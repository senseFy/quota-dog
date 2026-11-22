package saien.quotadog.hostsync

import io.ktor.client.HttpClient
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import saien.quotadog.AccountKey
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.QuotaDogStore
import kotlin.random.Random

/** An outer 403 is an admission failure, not an authenticated instruction to erase a cache. */
class RelayTicketRejected : IOException()

@Serializable private data class SavedHost(val route: String, val pin: String, val token: String,
    val revision: Long = -1, val card: SharedDevin? = null, val confirmed: Boolean = revision >= 0,
    val incoming: ReceivedShares? = null, val outgoing: LocalShares = LocalShares()) {
    val pair get() = digest(token.encodeToByteArray())
    val cards get() = incoming?.cards ?: listOfNotNull(card?.toCard())
}
data class PhoneState(val paired: Boolean = false, val online: Boolean = false, val digits: String? = null,
    val cards: List<SharedCard> = emptyList(), val busyRefs: Set<String> = emptySet(), val note: String = "Connect your computer",
    val pairId: String? = null, val grants: Map<AccountKey, GrantPermission> = emptyMap(), val workspaceGrants: Map<AccountKey, GrantPermission> = emptyMap(),
    val legacy: Boolean = false, val clockConflict: Boolean = false)

// This device-local record contains only opaque grant refs and permission levels. It is writable
// independently of the sealed pairing. Neither record alone authorizes a phone-owned source.
const val PHONE_GRANT_LIMITS = "phone-grant-limits-v1"
private const val PAUSED = "Sharing paused. Unlock the phone and reopen QuotaDog to retry."

