package saien.quotadog.app

import kotlinx.datetime.Clock
import saien.quotadog.AccountKey
import saien.quotadog.AccountSortMode
import saien.quotadog.AccountUiState
import saien.quotadog.AuthState
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.availableProviders
import saien.quotadog.hostsync.Access
import saien.quotadog.hostsync.QuotaDetails
import saien.quotadog.hostsync.QuotaReading
import saien.quotadog.hostsync.SharedCard
import saien.quotadog.hostsync.SourceStatus
import saien.quotadog.isQuotaExhausted
import saien.quotadog.sortName

/** One authenticated pairing. Its match function and refresh action only address this peer. */
class DashboardPeer(
    val id: String,
    val label: String,
    val refreshLabel: String,
    val online: Boolean,
    val cards: List<SharedCard>,
    val busyRefs: Set<String>,
    val matchFor: (ProviderUsageSnapshot) -> String?,
    val refresh: (String) -> Unit,
    val clockConflict: Boolean = false,
    val unconfirmedRefs: Set<String> = emptySet(),
)

internal sealed interface DashboardKey {
    data class Local(val key: AccountKey) : DashboardKey
    data class Remote(val peer: String, val ref: String) : DashboardKey
}

private fun SharedCard.inScope(value: QuotaReading?) = value?.takeIf {
    it.provider == provider && (it.details is QuotaDetails.AmpWorkspace) == workspace
}

private const val CLOCK_TOLERANCE_MS = 5 * 60_000L // Same boundary as the pairing manifest.

internal data class RemoteSource(
    val peer: DashboardPeer, val card: SharedCard,
    val comparableReading: QuotaReading? = card.inScope(card.reading)?.takeUnless { peer.clockConflict },
    val clockWarning: Boolean = peer.clockConflict || card.uncertain != null,
) {
    val key = DashboardKey.Remote(peer.id, card.ref)
    // A ref is random per grant and pairing, not an account id, email, workspace name or match tag.
    val label = "${if (card.workspace) "Workspace" else "Account"} ${card.ref.take(8).uppercase()}"
    // A suspect timestamp is inspectable only on its own source; it never wins a merge.
    val trustedReading get() = comparableReading ?: card.inScope(card.reading)
    val reading get() = trustedReading ?: card.inScope(card.uncertain)
    val uncertainOnly get() = trustedReading == null && reading != null
    val busy get() = card.ref in peer.busyRefs
    val refreshable get() = peer.online && card.access == Access.Refresh

    fun refresh() { if (refreshable && !busy) peer.refresh(card.ref) }
}

/** In-memory comparison state, not a second store. The pairing vault owns accepted/uncertain
 * observations across restarts. Removing a grant or changing a local login drops its history. */
internal class DashboardReadingHistory {
    private var previous = emptyMap<DashboardKey.Remote, QuotaReading>()
    private var local = emptyMap<AccountKey, ProviderUsageSnapshot>()

    fun localObservations(accounts: List<AccountUiState>, now: Long): Map<AccountKey, ProviderUsageSnapshot> {
        local = buildMap {
            for (account in accounts) {
                val current = account.attributableSnapshot() ?: continue
                // A different login or query target is a different source, even at the same key.
                val old = local[account.accountKey]?.takeIf {
                    it.collectedAt.toEpochMilliseconds() <= now + CLOCK_TOLERANCE_MS && it.loginTag == current.loginTag &&
                        it.quotaIdentity == current.quotaIdentity && it.quotaTarget == current.quotaTarget
                }
                val accepted = if (current.collectedAt.toEpochMilliseconds() <= now + CLOCK_TOLERANCE_MS &&
                    (old == null || current.collectedAt > old.collectedAt)) current else old
                if (accepted != null) put(account.accountKey, accepted)
            }
        }
        return local
    }

    fun sources(peers: List<DashboardPeer>, now: Long): List<RemoteSource> {
        val next = mutableMapOf<DashboardKey.Remote, QuotaReading>()
        val result = buildList {
            for (peer in peers) for (card in peer.cards) {
                if (card.access == Access.Off) continue
                val key = DashboardKey.Remote(peer.id, card.ref)
                val incoming = card.inScope(card.reading)
                val old = card.inScope(previous[key])?.takeIf { incoming != null && it.collectedAt <= now + CLOCK_TOLERANCE_MS }
                val suspect = peer.clockConflict || (incoming != null && incoming.collectedAt > now + CLOCK_TOLERANCE_MS)
                val accepted = if (!suspect && incoming != null && (old == null || incoming.collectedAt > old.collectedAt)) incoming else old
                if (accepted != null) next[key] = accepted
                add(RemoteSource(peer, card, accepted, suspect || card.uncertain != null))
            }
        }
        previous = next
        return result
    }
}

