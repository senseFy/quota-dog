package saien.quotadog.hostsync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.cancel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature

// Each build has one origin. A QR or stored pairing can never redirect credentials.
val hostSyncStaging = hostSyncLabel == "Staging"
internal val random = SecureRandom()
internal fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).toHexString()
internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
internal fun String.isHex(bytes: Int) = length == bytes * 2 && all { it in '0'..'9' || it in 'a'..'f' }

interface SyncVault {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class DeviceIdentity(private val keys: KeyPair) {
    val spki: ByteArray = keys.public.encoded
    val pin: String = digest(spki)
    // Called by rustls through JNI. Android's key is deliberately not exportable.
    fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(message)
        sign()
    }
}

internal class Invite(val route: String, val pin: String, val secret: String) {
    fun qr() = "QD1:$route:$pin:$secret"

    companion object {
        fun parse(text: String): Invite? {
            if (text.length != 166) return null
            val parts = text.split(':')
            return if (parts.size == 4 && parts[0] == "QD1" && parts[1].isHex(16) &&
                parts[2].isHex(32) && parts[3].isHex(32)) Invite(parts[1], parts[2], parts[3]) else null
        }
    }
}

@Serializable internal class Route(val route: String, val token: String)

/** Only the host calls this, with its private test Keychain. Never export the bearer itself. */
fun stagingRelayBindings(vault: SyncVault): String {
    check(hostSyncStaging)
    val r = vault.read("route")?.let { Json.decodeFromString<Route>(it) }
        ?: Route(randomHex(16), randomHex(32)).also { vault.write("route", Json.encodeToString(it)) }
    return buildJsonObject {
        put("STAGING_ROUTE", r.route)
        put("STAGING_HOST_VERIFIER", digest(r.token.encodeToByteArray()))
    }.toString()
}

internal suspend fun HttpClient.newRoute(): Route {
    check(!hostSyncStaging) { "Provision the test host before enabling it" }
    val res = post("$RELAY_ORIGIN/v1/routes")
    check(res.status.value in 200..299)
    return Json.decodeFromString<Route>(res.bodyAsText()).also { require(it.route.isHex(16) && it.token.isHex(32)) }
}
internal suspend fun HttpClient.connect(route: String, role: String, token: String? = null) = webSocketSession {
    require(route.isHex(16) && role in listOf("host", "device", "enroll"))
    url("${RELAY_ORIGIN.replaceFirst("http", "ws")}/v1/$route/$role")
    if (token != null) header("Authorization", "Bearer $token")
}

internal fun WebSocketSession.emit(frame: Frame) {
    // A dropped TLS record or control invalidates this stream, not the long-lived store observer.
    if (!outgoing.trySend(frame).isSuccess) cancel("Relay send unavailable")
}

// Development diagnostics are categories only: never print an exception message, URL, QR or frame.
internal fun localFailure(stage: String, e: Exception) {
    if (hostSyncLabel == "QuotaDog") return
    val frame = e.stackTrace.firstOrNull()
    val status = Regex("\\b(?:400|401|403|404|426|429|500|503)\\b").find(e.message.orEmpty())?.value ?: "-"
    System.err.println("QuotaDog sync: $stage / ${e.javaClass.simpleName} / ${frame?.className}.${frame?.methodName}:${frame?.lineNumber} / $status")
}

enum class Access { Off, View, Refresh }
@Serializable data class QuotaPeriod(val used: Double, val resetsAt: Long?)
@Serializable data class DevinReading(
    val collectedAt: Long, val daily: QuotaPeriod?, val weekly: QuotaPeriod?, val extraUsd: Double?,
)

// Never serialize ProviderUsageSnapshot: even its success message can include a credential path.
internal fun ProviderUsageSnapshot.reading(): DevinReading? {
    if (providerId != ProviderId.DEVIN || stale) return null
    fun period(id: String) = windows.singleOrNull { it.id == id && it.usedRatio in 0.0..1.0 }
        ?.let { QuotaPeriod(it.usedRatio, it.resetsAt?.toEpochMilliseconds()) }
    val daily = period("devin-daily")
    val weekly = period("devin-weekly")
    if (daily == null && weekly == null) return null
    return DevinReading(collectedAt.toEpochMilliseconds(), daily, weekly, devinExtraBalanceUsd?.takeIf { it.isFinite() && it >= 0 })
}

@Serializable data class SharedDevin(val ref: String, val access: Access, val reading: DevinReading?)
@Serializable internal sealed interface Msg {
    @Serializable @SerialName("enroll") class Enroll(val secret: String) : Msg
    @Serializable @SerialName("compare") class Compare(val digits: String) : Msg
    @Serializable @SerialName("grant") class Grant(val token: String) : Msg
    @Serializable @SerialName("stored") data object Stored : Msg
    @Serializable @SerialName("paired") data object Paired : Msg
    @Serializable @SerialName("sync-v1") data object Sync : Msg
    // One complete, atomic manifest for this direction; null removes its sole source in this slice.
    @Serializable @SerialName("manifest-v1") data class Manifest(val revision: Long, val card: SharedDevin?) : Msg
    @Serializable @SerialName("refresh") class Refresh(val ref: String, val id: String, val revision: Long) : Msg
    @Serializable @SerialName("result") class Result(val id: String, val status: RefreshStatus) : Msg
    @Serializable @SerialName("revoked") data object Revoked : Msg
}
@Serializable internal enum class RefreshStatus { Updated, Denied, Failed, Busy }

internal object NativeHostTls {
    external fun open(server: Boolean, spki: ByteArray, signer: Any, peerPin: ByteArray?): Long
    external fun receive(id: Long, ciphertext: ByteArray)
    external fun send(id: Long, plaintext: ByteArray)
    external fun outbound(id: Long): ByteArray
    external fun plaintext(id: Long): ByteArray
    external fun peerPin(id: Long): ByteArray?
    external fun close(id: Long)
}

/** TLS is a byte stream, independent of both record and WebSocket frame boundaries. */
internal class SecureChannel(server: Boolean, identity: DeviceIdentity, pin: String?, private val emit: (ByteArray) -> Unit) : AutoCloseable {
    private val handle = NativeHostTls.open(server, identity.spki, identity, pin?.hexToByteArray())
    private var buffered = byteArrayOf()
    val peer get() = NativeHostTls.peerPin(handle)?.toHexString()
    fun flush() { NativeHostTls.outbound(handle).takeIf { it.isNotEmpty() }?.let(emit) }
    fun receive(bytes: ByteArray): List<Msg> {
        require(bytes.size in 1..65_536)
        NativeHostTls.receive(handle, bytes)
        flush()
        if (peer == null) return emptyList()
        buffered += NativeHostTls.plaintext(handle)
        return buildList {
            var pos = 0
            while (buffered.size - pos >= 4) {
                val n = ByteBuffer.wrap(buffered, pos, 4).int
                require(n in 1..16_384)
                if (buffered.size - pos - 4 < n) break
                add(Json.decodeFromString<Msg>(buffered.decodeToString(pos + 4, pos + 4 + n, throwOnInvalidSequence = true)))
                pos += 4 + n
            }
            buffered = buffered.copyOfRange(pos, buffered.size)
        }
    }
    fun send(msg: Msg) {
        check(peer != null)
        val data = Json.encodeToString<Msg>(msg).encodeToByteArray()
        require(data.size in 1..16_384)
        NativeHostTls.send(handle, ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array())
        flush()
    }
    override fun close() = NativeHostTls.close(handle)
}
