package saien.quotadog.hostsync

import io.ktor.client.HttpClient
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** An outer 403 is an admission failure, not an authenticated instruction to erase a cache. */
class RelayTicketRejected : IOException()

@Serializable private data class SavedHost(val route: String, val pin: String, val token: String,
                                          val revision: Long = -1, val card: SharedDevin? = null, val confirmed: Boolean = revision >= 0)
data class PhoneState(val paired: Boolean = false, val online: Boolean = false, val digits: String? = null,
                      val card: SharedDevin? = null, val busy: Boolean = false, val note: String = "Connect your computer")

class PhoneSync(private val client: HttpClient, private val identity: DeviceIdentity, private val vault: SyncVault) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var saved = vault.read("host")?.let { Json.decodeFromString<SavedHost>(it) }
    private val ui = MutableStateFlow(PhoneState(paired = saved?.confirmed == true, card = saved?.card))
    val state = ui.asStateFlow()
    private var running: Job? = null
    private var network: Long? = null
    private var session: SecureChannel? = null
    private var sequence = 0L
    private var pending: String? = null

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
        try { save(null) } catch (_: Exception) {
            ui.value = ui.value.copy(online = false, busy = false, note = "Could not remove pairing. Unlock the phone and retry.")
            return false
        }
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
        if (network == null || running?.isCompleted == false || saved == null) return
        running = scope.launch {
            var wait = 1_000L
            while (isActive) {
                val host = saved ?: break
                try { talk(null, host) { wait = 1_000 } }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (host.confirmed && generateSequence<Throwable>(e) { it.cause }.any { it is RelayTicketRejected }) {
                        ui.value = ui.value.copy(paired = true, online = false, busy = false, note = "Relay did not accept this pairing")
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

    private suspend fun talk(invite: Invite?, host: SavedHost?, connected: () -> Unit = {}) {
        val ws = withTimeout(10_000) { client.connect(invite?.route ?: host!!.route, if (invite == null) "device" else "enroll", host?.token) }
        try {
            SecureChannel(false, identity, invite?.pin ?: host!!.pin) { ws.emit(Frame.Binary(true, it)) }.use { tls ->
                suspend fun read(): List<Msg> {
                    val frame = ws.incoming.receive() as? Frame.Binary ?: error("Expected relay data")
                    return tls.receive(frame.data)
                }
                tls.flush()
                withTimeout(10_000) { while (tls.peer == null) check(read().isEmpty()) }
                if (invite != null) tls.send(Msg.Enroll(invite.secret)) else tls.send(Msg.Sync)
                var receivedManifest = false
                var compared = false
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
                            Msg.Paired -> { save(checkNotNull(saved).copy(confirmed = true)); return }
                            else -> error("No account data during enrollment")
                        } else when (msg) {
                            is Msg.Manifest -> {
                                val old = checkNotNull(saved)
                                check(msg.revision >= old.revision)
                                val incoming = msg.card
                                check(incoming == null || (incoming.ref.isHex(16) && incoming.access != Access.Off))
                                val prior = old.card?.takeIf { it.ref == incoming?.ref }?.reading
                                val reading = if (prior != null && prior.collectedAt >= (incoming?.reading?.collectedAt ?: Long.MIN_VALUE)) prior else incoming?.reading
                                val card = incoming?.copy(reading = reading)
                                if (!old.confirmed || old.card != card || old.revision != msg.revision)
                                    save(old.copy(revision = msg.revision, card = card, confirmed = true))
                                val note = if (!receivedManifest || old.revision != msg.revision) "Computer connected" else ui.value.note
                                if (!receivedManifest) connected()
                                receivedManifest = true; session = tls
                                ui.value = ui.value.copy(paired = true, online = true, digits = null, card = card, note = note)
                            }
                            is Msg.Result -> if (receivedManifest && msg.id == pending) {
                                pending = null
                                ui.value = ui.value.copy(busy = false, note = when (msg.status) {
                                    RefreshStatus.Updated -> "Updated by computer"
                                    RefreshStatus.Denied -> "Refresh not permitted"
                                    RefreshStatus.Busy -> "Computer is already refreshing"
                                    RefreshStatus.Failed -> "Refresh failed on computer"
                                })
                            }
                            Msg.Revoked -> { save(null); ui.value = PhoneState(note = "Pairing revoked by computer"); return }
                            else -> error("Unexpected sync message")
                        }
                    }
                }
            }
        } finally {
            session = null; pending = null; ws.cancel()
            disconnected()
        }
    }

    private fun disconnected() {
        val host = saved ?: return
        ui.value = PhoneState(paired = host.confirmed, card = host.card, note = if (host.confirmed) "Computer unavailable"
            else "Pairing not confirmed. Reconnecting, or scan a new invitation.")
    }

    fun refresh() { scope.launch {
        val card = ui.value.card ?: return@launch
        val ch = session ?: return@launch
        if (pending != null || card.access != Access.Refresh) return@launch
        val revision = saved?.revision ?: return@launch
        val id = (++sequence).toString()
        pending = id; ui.value = ui.value.copy(busy = true, note = "Refreshing on computer…")
        try { ch.send(Msg.Refresh(card.ref, id, revision)) } catch (_: Exception) { pending = null; ui.value = ui.value.copy(busy = false, note = "Refresh not confirmed"); return@launch }
        delay(30_000)
        if (pending == id) { pending = null; ui.value = ui.value.copy(busy = false, note = "Refresh not confirmed") }
    } }
    override fun close() { scope.cancel(); client.close() }
}