class PhoneSync(private val client: HttpClient, private val identity: DeviceIdentity, private val vault: SyncVault) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var saved = vault.read("host")?.let { Json.decodeFromString<SavedHost>(it) }
    private val ui = MutableStateFlow(savedState())
    val state = ui.asStateFlow()
    private var running: Job? = null
    private var network: Long? = null
    private var session: SecureChannel? = null
    private var stream: ShareStream? = null
    private var store: QuotaDogStore? = null
    private var observer: Job? = null
    private var storagePaused = false

    init {
        saved?.takeIf { it.outgoing.entries.isNotEmpty() }?.let { host ->
            val limits = vault.read(PHONE_GRANT_LIMITS)?.let { Json.decodeFromString<Map<String, Access>>(it) }.orEmpty()
            val capped = host.outgoing.entries.mapNotNull { grant ->
                val access = minOf(grant.access, limits[grant.ref] ?: Access.Off)
                grant.copy(access = access).takeUnless { access == Access.Off }
            }
            if (capped != host.outgoing.entries) saveGrants(host, LocalShares(host.outgoing.revision + 1, capped))
        }
    }

    private fun savedState() = PhoneState(paired = saved?.confirmed == true, cards = saved?.cards.orEmpty(),
        pairId = saved?.pair, grants = saved?.outgoing?.permissions(false).orEmpty(), workspaceGrants = saved?.outgoing?.permissions(true).orEmpty(),
        clockConflict = saved?.incoming?.clockConflict == true)

    fun bindStore(value: QuotaDogStore) { scope.launch {
        if (value === store) return@launch
        observer?.cancel(); store = value
        observer = scope.launch { value.state.collect { publishLocal() } }
    } }

    fun matchFor(snapshot: ProviderUsageSnapshot): String? = saved?.let { matchForPair(it.pair, snapshot) }

    private suspend fun publishLocal() {
        val host = saved ?: return
        if (storagePaused) return
        val local = store ?: run { if (host.outgoing.entries.isEmpty()) stream?.publish(host.outgoing, null); return }
        val valid = host.outgoing.valid(local)
        val latest = saved ?: return
        if (storagePaused || latest.token != host.token || latest.outgoing !== host.outgoing) return
        if (valid !== host.outgoing && !saveGrants(latest, valid)) return
        stream?.publish(valid, local)
    }

    private fun saveGrants(host: SavedHost, shares: LocalShares): Boolean {
        val candidate = host.copy(outgoing = shares)
        val limitsSaved = if (!storagePaused && host.outgoing.revision == shares.revision) true else try {
            vault.write(PHONE_GRANT_LIMITS, Json.encodeToString(shares.entries.associate { it.ref to it.access })); true
        } catch (_: Exception) { false }
        // Attempt both writes. If either one fails, a withdrawal in the other still caps a cold start.
        val pairingSaved = try { save(candidate); true } catch (_: Exception) { false }
        if (!limitsSaved || !pairingSaved) {
            saved = candidate // Unrelated edits and reconnects must retry this intent, not the old permissions.
            storagePaused = true; running?.cancel()
            ui.value = savedState().copy(note = PAUSED)
            return false
        }
        storagePaused = false
        ui.value = ui.value.copy(grants = shares.permissions(false), workspaceGrants = shares.permissions(true))
        return true
    }

    fun setAccess(pairId: String?, edit: GrantEdit) { scope.launch {
        if (storagePaused) running?.join()
        val source = edit.checkedSource(store)
        val latest = saved?.takeIf { it.confirmed && it.pair == pairId } ?: return@launch
        val next = latest.outgoing.change(latest.pair, edit, source)
        if (next === latest.outgoing && !storagePaused || !saveGrants(latest, next)) return@launch
        publishLocal(); reconnect()
    } }

    private fun save(host: SavedHost?) {
        vault.write("host", host?.let { Json.encodeToString(it) }); saved = host
    }

    /** Null means offline or backgrounded. A different default network needs a fresh TLS session,
     * even if the old TCP connection hasn't noticed that its Wi-Fi path disappeared. */
    fun useNetwork(handle: Long?) { scope.launch {
        if (network == handle) return@launch
        network = handle
        val old = running; old?.cancelAndJoin()
        if (running === old) {
            running = null
            if (saved != null) disconnected()
            else if (old != null) ui.value = PhoneState()
            reconnect() // A newer network or a pause can arrive during cancellation; use the latest.
        }
    } }
    private suspend fun clearPairing(note: String): Boolean {
        running?.cancelAndJoin(); running = null
        saved?.takeIf { it.outgoing.entries.isNotEmpty() || storagePaused }?.let {
            if (!saveGrants(it, LocalShares(it.outgoing.revision + 1))) return false
        }
        try { save(null) } catch (_: Exception) {
            ui.value = ui.value.copy(online = false, busyRefs = emptySet(), note = "Could not remove pairing. Unlock the phone and retry.")
            return false
        }
        storagePaused = false
        ui.value = PhoneState(note = note)
        return true
    }
    fun forget() { scope.launch { clearPairing("Computer disconnected") } }
    fun cancelPairing() { scope.launch {
        if (saved?.confirmed == true) return@launch
        clearPairing("Connect your computer")
    } }
    fun pair(qr: String) { scope.launch {
        if (saved?.confirmed == true || (saved == null && running?.isActive == true)) return@launch
        if (network == null) { ui.value = PhoneState(note = "Connect to a network and scan again"); return@launch }
        val invite = Invite.parse(qr)
        if (invite == null) { ui.value = PhoneState(note = "Not a QuotaDog pairing QR"); return@launch }
        if (saved != null) {
            if (!clearPairing("Connect your computer")) return@launch // Replace an unfinished ticket.
            if (network == null) { ui.value = PhoneState(note = "Connect to a network and scan again"); return@launch }
        }
        running = scope.launch {
            ui.value = PhoneState(note = "Verifying computer…")
            try { withTimeout(300_000) { talk(invite, null) } }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); localFailure("enrollment", e) }
            if (saved == null) ui.value = PhoneState(note = "Pairing did not finish. Open a new invitation.")
            running = null
            reconnect()
        }
    } }

    private fun reconnect() {
        val pending = saved ?: return
        if (network == null || running?.isCompleted == false) return
        if (storagePaused && !saveGrants(pending, pending.outgoing)) return
        running = scope.launch {
            var wait = 1_000L
            while (isActive) {
                val host = saved ?: break
                try { talk(null, host) { wait = 1_000 } }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (host.confirmed && generateSequence<Throwable>(e) { it.cause }.any { it is RelayTicketRejected }) {
                        ui.value = ui.value.copy(paired = true, online = false, busyRefs = emptySet(), note = "Relay did not accept this pairing")
                        break
                    }
                    localFailure("reconnect", e)
                }
                if (saved == null) break
                disconnected()
                delay(wait + Random.nextInt(400)); wait = (wait * 2).coerceAtMost(60_000)
            }
        }
    }

    private suspend fun talk(invite: Invite?, host: SavedHost?, connected: () -> Unit = {}): Unit = coroutineScope {
        val ws = withTimeout(10_000) { client.connect(invite?.route ?: host!!.route, if (invite == null) "device" else "enroll", host?.token) }
        try {
            SecureChannel(false, identity, invite?.pin ?: host!!.pin) { ws.emit(Frame.Binary(true, it)) }.use { tls ->
                suspend fun read(): List<Msg> {
                    val frame = ws.incoming.receive() as? Frame.Binary ?: error("Expected relay data")
                    return tls.receive(frame.data)
                }
                tls.flush()
                withTimeout(10_000) { while (tls.peer == null) check(read().isEmpty()) }
                if (invite != null) tls.send(Msg.Enroll(invite.secret)) else {
                    tls.send(Msg.Sync)
                    tls.send(Msg.Refresh(CAPABILITIES, "1", -1))
                }
                val link = ShareStream(tls)
                var legacy: Msg.Manifest? = null
                var legacyOnly = false
                var receivedManifest = false
                var compared = false
                fun receiveLegacy(msg: Msg.Manifest) {
                    val old = checkNotNull(saved)
                    if (old.incoming != null) {
                        // A downgraded Mac cannot withdraw the v2 list. Keep the cache and don't
                        // send a v2 request to an endpoint that can't interpret its permissions.
                        ui.value = savedState().copy(note = "Update QuotaDog on your computer to resume sharing", legacy = true)
                        receivedManifest = true; return
                    }
                    if (msg.revision < old.revision) return
                    val incoming = msg.card
                    check(incoming == null || (incoming.ref.isHex(16) && incoming.access != Access.Off))
                    val prior = old.card?.takeIf { it.ref == incoming?.ref }?.reading
                    val reading = if (prior != null && prior.collectedAt >= (incoming?.reading?.collectedAt ?: Long.MIN_VALUE)) prior else incoming?.reading
                    val card = incoming?.copy(reading = reading)
                    if (!old.confirmed || old.card != card || old.revision != msg.revision) save(old.copy(revision = msg.revision, card = card, confirmed = true))
                    if (!receivedManifest) connected()
                    receivedManifest = true; session = tls
                    ui.value = ui.value.copy(paired = true, online = true, cards = saved!!.cards, legacy = true,
                        note = "Update QuotaDog on your computer to share more accounts")
                }
                while (true) {
                    val messages = if (invite == null && !receivedManifest) withTimeout(10_000) { read() } else read()
                    for (msg in messages) {
                        if (invite != null) when (msg) {
                            is Msg.Compare -> {
                                check(!compared && msg.digits.matches(Regex("[0-9]{6}")))
                                compared = true; ui.value = PhoneState(digits = msg.digits, note = "Compare both screens")
                            }
                            is Msg.Grant -> {
                                check(compared && saved == null && msg.token.isHex(32))
                                save(SavedHost(invite.route, invite.pin, msg.token)); tls.send(Msg.Stored)
                            }
                            Msg.Paired -> { save(checkNotNull(saved).copy(confirmed = true)); return@coroutineScope }
                            else -> error("No account data during enrollment")
                        } else when (msg) {
                            is Msg.Manifest -> {
                                check(stream == null); legacy = msg
                                if (legacyOnly) receiveLegacy(msg)
                            }
                            Msg.Ready -> { check(legacy != null && !receivedManifest && stream == null); stream = link; publishLocal() }
                            is Msg.Sources -> {
                                check(stream === link)
                                val old = checkNotNull(saved)
                                val received = old.incoming ?: ReceivedShares(cards = old.cards)
                                check(msg.revision >= received.revision || receivedManifest)
                                val next = received.manifest(msg)
                                if (old.incoming != next || !old.confirmed) save(old.copy(card = null, revision = -1, incoming = next, confirmed = true))
                                val note = if (!receivedManifest || received.revision != next.revision) "Computer connected" else ui.value.note
                                if (!receivedManifest) connected()
                                receivedManifest = true; link.receivedManifest = true; session = tls
                                ui.value = ui.value.copy(paired = true, online = true, digits = null, cards = next.cards, note = note, legacy = false,
                                    clockConflict = next.clockConflict)
                            }
                            is Msg.Observation -> {
                                check(stream === link && receivedManifest)
                                val old = checkNotNull(saved); val current = checkNotNull(old.incoming)
                                val next = current.observation(msg)
                                if (next !== current) { save(old.copy(incoming = next)); ui.value = ui.value.copy(cards = next.cards, clockConflict = next.clockConflict) }
                            }
                            is Msg.Refresh -> {
                                check(stream === link && receivedManifest); link.checkSequence(msg)
                                launch {
                                    val status = refreshGranted(store, msg) { saved?.outgoing?.takeIf { stream === link && !storagePaused && session === tls } }
                                    if (stream === link && !storagePaused) publishLocal()
                                    if (stream === link && !storagePaused && session === tls) tls.send(Msg.Result(msg.id, status))
                                }
                            }
                            is Msg.Result -> if (!receivedManifest && msg.id == "1" && msg.status == RefreshStatus.Denied && stream == null) {
                                legacyOnly = true; receiveLegacy(checkNotNull(legacy))
                            } else if (receivedManifest && (link.pending.remove(msg.id) != null || legacyPending == msg.id)) {
                                if (legacyPending == msg.id) legacyPending = null
                                ui.value = ui.value.copy(busyRefs = link.pending.values.toSet(), note = when (msg.status) {
                                    RefreshStatus.Updated -> "Updated by computer"
                                    RefreshStatus.Denied -> "Refresh not permitted"
                                    RefreshStatus.Busy -> "Computer is already refreshing"
                                    RefreshStatus.Failed -> "Refresh failed on computer"
                                })
                            }
                            Msg.Revoked -> { save(null); ui.value = PhoneState(note = "Pairing revoked by computer"); return@coroutineScope }
                            else -> error("Unexpected sync message")
                        }
                    }
                }
            }
        } finally {
            coroutineContext.cancelChildren()
            session = null; stream = null; legacyPending = null; ws.cancel()
            disconnected()
        }
    }

    private fun disconnected() {
        val host = saved ?: return
        ui.value = savedState().copy(note = if (storagePaused) PAUSED else if (host.confirmed) "Computer unavailable"
            else "Pairing not confirmed. Reconnecting, or scan a new invitation.")
    }

    private var legacySequence = 1L
    private var legacyPending: String? = null
    fun refresh(ref: String) { scope.launch {
        val card = ui.value.cards.find { it.ref == ref && it.access == Access.Refresh } ?: return@launch
        val ch = session ?: return@launch
        val host = saved ?: return@launch
        val link = stream
        val id = if (link == null) {
            if (!ui.value.legacy || legacyPending != null) return@launch
            (++legacySequence).toString().also { ch.send(Msg.Refresh(ref, it, host.revision)); legacyPending = it }
        } else link.request(card, host.incoming?.revision ?: return@launch) ?: return@launch
        ui.value = ui.value.copy(busyRefs = ui.value.busyRefs + ref, note = "Refreshing on computer…")
        delay(30_000)
        if (session === ch && (link?.pending?.remove(id) != null || legacyPending == id)) {
            if (legacyPending == id) legacyPending = null
            ui.value = ui.value.copy(busyRefs = ui.value.busyRefs - ref, note = "Refresh not confirmed")
        }
    } }
    override fun close() { scope.cancel(); client.close() }
}
