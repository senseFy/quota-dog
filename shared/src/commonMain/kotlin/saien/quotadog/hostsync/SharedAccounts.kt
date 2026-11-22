package saien.quotadog.hostsync

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import saien.quotadog.*

enum class SourceStatus { Ready, LoginRequired, RateLimited, Failed }

@Serializable data class SharedSource(val ref: String, val provider: ProviderId, val access: Access,
    val match: String? = null, val status: SourceStatus = SourceStatus.Ready, val workspace: Boolean = false)

@Serializable data class SharedCard(val source: SharedSource, val reading: QuotaReading? = null,
    val uncertain: QuotaReading? = null) {
    val ref get() = source.ref
    val provider get() = source.provider
    val access get() = source.access
    val match get() = source.match
    val status get() = source.status
    val workspace get() = source.workspace
}

// Stored only on the collector in its device-pairing vault. Never serialize this type to a peer.
@Serializable internal data class LocalGrant(val key: AccountKey, val login: String, val ref: String, val access: Access,
    val scope: String? = null, val match: String? = null, val workspace: Boolean = false, val last: QuotaReading? = null)

private const val MAX_SOURCES = 64 // The whole descriptor list must fit in one 16 KiB app frame.
internal const val CLOCK_TOLERANCE_MS = 300_000L
internal fun nowMs() = Clock.System.now().toEpochMilliseconds()

internal fun matchForPair(pair: String, snapshot: ProviderUsageSnapshot): String? {
    if (snapshot.stale || snapshot.loginTag == null || snapshot.providerId !in mergeable) return null
    val verified = snapshot.quotaIdentity ?: return null
    return hmacSha256(pair.hexToByteArray(), "QuotaDog:match:v2:${snapshot.providerId.name}:$verified".encodeToByteArray()).toHexString()
}
private val mergeable = setOf(ProviderId.CODEX, ProviderId.CLAUDE_CODE, ProviderId.ANTIGRAVITY)

// A workspace without independent identity evidence may still have a query target (e.g. the
// effective Cloud Code project). Bind that target locally even though it cannot be used to merge.
private fun ProviderUsageSnapshot.scopeFor(workspace: Boolean): String? {
    val evidence = if (workspace) ampWorkspaceScope else if (quotaTarget != null) kotlinx.serialization.json.Json.encodeToString(listOf(quotaIdentity, quotaTarget)) else quotaIdentity
    return evidence?.let { digest(it.encodeToByteArray()) }
}

data class GrantPermission(val ref: String, val access: Access)

/** Local UI intent, never a wire message. A delayed click cannot authorize a different login or workspace. */
class GrantEdit(val key: AccountKey, val workspace: Boolean, val previous: GrantPermission?, val access: Access,
                private val shown: ProviderUsageSnapshot?) {
    internal suspend fun checkedSource(store: QuotaDogStore?): ProviderUsageSnapshot? {
        if (access <= (previous?.access ?: Access.Off)) return null // Restricting a grant needs no live collector.
        val login = shown?.loginTag ?: return null
        if (store == null || key.isPending || store.currentLogin(key) != login) return null
        return store.state.value.accounts[key]?.takeIf { it.added && it.authState == AuthState.LoggedIn }?.snapshot?.takeIf {
            it.providerId == key.providerId && it.loginTag == login && it.scopeFor(workspace) == shown.scopeFor(workspace)
        }
    }
}

@Serializable internal data class LocalShares(val revision: Long = 0, val entries: List<LocalGrant> = emptyList()) {
    fun permissions(workspace: Boolean) = entries.filter { it.workspace == workspace }.associate { it.key to GrantPermission(it.ref, it.access) }

