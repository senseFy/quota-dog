@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package saien.quotadog.hostsync

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.NSObject

interface IosSyncNetworkListener {
    fun networkChanged(handle: Long)
}

interface IosPhonePlatform {
    fun identitySpki(): NSData?
    fun signature(message: NSData): NSData?
    /** Empty only when no item exists. A locked or unreadable Keychain throws. */
    @Throws(Exception::class)
    fun readPairing(): String
    fun writePairing(value: String?): Boolean
    @Throws(Exception::class)
    fun readGrantLimits(): String
    fun writeGrantLimits(value: String): Boolean
    fun selectQr(image: Boolean, completion: (String?) -> Unit)
    fun watchNetwork(listener: IosSyncNetworkListener)
    fun stopWatchingNetwork()
}

private fun NSData.bytes(max: Int): ByteArray {
    require(length in 1uL..max.toULong())
    return checkNotNull(bytes).readBytes(length.toInt())
}

// Ktor Darwin drops the HTTP status of a failed upgrade. Foundation exposes it on the
// completed task, not on the NSError. Preserve it without parsing messages or private keys.
private const val relayHttpError = "saien.quotadog.host-sync.http"
private class RelaySocketDelegate(val ktor: KtorNSURLSessionDelegate) : NSObject(), NSURLSessionWebSocketDelegateProtocol {
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val status = (task.response as? NSHTTPURLResponse)?.statusCode
        val error = if (status != null && status >= 400) NSError.errorWithDomain(relayHttpError, status, null) else didCompleteWithError
        ktor.URLSession(session, task, didCompleteWithError = error)
    }
    override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didOpenWithProtocol: String?) =
        ktor.URLSession(session, webSocketTask, didOpenWithProtocol = didOpenWithProtocol)
    override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didCloseWithCode: NSURLSessionWebSocketCloseCode, reason: NSData?) =
        ktor.URLSession(session, webSocketTask, didCloseWithCode, reason)
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, willPerformHTTPRedirection: NSHTTPURLResponse,
                            newRequest: NSURLRequest, completionHandler: (NSURLRequest?) -> Unit) = completionHandler(null)
}

fun IosPhonePlatform.openPhoneSync(): PhoneSync? {
    val spki = identitySpki()?.bytes(91) ?: return null
    val identity = DeviceIdentity(spki) { message ->
        val data = message.usePinned { NSData.create(bytes = it.addressOf(0), length = message.size.toULong()) }
        checkNotNull(signature(data)).bytes(72)
    }
    val vault = object : SyncVault {
        override fun read(key: String): String? = when (key) {
            "host" -> readPairing()
            PHONE_GRANT_LIMITS -> readGrantLimits()
            else -> error("Unknown pairing record")
        }.ifEmpty { null }
        override fun write(key: String, value: String?) = check(when (key) {
            "host" -> writePairing(value)
            PHONE_GRANT_LIMITS -> writeGrantLimits(checkNotNull(value))
            else -> false
        })
    }
    val client = HttpClient(Darwin) {
        followRedirects = false
        install(WebSockets) { maxFrameSize = 65_536 }
        engine {
            val delegate = RelaySocketDelegate(KtorNSURLSessionDelegate())
            val config = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
                URLCache = null; HTTPCookieStorage = null; URLCredentialStorage = null
            }
            usePreconfiguredSession(NSURLSession.sessionWithConfiguration(config, delegate, null), delegate.ktor)
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                val error = (cause as? DarwinHttpRequestException)?.origin
                if (error?.domain == relayHttpError && error.code == 403L) throw RelayTicketRejected()
            }
        }
    }
    return try { PhoneSync(client, identity, vault) } catch (e: Exception) { client.close(); throw e }
}
