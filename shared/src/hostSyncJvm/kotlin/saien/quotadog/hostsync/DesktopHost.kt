package saien.quotadog.hostsync

import io.ktor.client.HttpClient
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import saien.quotadog.AccountKey
import saien.quotadog.AuthState
import saien.quotadog.ProviderId
import saien.quotadog.QuotaDogStore
import java.security.MessageDigest
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.minutes

data class PhoneGrant(val id: String, val access: Access, val sourceId: String?)
data class HostState(val enabled: Boolean = false, val online: Boolean = false, val qr: String? = null,
                     val digits: String? = null, val phones: List<PhoneGrant> = emptyList(), val saveFailed: Boolean = false)

const val MAX_PAIRED_PHONES = 32 // Matches the relay's per-route verifier capacity.

@Serializable private data class Trust(val pin: String, val sourceId: String? = null, val ref: String? = null,
                                      val access: Access = Access.Off, val revision: Long = 0, val loginTag: String? = null) {
    val source: AccountKey? get() = sourceId?.let { AccountKey(ProviderId.DEVIN, it) }
}
private class Channel(val tls: SecureChannel, val grant: String?) {
    var syncing = false
    var lastManifest: Msg.Manifest? = null
    var lastRequest = 0L
}
private class Enrollment(val invite: Invite) {
    val deadline = TimeSource.Monotonic.markNow() + 5.minutes
    var channel: String? = null
    var digits: String? = null
    var ticketDigest: String? = null
    var approved = false
}

