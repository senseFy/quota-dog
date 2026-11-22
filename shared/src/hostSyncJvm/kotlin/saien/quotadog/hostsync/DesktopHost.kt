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
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.QuotaDogStore
import java.security.MessageDigest
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.minutes

data class PhoneGrant(val id: String, val grants: Map<AccountKey, GrantPermission>, val workspaceGrants: Map<AccountKey, GrantPermission>,
    val cards: List<SharedCard>, val online: Boolean, val legacy: Boolean, val busyRefs: Set<String>, val clockConflict: Boolean)
data class HostState(val enabled: Boolean = false, val online: Boolean = false, val qr: String? = null,
                     val digits: String? = null, val phones: List<PhoneGrant> = emptyList(), val saveFailed: Boolean = false)

const val MAX_PAIRED_PHONES = 32 // Matches the relay's per-route verifier capacity.

@Serializable private data class Trust(val pin: String, val sourceId: String? = null, val ref: String? = null,
    val access: Access = Access.Off, val revision: Long = 0, val loginTag: String? = null,
    val outgoing: LocalShares = LocalShares(), val incoming: ReceivedShares = ReceivedShares()) {
    fun migrate(): Trust {
        if (sourceId == null && revision == 0L) return this
        val legacy = if (sourceId != null && ref != null && loginTag != null && access != Access.Off)
            listOf(LocalGrant(AccountKey(ProviderId.DEVIN, sourceId), loginTag, ref, access)) else emptyList()
        return Trust(pin, outgoing = LocalShares(revision, legacy), incoming = incoming)
    }
}
private class Channel(val tls: SecureChannel, val grant: String?) {
    var syncing = false
    var multi = false
    val stream = ShareStream(tls)
    var lastManifest: Msg.Manifest? = null
}
private class Enrollment(val invite: Invite) {
    val deadline = TimeSource.Monotonic.markNow() + 5.minutes
    var channel: String? = null
    var digits: String? = null
    var ticketDigest: String? = null
    var approved = false
}

/** Each paired device has two independent lists. Only local credentials can back outgoing grants. */
class DesktopHost(private val client: HttpClient, private val identity: DeviceIdentity, private val vault: SyncVault,
                  private val store: QuotaDogStore, private val onStoragePause: () -> Unit = {}) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    // A null entry keeps only the relay verifier until the relay confirms its removal.
    private var trust = vault.read("phones")?.let { Json.decodeFromString<Map<String, Trust?>>(it) }.orEmpty().mapValues { it.value?.migrate() }
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
            val prior = t?.outgoing ?: continue
            val current = prior.valid(store)
            val latest = trust[id] ?: continue
            if (current !== prior && latest.outgoing === prior && !saveTrust(trust + (id to latest.copy(outgoing = current)))) return
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
    private fun publishPhones() { ui.value = ui.value.copy(phones = trust.mapNotNull { (id, t) -> t?.let {
        val ch = channels.values.find { it.grant == id && it.syncing }
        PhoneGrant(id, it.outgoing.permissions(false), it.outgoing.permissions(true), it.incoming.cards,
            ch?.multi == true && ch.stream.receivedManifest && ui.value.online, ch != null && !ch.multi,
            ch?.stream?.pending?.values?.toSet().orEmpty(), it.incoming.clockConflict)
    } }) }

    fun matchFor(id: String, snapshot: ProviderUsageSnapshot): String? = matchForPair(id, snapshot)

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
                    publishPhones()
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

    fun setAccess(id: String, edit: GrantEdit) { scope.launch {
        val source = edit.checkedSource(store)
        val latest = trust[id] ?: return@launch
        val next = latest.outgoing.change(id, edit, source)
        if (next === latest.outgoing || !saveTrust(trust + (id to latest.copy(outgoing = next)))) return@launch
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
        publishPhones()
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
                publishPhones()
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
                        Msg.Sync -> { check(!ch.syncing); validateSources(); ch.syncing = true; publish(ch); publishPhones() }
                        is Msg.Refresh -> {
                            check(ch.syncing)
                            if (!ch.multi && msg.ref == CAPABILITIES && msg.revision == -1L && msg.id == "1") {
                                ch.multi = true; ch.tls.send(Msg.Ready); publish(ch); publishPhones()
                            } else { ch.stream.checkSequence(msg); refresh(key, ch, msg) }
                        }
                        is Msg.Sources, is Msg.Observation -> {
                            check(ch.multi)
                            val t = trust.getValue(ch.grant) ?: error("Removed phone")
                            val next = if (msg is Msg.Sources) {
                                ch.stream.receivedManifest = true
                                t.incoming.manifest(msg)
                            } else {
                                check(ch.stream.receivedManifest); t.incoming.observation(msg as Msg.Observation)
                            }
                            if (next != t.incoming) saveTrust(trust + (ch.grant to t.copy(incoming = next))) else publishPhones()
                        }
                        is Msg.Result -> { check(ch.multi); ch.stream.pending.remove(msg.id); publishPhones() }
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
        if (ch.multi) { ch.stream.publish(t.outgoing, store); return }
        val grant = t.outgoing.entries.firstOrNull { it.key.providerId == ProviderId.DEVIN }
        val card = grant?.let {
            val account = store.state.value.accounts[it.key]
            SharedDevin(it.ref, it.access, account?.snapshot?.takeIf { s -> account.added && s.loginTag == it.login }?.reading())
        }
        val next = Msg.Manifest(t.outgoing.revision, card)
        if (ch.lastManifest == next) return
        ch.tls.send(next); ch.lastManifest = next
    }
    private fun refresh(key: String, ch: Channel, req: Msg.Refresh) {
        scope.launch {
            val status = refreshGranted(store, req) {
                trust[ch.grant]?.outgoing?.takeIf { ui.value.online && channels[key] === ch && (ch.multi || ch.lastManifest?.card?.ref == req.ref) }
            }
            if (ch.multi) validateSources()
            if (ui.value.online && channels[key] === ch) { publish(ch); ch.tls.send(Msg.Result(req.id, status)) }
        }
    }

    fun refreshPhone(id: String, ref: String) { scope.launch {
        val ch = channels.values.find { it.grant == id && it.multi && it.stream.receivedManifest } ?: return@launch
        val incoming = trust[id]?.incoming ?: return@launch
        val card = incoming.cards.find { it.ref == ref } ?: return@launch
        val request = ch.stream.request(card, incoming.revision) ?: return@launch
        publishPhones(); delay(30_000)
        if (ch.stream.pending.remove(request) != null) publishPhones()
    } }
    override fun close() { runBlocking { scope.coroutineContext.job.cancelAndJoin() }; client.close() }
}