    fun change(pair: String, edit: GrantEdit, snapshot: ProviderUsageSnapshot?): LocalShares {
        val previous = entries.find { it.key == edit.key && it.workspace == edit.workspace }
        if (previous?.ref != edit.previous?.ref || previous?.access == edit.access) return this
        if (edit.access == Access.Off) return if (previous == null) this else copy(revision + 1, entries - previous)
        val next = if (previous != null && edit.access < previous.access) previous.copy(access = edit.access) else {
            if (previous?.access != edit.previous?.access || (previous == null && entries.size >= MAX_SOURCES)) return this
            val login = snapshot?.loginTag ?: return this
            val reading = snapshot.toQuotaReading(edit.workspace) ?: return this
            val scope = snapshot.scopeFor(edit.workspace)
            if (edit.workspace && scope == null || previous != null && (previous.login != login || previous.scope != scope)) return this
            LocalGrant(edit.key, login, previous?.ref ?: randomHex(16), edit.access, scope, matchForPair(pair, snapshot), edit.workspace, reading)
        }
        return copy(revision + 1, if (previous == null) entries + next else entries.map { if (it.ref == previous.ref) next else it })
    }

    suspend fun valid(store: QuotaDogStore): LocalShares {
        val keep = entries.mapNotNull { grant ->
            if (store.currentLogin(grant.key) != grant.login) return@mapNotNull null
            // Only a local collector with the same verified login and target may advance the
            // durable outgoing copy. A cold store or restored Dropbox reading has no provenance.
            val last = store.state.value.accounts[grant.key]?.snapshot?.takeIf { it.loginTag != null } ?: return@mapNotNull grant
            if (last.loginTag != grant.login || last.scopeFor(grant.workspace) != grant.scope) return@mapNotNull null
            val value = last.toQuotaReading(grant.workspace) ?: return@mapNotNull grant
            if (grant.last == null || value.collectedAt > grant.last.collectedAt ||
                (grant.last.collectedAt > nowMs() + CLOCK_TOLERANCE_MS && value.collectedAt < grant.last.collectedAt)) grant.copy(last = value) else grant
        }
        return if (entries == keep) this else copy(if (keep.size == entries.size) revision else revision + 1, keep)
    }

    fun cards(store: QuotaDogStore?): List<SharedCard> = entries.map { grant ->
        val state = store?.state?.value?.accounts?.get(grant.key)
        val status = when (state?.authState) {
            AuthState.Unauthorized, AuthState.RequiresRelogin, AuthState.NotConfigured, AuthState.TokenExpired -> SourceStatus.LoginRequired
            AuthState.RateLimited -> SourceStatus.RateLimited
            AuthState.Error -> SourceStatus.Failed
            else -> SourceStatus.Ready
        }
        SharedCard(SharedSource(grant.ref, grant.key.providerId, grant.access, grant.match, status, grant.workspace), grant.last)
    }
}

/** Replace membership only after an entire authenticated list arrives. Readings have their own
 * bounded message and cannot introduce a ref or change its provider, permission or scope. */
@Serializable internal data class ReceivedShares(val revision: Long = -1, val cards: List<SharedCard> = emptyList(),
    val clockConflict: Boolean = false) {
    fun manifest(msg: Msg.Sources, now: Long = nowMs()): ReceivedShares {
        if (msg.revision < revision) return this
        require(msg.revision >= 0 && msg.entries.size <= MAX_SOURCES && msg.entries.distinctBy { it.ref }.size == msg.entries.size)
        val byRef = cards.associateBy { it.ref }
        val next = msg.entries.map { source ->
            require(source.ref.isHex(16) && source.access != Access.Off && (!source.workspace || source.provider == ProviderId.AMP))
            require(source.match == null || (source.match.isHex(32) && source.provider in mergeable))
            val prior = byRef[source.ref]
            require(prior == null || (prior.provider == source.provider && prior.workspace == source.workspace && prior.match == source.match))
            prior?.copy(source = source) ?: SharedCard(source)
        }
        if (revision == msg.revision) require(cards.map { it.ref to it.access } == next.map { it.ref to it.access })
        return ReceivedShares(msg.revision, next, msg.now !in (now - CLOCK_TOLERANCE_MS)..(now + CLOCK_TOLERANCE_MS))
    }

    fun observation(msg: Msg.Observation, now: Long = nowMs()): ReceivedShares {
        if (revision != msg.revision) return this
        val card = cards.find { it.ref == msg.ref } ?: return this
        require(msg.value.provider == card.provider)
        require(card.workspace == (msg.value.details is QuotaDetails.AmpWorkspace))
        // An unorderable sample can be inspected at its source, but cannot replace the last
        // trustworthy value or set a high-water mark that blocks the collector's corrected time.
        val skewed = msg.now !in (now - CLOCK_TOLERANCE_MS)..(now + CLOCK_TOLERANCE_MS)
        val next = when {
            skewed || msg.value.collectedAt > now + CLOCK_TOLERANCE_MS -> card.copy(uncertain = msg.value)
            msg.value.collectedAt == card.uncertain?.collectedAt -> card
            (card.reading?.collectedAt ?: Long.MIN_VALUE) < msg.value.collectedAt -> card.copy(reading = msg.value, uncertain = null)
            else -> card
        }
        return if (next == card && skewed == clockConflict) this else copy(clockConflict = skewed,
            cards = cards.map { if (it.ref == msg.ref) next else it })
    }
}