/** Each phone receives at most one explicitly selected local Devin login. */
class DesktopHost(private val client: HttpClient, private val identity: DeviceIdentity, private val vault: SyncVault,
                  private val store: QuotaDogStore, private val defaultSource: AccountKey? = null,
                  private val onStoragePause: () -> Unit = {}) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    // A null entry keeps only the relay verifier until the relay confirms its removal.
    private var trust = vault.read("phones")?.let { Json.decodeFromString<Map<String, Trust?>>(it) }.orEmpty()
    private var retryWrite: (() -> Unit)? = null
    private val channels = mutableMapOf<String, Channel>()
    private var route = vault.read("route")?.let { Json.decodeFromString<Route>(it) }
    private var socket: WebSocketSession? = null
    private var worker: Job? = null
    private var enrollment: Enrollment? = null
    private val ui = MutableStateFlow(HostState())
    val state = ui.asStateFlow()

    init {
        publishPhones()
        scope.launch {
            store.state.collect {
                validateSources()
                channels.values.filter { it.syncing }.forEach { publish(it) }
            }
        }
    }

    private suspend fun validateSources() {
        for ((id, t) in trust) {
            val source = t?.source ?: continue
            // The imported login, not whether detectAll has produced a reading yet, owns the grant.
            if (store.currentDevinLogin(source) != t.loginTag && trust[id] === t) {
                if (!saveTrust(trust + (id to Trust(t.pin, revision = t.revision + 1)))) return
            }
        }
    }

    private fun writeOrPause(write: () -> Unit): Boolean {
        if (retryWrite != null) return false
        return try { write(); true } catch (_: Exception) {
            retryWrite = write
            worker?.cancel(); socket?.cancel()
            onStoragePause() // Do not automatically reopen the old grant on the next app launch.
            ui.value = ui.value.copy(enabled = false, online = false, qr = null, digits = null, saveFailed = true)
            false
        }
    }
    private fun saveTrust(next: Map<String, Trust?>) = writeOrPause {
        vault.write("phones", Json.encodeToString(next))
        trust = next; publishPhones()
    }
    fun retrySave() { scope.launch {
        val write = retryWrite ?: return@launch
        retryWrite = null
        if (writeOrPause(write)) ui.value = ui.value.copy(saveFailed = false)
    } }
    private fun publishPhones() { ui.value = ui.value.copy(phones = trust.mapNotNull { (id, t) -> t?.let { PhoneGrant(id, it.access, it.sourceId) } }) }

    fun enable() { scope.launch {
        if (ui.value.enabled || retryWrite != null) return@launch
        worker?.join()
        if (ui.value.enabled || retryWrite != null) return@launch
        ui.value = ui.value.copy(enabled = true)
        worker = scope.launch {
            var wait = 1_000L
            while (isActive) {
                try {
                    validateSources()
                    if (retryWrite != null) break
                    val r = route ?: withTimeout(10_000) { client.newRoute() }
                    if (route == null && !writeOrPause { vault.write("route", Json.encodeToString(r)); route = r }) break
                    val ws = withTimeout(10_000) { client.connect(r.route, "host", r.token) }
                    ws.timeoutMillis = 15_000
                    ws.pingIntervalMillis = 60_000
                    socket = ws
                    // The Keychain is authoritative. Both operations are idempotent, including
                    // after a crash between our durable write and the relay's acknowledgement.
                    trust.filterValues { it == null }.keys.forEach { command("deny $it") }
                    trust.filterValues { it != null }.keys.forEach { command("allow $it") }
                    ui.value = ui.value.copy(online = trust.values.none { it == null })
                    wait = 1_000
                    for (frame in ws.incoming) when (frame) {
                        is Frame.Text -> control(frame.readText())
                        is Frame.Binary -> binary(frame.data)
                        else -> Unit
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive(); localFailure("host connection", e)
                } finally {
                    channels.values.forEach { it.tls.close() }; channels.clear()
                    enrollment = null
                    socket?.cancel(); socket = null
                    ui.value = ui.value.copy(online = false, qr = null, digits = null)
                }
                delay(wait + random.nextInt(400)); wait = (wait * 2).coerceAtMost(60_000)
            }
        }
    } }

    fun pause() { scope.launch {
        ui.value = ui.value.copy(enabled = false, online = false, qr = null, digits = null)
        val old = worker; old?.cancelAndJoin()
        if (worker === old) worker = null
    } }
    private fun command(text: String) { socket?.emit(Frame.Text(text)) }

    fun invite() { scope.launch {
        val r = route ?: return@launch
        if (!ui.value.online || trust.size >= MAX_PAIRED_PHONES) return@launch
        endInvite()
        val pending = Enrollment(Invite(r.route, identity.pin, randomHex(32)))
        enrollment = pending
        command("invite")
        delay(300_000)
        if (enrollment === pending) endInvite()
    } }
    fun cancelInvite() { scope.launch { endInvite() } }
    private fun endInvite() {
        val old = enrollment ?: return
        enrollment = null
        command("seal")
        old.channel?.let(::drop)
        ui.value = ui.value.copy(qr = null, digits = null)
    }

    fun approve(digits: String) { scope.launch {
        val pending = enrollment ?: return@launch
        if (pending.deadline.hasPassedNow()) { endInvite(); return@launch }
        if (pending.approved || pending.digits != digits) return@launch
        val ch = channels[pending.channel] ?: return@launch
        val ticket = randomHex(32)
        pending.ticketDigest = digest(ticket.encodeToByteArray())
        pending.approved = true
        ui.value = ui.value.copy(digits = null)
        ch.tls.send(Msg.Grant(ticket))
    } }

    fun setAccess(id: String, access: Access, selected: AccountKey? = defaultSource) { scope.launch {
        val old = trust[id] ?: return@launch
        val source = selected.takeUnless { access == Access.Off }
        val tag = if (access == Access.Off) null else {
            if (source?.providerId != ProviderId.DEVIN || source.isPending) return@launch
            store.state.value.accounts[source]?.takeIf { it.added }?.snapshot?.devinLoginTag ?: return@launch
        }
        if ((source != null && store.currentDevinLogin(source) != tag) || trust[id] !== old) return@launch
        val sameLogin = old.sourceId == source?.accountId && old.loginTag == tag
        if (old.access == access && sameLogin) return@launch
        val next = old.copy(access = access, sourceId = source?.accountId, loginTag = tag,
            ref = if (tag == null) null else old.ref?.takeIf { sameLogin } ?: randomHex(16), revision = old.revision + 1)
        if (!saveTrust(trust + (id to next))) return@launch
        channels.values.filter { it.grant == id && it.syncing }.forEach(::publish)
    } }
    fun revoke(id: String) { scope.launch {
        if (trust[id] == null) return@launch
        if (!saveTrust(trust + (id to null))) return@launch
        channels.filterValues { it.grant == id }.toMap().forEach { (key, ch) ->
            if (ch.syncing) ch.tls.send(Msg.Revoked)
            drop(key)
        }
        command("deny $id")
    } }

    private fun drop(key: String) {
        channels.remove(key)?.tls?.close()
        command("close $key")
    }
    private fun control(text: String) {
        val p = text.split(' ')
        when (p.first()) {
            "inviting" -> enrollment?.let { if (it.channel == null) ui.value = ui.value.copy(qr = it.invite.qr()) }
            "join" -> {
                if (p.size != 3 || !p[1].isHex(8)) return
                val key = p[1]; val grant = p[2].takeUnless { it == "enroll" }
                val pin = trust[grant]?.pin
                if (!ui.value.online || key in channels || (grant != null && pin == null) || (grant == null &&
                        (enrollment?.deadline?.hasPassedNow() != false || enrollment?.channel != null))) {
                    drop(key); return
                }
                val tls = SecureChannel(true, identity, pin) { socket!!.emit(Frame.Binary(true, key.encodeToByteArray() + it)) }
                val ch = Channel(tls, grant)
                channels[key] = ch
                scope.launch { delay(10_000); if (channels[key] === ch && !ch.syncing && enrollment?.channel != key) drop(key) }
            }
            "left" -> if (p.size == 2) {
                channels.remove(p[1])?.tls?.close()
                if (enrollment?.channel == p[1]) endInvite()
            }
            "denied" -> if (p.size == 2 && p[1] in trust && trust[p[1]] == null) {
                if (!saveTrust(trust - p[1])) return
                if (trust.values.none { it == null }) ui.value = ui.value.copy(online = true)
            }
            "allowed" -> enrollment?.let { e ->
                if (p.size == 2 && e.approved && e.ticketDigest == p[1] && trust[p[1]] != null) {
                    channels[e.channel]?.tls?.send(Msg.Paired); endInvite()
                }
            }
        }
    }

    private suspend fun binary(bytes: ByteArray) {
        if (!ui.value.online || bytes.size <= 16) return
        val key = bytes.decodeToString(0, 16)
        val ch = channels[key] ?: return
        try {
            for (msg in ch.tls.receive(bytes.copyOfRange(16, bytes.size))) {
                if (ch.grant == null) enroll(key, ch, msg) else {
                    check(trust[ch.grant]?.pin == ch.tls.peer)
                    when (msg) {
                        Msg.Sync -> { check(!ch.syncing); validateSources(); ch.syncing = true; publish(ch) }
                        is Msg.Refresh -> { check(ch.syncing); refresh(key, ch, msg) }
                        else -> error("Unexpected sync message")
                    }
                }
            }
        } catch (e: Exception) { localFailure("host channel", e); drop(key); if (enrollment?.channel == key) endInvite() }
    }

    private fun enroll(key: String, ch: Channel, msg: Msg) {
        val e = checkNotNull(enrollment)
        check(!e.deadline.hasPassedNow())
        when (msg) {
            is Msg.Enroll -> {
                check(e.channel == null && msg.secret.isHex(32) && MessageDigest.isEqual(msg.secret.hexToByteArray(), e.invite.secret.hexToByteArray()))
                e.channel = key
                command("seal")
                val digits = random.nextInt(1_000_000).toString().padStart(6, '0')
                e.digits = digits
                ch.tls.send(Msg.Compare(digits)); ui.value = ui.value.copy(qr = null, digits = digits)
            }
            Msg.Stored -> {
                check(e.approved && e.channel == key)
                val id = checkNotNull(e.ticketDigest)
                check(id !in trust && trust.size < MAX_PAIRED_PHONES)
                if (!saveTrust(trust + (id to Trust(checkNotNull(ch.tls.peer))))) return
                command("allow $id")
            }
            else -> error("No account operations during enrollment")
        }
    }
    private fun publish(ch: Channel) {
        if (!ui.value.online) return
        val t = trust[ch.grant] ?: return
        val account = store.state.value.accounts[t.source]
        val card = if (t.access != Access.Off && t.ref != null && t.loginTag != null) {
            val reading = account?.snapshot?.takeIf { account.added && it.devinLoginTag == t.loginTag }?.reading()
            SharedDevin(t.ref, t.access, reading)
        } else null
        val next = Msg.Manifest(t.revision, card)
        if (ch.lastManifest == next) return
        ch.tls.send(next); ch.lastManifest = next
    }
    private fun refresh(key: String, ch: Channel, req: Msg.Refresh) {
        // Request numbers are monotonic within this fresh TLS session; no cross-session retry queue.
        val seq = req.id.toLongOrNull() ?: error("Invalid request")
        check(seq > ch.lastRequest); ch.lastRequest = seq
        val grant = trust[ch.grant]
        val source = grant?.source
        fun allowed() = ui.value.online && grant != null && grant.access == Access.Refresh && grant.ref == req.ref && grant.revision == req.revision &&
            grant.loginTag != null && trust[ch.grant] == grant
        val account = store.state.value.accounts[source]
        if (source == null || grant.loginTag == null || !allowed()) { ch.tls.send(Msg.Result(req.id, RefreshStatus.Denied)); return }
        if (account?.busy == true) { ch.tls.send(Msg.Result(req.id, RefreshStatus.Busy)); return }
        val previous = account?.snapshot?.collectedAt
        scope.launch {
            val current = store.currentDevinLogin(source)
            if (channels[key] !== ch) return@launch
            if (!allowed() || current != grant.loginTag) { ch.tls.send(Msg.Result(req.id, RefreshStatus.Denied)); return@launch }
            store.refresh(source, grant.loginTag)
            val after = store.currentDevinLogin(source)
            if (channels[key] !== ch) return@launch
            if (!allowed() || after != grant.loginTag) { ch.tls.send(Msg.Result(req.id, RefreshStatus.Denied)); return@launch }
            val fresh = store.state.value.accounts[source]
            val ok = fresh?.authState == AuthState.LoggedIn && fresh.snapshot?.devinLoginTag == grant.loginTag && fresh.snapshot.collectedAt != previous
            publish(ch); ch.tls.send(Msg.Result(req.id, if (ok) RefreshStatus.Updated else RefreshStatus.Failed))
        }
    }
    override fun close() { runBlocking { scope.coroutineContext.job.cancelAndJoin() }; client.close() }
}
