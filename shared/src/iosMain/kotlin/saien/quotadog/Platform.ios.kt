package saien.quotadog

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

actual class PlatformTokenStore actual constructor() : TokenStore {
    private val delegate = SettingsTokenStore(
        NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
    )

    override suspend fun list(): List<StoredToken> = delegate.list()

    override suspend fun load(accountKey: AccountKey): OAuthTokenBundle? = delegate.load(accountKey)

    override suspend fun save(providerId: ProviderId, token: OAuthTokenBundle): AccountKey {
        return delegate.save(providerId, token)
    }

    override suspend fun save(accountKey: AccountKey, token: OAuthTokenBundle) {
        delegate.save(accountKey, token)
    }

    override suspend fun delete(accountKey: AccountKey) {
        delegate.delete(accountKey)
    }

    override suspend fun exportTokensForSync(): List<CloudSyncAccountRecord> = delegate.exportTokensForSync()

    override suspend fun importTokenForSync(
        accountKey: AccountKey,
        token: OAuthTokenBundle,
        updatedAtEpochMillis: Long
    ) {
        delegate.importTokenForSync(accountKey, token, updatedAtEpochMillis)
    }

    override suspend fun deleteForSync(accountKey: AccountKey) {
        delegate.deleteForSync(accountKey)
    }
}

actual class PlatformBrowserLauncher actual constructor() : BrowserLauncher {
    override fun open(url: String): Boolean {
        return IosBrowserSession.openAuthorizationUrl(url)
    }
}

interface IosNativeOAuthHandler {
    fun openAuthorizationUrl(
        url: String,
        port: Int,
        path: String,
        completion: (String?) -> Unit
    ): Boolean

    fun cancelAuthorization()
}

object IosNativeOAuthBridge {
    private var handler: IosNativeOAuthHandler? = null

    fun register(handler: IosNativeOAuthHandler?) {
        this.handler = handler
    }

    internal fun openAuthorizationUrl(
        url: String,
        port: Int,
        path: String,
        completion: (String?) -> Unit
    ): Boolean {
        val nativeHandler = handler
        if (nativeHandler == null) {
            platformDebugLog("native OAuth handler is not registered")
            return false
        }
        return nativeHandler.openAuthorizationUrl(url, port, path, completion)
    }

    internal fun cancelAuthorization() {
        handler?.cancelAuthorization()
    }
}

private object IosBrowserSession {
    private var expectedPort: Int? = null
    private var expectedPath: String? = null
    private var result: CompletableDeferred<String?>? = null

    fun prepare(port: Int, path: String): CompletableDeferred<String?> {
        result?.complete(null)
        expectedPort = port
        expectedPath = path
        return CompletableDeferred<String?>().also { result = it }
    }

    fun clear(deferred: CompletableDeferred<String?>) {
        if (result === deferred) {
            result = null
            expectedPort = null
            expectedPath = null
        }
    }

    fun openAuthorizationUrl(url: String): Boolean {
        val port = expectedPort ?: return false
        val path = expectedPath ?: return false
        return IosNativeOAuthBridge.openAuthorizationUrl(url, port, path) { callbackUrl ->
            complete(callbackUrl)
        }
    }

    private fun complete(url: String?) {
        val port = expectedPort
        val path = expectedPath
        val raw = url
        if (port == null || path == null || raw == null) {
            result?.complete(null)
            return
        }
        val matches = raw.startsWith("http://localhost:$port$path") ||
            raw.startsWith("http://127.0.0.1:$port$path")
        if (matches) {
            result?.complete(raw)
        } else {
            platformDebugLog("native auth received unmatched callback")
            result?.complete(null)
        }
    }
}

actual class PlatformOAuthCallbackServer actual constructor() : OAuthCallbackServer {
    override suspend fun waitForCallback(providerId: ProviderId, timeoutMillis: Long): String? = withContext(Dispatchers.Default) {
        val config = when (providerId) {
            ProviderId.CODEX -> CallbackConfig(1455, "/auth/callback")
            ProviderId.CLAUDE_CODE -> CallbackConfig(54545, "/callback")
        }
        waitForCallback(config.port, config.path, timeoutMillis)
    }

    override suspend fun waitForCallback(port: Int, path: String, timeoutMillis: Long): String? = withContext(Dispatchers.Default) {
        val config = CallbackConfig(port, path)
        val deferred = IosBrowserSession.prepare(config.port, config.path)
        var callbackUrl: String? = null
        try {
            callbackUrl = withTimeoutOrNull(timeoutMillis) { deferred.await() }
            if (callbackUrl == null) {
                platformDebugLog("native auth callback wait ended without callback")
                IosNativeOAuthBridge.cancelAuthorization()
            }
            return@withContext callbackUrl
        } finally {
            IosBrowserSession.clear(deferred)
        }
    }

    private data class CallbackConfig(val port: Int, val path: String)
}

actual fun createPreferenceSettings(): Settings =
    NSUserDefaultsSettings(NSUserDefaults(suiteName = "saien.quotadog.prefs") ?: NSUserDefaults.standardUserDefaults)

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val bytes = ByteArray(size)
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
    }
    check(status == 0) { "SecRandomCopyBytes failed with status $status" }
    return bytes
}

actual fun sha256(input: ByteArray): ByteArray {
    return Sha256.digest(input)
}

actual fun base64UrlNoPadding(input: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val out = StringBuilder((input.size * 4 + 2) / 3)
    var index = 0
    while (index < input.size) {
        val b0 = input[index++].toInt() and 0xff
        val b1 = if (index < input.size) input[index++].toInt() and 0xff else -1
        val b2 = if (index < input.size) input[index++].toInt() and 0xff else -1

        out.append(alphabet[b0 ushr 2])
        out.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
        if (b1 >= 0) {
            out.append(alphabet[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)])
        }
        if (b2 >= 0) {
            out.append(alphabet[b2 and 0x3f])
        }
    }
    return out.toString()
}

actual fun platformDebugLog(message: String) {
    // Intentionally disabled by default so account identifiers and provider errors do not
    // end up in device logs in public builds.
}

private object Sha256 {
    private val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
    )

    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = -0x4498517b
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6
        var h4 = 0x510e527f
        var h5 = -0x64fa9774
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val bitLength = message.size.toLong() * 8
        val paddedLength = (((message.size + 9 + 63) / 64) * 64)
        val padded = ByteArray(paddedLength)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }

        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((padded[j].toInt() and 0xff) shl 24) or
                    ((padded[j + 1].toInt() and 0xff) shl 16) or
                    ((padded[j + 2].toInt() and 0xff) shl 8) or
                    (padded[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = rotateRight(w[i - 15], 7) xor rotateRight(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotateRight(w[i - 2], 17) xor rotateRight(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (i in 0 until 64) {
                val s1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + k[i] + w[i]
                val s0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { index, value ->
            val j = index * 4
            out[j] = (value ushr 24).toByte()
            out[j + 1] = (value ushr 16).toByte()
            out[j + 2] = (value ushr 8).toByte()
            out[j + 3] = value.toByte()
        }
        return out
    }

    private fun rotateRight(value: Int, bits: Int): Int {
        return (value ushr bits) or (value shl (32 - bits))
    }
}