/** No remote ref is an AccountKey. Only a Local row can enter login, delete or grant UI. */
internal sealed interface DashboardAccount {
    val key: DashboardKey
    val provider: ProviderId
    val sortName: String
    val collectedAt: Long?

    data class Local(
        val account: AccountUiState, val copies: List<RemoteSource>, val localObservation: ProviderUsageSnapshot?,
        val displayedRemote: RemoteSource?, val localClockWarning: Boolean,
    ) : DashboardAccount {
        override val key = DashboardKey.Local(account.accountKey)
        override val provider get() = account.providerId
        override val sortName get() = account.sortName()
        override val collectedAt get() = displayedRemote?.trustedReading?.collectedAt ?: localObservation?.collectedAt?.toEpochMilliseconds()
    }

    data class Remote(val source: RemoteSource) : DashboardAccount {
        override val key get() = source.key
        override val provider get() = source.card.provider
        override val sortName get() = "${provider.displayName} ${source.peer.label} ${source.label}".lowercase()
        override val collectedAt get() = source.trustedReading?.collectedAt
    }
}

/** Select exactly one target per row, independently of the source of the value on its face. */
internal sealed interface DashboardRefresh {
    val label: String
    data class Here(val account: AccountUiState) : DashboardRefresh { override val label = "Refresh on this device" }
    data class There(val source: RemoteSource) : DashboardRefresh { override val label get() = source.peer.refreshLabel }

    fun run(onLocal: (AccountUiState) -> Unit) = when (this) {
        is Here -> onLocal(account)
        is There -> source.refresh()
    }
}

internal val DashboardAccount.busy get() = when (this) {
    is DashboardAccount.Local -> account.busy || copies.any { it.busy }
    is DashboardAccount.Remote -> source.busy
}

internal fun DashboardAccount.refreshTarget(
    collectors: List<ProviderId> = availableProviders(), retryStandalone: Boolean = false,
): DashboardRefresh? = when (this) {
    is DashboardAccount.Remote -> source.takeIf { it.refreshable }?.let(DashboardRefresh::There)
    is DashboardAccount.Local -> when {
        !account.added || account.authState == AuthState.RateLimited -> null
        provider !in collectors || account.authState in listOf(AuthState.NotConfigured, AuthState.Unauthorized, AuthState.RequiresRelogin) ->
            copies.firstOrNull { it.refreshable && it.card.status == SourceStatus.Ready }?.let(DashboardRefresh::There)
        // Local tokens can be renewed, and a failed local request can be retried. Neither is a
        // reason to issue another provider request from a peer.
        account.authState == AuthState.LoggedIn || account.authState == AuthState.TokenExpired ||
            (account.authState == AuthState.Error && (copies.isNotEmpty() || retryStandalone && !account.accountKey.isPending)) ->
            DashboardRefresh.Here(account)
        else -> null
    }
}

internal fun List<DashboardAccount>.refreshAllTargets(retryStandalone: Boolean = false): List<DashboardRefresh> =
    mapNotNull { if (it.busy) null else it.refreshTarget(retryStandalone = retryStandalone) }

internal fun AccountUiState.attributableSnapshot(): ProviderUsageSnapshot? = snapshot?.takeIf {
    // A later sign-in or rate-limit error doesn't invalidate the last locally observed identity.
    added && !accountKey.isPending && accountKey.providerId == providerId && providerId == it.providerId &&
        it.authState == AuthState.LoggedIn && !it.stale && !it.loginTag.isNullOrBlank()
}