internal fun SharedDevin.toCard(): SharedCard {
    val value = reading?.let { old ->
        val windows = listOfNotNull(old.daily?.let { QuotaWindow(QuotaWindowKind.DevinDaily, it.used, it.resetsAt, 86_400) },
            old.weekly?.let { QuotaWindow(QuotaWindowKind.DevinWeekly, it.used, it.resetsAt, 604_800) })
        QuotaReading(ProviderId.DEVIN, old.collectedAt, windows, old.extraUsd?.let(QuotaDetails::Devin))
    }
    return SharedCard(SharedSource(ref, ProviderId.DEVIN, access), value)
}

/** Per TLS session, per direction. The source list is atomic; unchanged readings aren't replayed
 * on unrelated dashboard changes. The revision travels with each result and refresh request. */
internal class ShareStream(private val tls: SecureChannel) {
    private var revision = -1L
    private var previous = emptyList<SharedCard>()
    private var sequence = 0L
    private var lastRequest = 0L
    val pending = mutableMapOf<String, String>() // request → ref; discarded with this connection
    var receivedManifest = false

    fun publish(shares: LocalShares, store: QuotaDogStore?) {
        val next = shares.cards(store)
        if (revision != shares.revision || next.map { it.source } != previous.map { it.source }) {
            tls.send(Msg.Sources(shares.revision, next.map { it.source }, nowMs()))
        }
        for (card in next) {
            val value = card.reading ?: continue
            if (previous.find { it.ref == card.ref }?.reading != value) tls.send(Msg.Observation(shares.revision, card.ref, value, nowMs()))
        }
        revision = shares.revision; previous = next
    }

    fun request(card: SharedCard, revision: Long): String? {
        if (card.access != Access.Refresh || card.ref in pending.values) return null
        val id = (++sequence).toString()
        tls.send(Msg.Refresh(card.ref, id, revision)); pending[id] = card.ref
        return id
    }

    fun checkSequence(req: Msg.Refresh) {
        val seq = req.id.toLongOrNull() ?: error("Invalid refresh")
        require(seq > lastRequest); lastRequest = seq
    }
}

/** The authorization closure must re-read current local grants and current session. Joining an
 * existing local query doesn't relax either the pre-query check or the last check before reply. */
internal suspend fun refreshGranted(store: QuotaDogStore?, req: Msg.Refresh, shares: () -> LocalShares?): RefreshStatus {
    fun allowed() = shares()?.takeIf { it.revision == req.revision }?.entries?.find { it.ref == req.ref && it.access == Access.Refresh }
    val grant = allowed() ?: return RefreshStatus.Denied
    if (store == null || store.currentLogin(grant.key) != grant.login || allowed()?.ref != grant.ref) return RefreshStatus.Denied
    val updated = store.refresh(grant.key, grant.login)
    if (store.currentLogin(grant.key) != grant.login || allowed()?.ref != grant.ref) return RefreshStatus.Denied
    if (!updated) return RefreshStatus.Failed
    val after = store.state.value.accounts[grant.key]?.snapshot ?: return RefreshStatus.Failed
    if (after.loginTag != grant.login || after.scopeFor(grant.workspace) != grant.scope) return RefreshStatus.Denied
    return if (after.toQuotaReading(grant.workspace) != null) RefreshStatus.Updated else RefreshStatus.Failed
}
