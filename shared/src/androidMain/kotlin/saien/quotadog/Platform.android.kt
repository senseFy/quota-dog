package saien.quotadog

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

private object AndroidPlatformContext {
    var applicationContext: Context? = null
}

fun initializeAndroidPlatform(context: Context) {
    AndroidPlatformContext.applicationContext = context.applicationContext
}

actual class PlatformTokenStore actual constructor() : TokenStore {
    private val delegate: SettingsTokenStore by lazy {
        val context = AndroidPlatformContext.applicationContext
            ?: error("initializeAndroidPlatform(context) must be called before using PlatformTokenStore")
        SettingsTokenStore(
            SharedPreferencesSettings(
                context.getSharedPreferences("quotadog", Context.MODE_PRIVATE)
            )
        )
    }

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
        val context = AndroidPlatformContext.applicationContext ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

actual class PlatformOAuthCallbackServer actual constructor() : OAuthCallbackServer {
    override suspend fun waitForCallback(providerId: ProviderId, timeoutMillis: Long): String? = withContext(Dispatchers.IO) {
        val config = when (providerId) {
            ProviderId.CODEX -> CallbackConfig(1455, "/auth/callback")
            ProviderId.CLAUDE_CODE -> CallbackConfig(54545, "/callback")
        }
        waitForCallback(config.port, config.path, timeoutMillis)
    }

    override suspend fun waitForCallback(port: Int, path: String, timeoutMillis: Long): String? = withContext(Dispatchers.IO) {
        val config = CallbackConfig(port, path)
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMillis
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.soTimeout = 500
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port))
            while (Clock.System.now().toEpochMilliseconds() < deadline) {
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                socket.use {
                    val requestLine = BufferedReader(InputStreamReader(it.getInputStream())).readLine().orEmpty()
                    val path = requestLine.substringAfter("GET ", "").substringBefore(" HTTP/")
                    val matched = path.startsWith(config.path)
                    val body = if (matched) successHtml() else "Not found"
                    val status = if (matched) "200 OK" else "404 Not Found"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(
                        buildString {
                            append("HTTP/1.1 $status\r\n")
                            append("Content-Type: text/html; charset=utf-8\r\n")
                            append("Content-Length: ${bytes.size}\r\n")
                            append("Connection: close\r\n")
                            append("\r\n")
                        }.toByteArray(Charsets.UTF_8)
                    )
                    it.getOutputStream().write(bytes)
                    it.getOutputStream().flush()
                    if (matched) return@withContext "http://localhost:${config.port}$path"
                }
            }
            null
        }
    }

    private data class CallbackConfig(val port: Int, val path: String)

    private fun successHtml(): String = """
        <html>
          <body style="font-family: sans-serif; padding: 24px;">
            <h2>QuotaDog</h2>
            <p>Sign-in complete. You can return to the app.</p>
          </body>
        </html>
    """.trimIndent()
}

actual fun createPreferenceSettings(): Settings {
    val context = AndroidPlatformContext.applicationContext
        ?: error("initializeAndroidPlatform(context) must be called before reading preferences")
    return SharedPreferencesSettings(
        context.getSharedPreferences("quotadog-prefs", Context.MODE_PRIVATE)
    )
}

actual fun createHttpClient(): HttpClient = HttpClient(Android) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also {
    SecureRandom().nextBytes(it)
}

actual fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

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
    // end up in system logs in public builds.
}