internal fun dashboardAccounts(
    local: List<AccountUiState>, peers: List<DashboardPeer>, now: Long = Clock.System.now().toEpochMilliseconds(),
    history: DashboardReadingHistory = DashboardReadingHistory(),
): List<DashboardAccount> {
    val copies = mutableMapOf<AccountKey, MutableList<RemoteSource>>()
    val remote = mutableListOf<DashboardAccount.Remote>()
    val observed = history.localObservations(local, now)
    for ((peer, sources) in history.sources(peers, now).groupBy { it.peer }) {
        val matched = sources.filter { !it.card.workspace && !it.card.match.isNullOrBlank() }
            .groupBy { it.card.provider to it.card.match }
        // Compute a tag once per local source, for this peer only. Ambiguity in either direction
        // leaves separate rows; the same ref or match on two phones is not proof of identity.
        val candidates = if (matched.isEmpty()) emptyMap() else local.mapNotNull { account ->
            val snapshot = account.attributableSnapshot() ?: return@mapNotNull null
            if (snapshot.quotaIdentity.isNullOrBlank()) return@mapNotNull null
            val tag = peer.matchFor(snapshot)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            (account.providerId to tag) to account.accountKey
        }.groupBy({ it.first }, { it.second })
        for (source in sources) {
            val evidence = source.card.provider to source.card.match
            val owner = if (matched[evidence]?.singleOrNull() == source) candidates[evidence]?.singleOrNull() else null
            if (owner == null) remote += DashboardAccount.Remote(source)
            else copies.getOrPut(owner) { mutableListOf() }.add(source)
        }
    }
    return local.map { account ->
        val matches = copies[account.accountKey].orEmpty()
        val localObservation = if (matches.isEmpty()) account.snapshot else observed[account.accountKey]
        val localTime = localObservation?.collectedAt?.toEpochMilliseconds()
        val newest = matches.filter { (it.comparableReading?.collectedAt ?: Long.MIN_VALUE) > (localTime ?: Long.MIN_VALUE) }
            .maxByOrNull { it.comparableReading!!.collectedAt }
        DashboardAccount.Local(account, matches, localObservation, newest,
            (account.snapshot?.collectedAt?.toEpochMilliseconds() ?: 0) > now + CLOCK_TOLERANCE_MS)
    } + remote
}

internal fun List<DashboardAccount>.inDashboardOrder(
    mode: AccountSortMode, localOrder: List<AccountKey>, sessionOrder: List<DashboardKey>, reversed: Boolean,
    sortName: (DashboardAccount) -> String = { it.sortName },
): List<DashboardAccount> {
    val byName = compareBy(sortName).thenBy { it.provider.displayName.lowercase() }
        .thenBy { when (val k = it.key) { is DashboardKey.Local -> k.key.accountId; is DashboardKey.Remote -> k.ref } }
    val forward = when (mode) {
        AccountSortMode.Name -> sortedWith(byName)
        AccountSortMode.RefreshTime -> sortedWith(compareByDescending<DashboardAccount> { it.collectedAt }.then(byName))
        AccountSortMode.Manual -> {
            val named = sortedWith(byName)
            val rows = associateBy { it.key }
            // Only local positions are saved; remote positions last for this dashboard session.
            val locals = (localOrder.map(DashboardKey::Local) + named.mapNotNull { it.key as? DashboardKey.Local })
                .distinct().filter { it in rows }
            val slots = (sessionOrder + locals + named.map { it.key }).distinct().filter { it in rows }
            val nextLocal = locals.iterator()
            slots.map { rows.getValue(if (it is DashboardKey.Local) nextLocal.next() else it) }
        }
    }
    if (!reversed) return forward
    if (mode != AccountSortMode.RefreshTime) return forward.asReversed()
    return forward.filter { it.collectedAt != null }.asReversed() + forward.filter { it.collectedAt == null }
}

/** Permute only the filtered slots. Hidden providers keep their positions in the full order. */
internal fun moveDashboardKey(
    displayed: List<DashboardAccount>, provider: ProviderId?, key: DashboardKey, delta: Int,
): List<DashboardKey> {
    val visible = displayed.filter { provider == null || it.provider == provider }.map { it.key }.toMutableList()
    val index = visible.indexOf(key)
    if (index < 0 || index + delta !in visible.indices) return displayed.map { it.key }
    visible.add(index + delta, visible.removeAt(index))
    val next = visible.iterator()
    return displayed.map { if (provider == null || it.provider == provider) next.next() else it.key }
}

// Only the collector can judge account-wide exhaustion from its complete set of provider pools.
// An intentionally limited remote projection must not turn a missing pool into a spent account.
internal val DashboardAccount.exhausted: Boolean get() = this is DashboardAccount.Local && displayedRemote == null &&
    (if (account.snapshot === localObservation) account else account.copy(snapshot = localObservation)).isQuotaExhausted()
