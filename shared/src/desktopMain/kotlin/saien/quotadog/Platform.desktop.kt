package saien.quotadog

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.prefs.Preferences

actual class PlatformTokenStore actual constructor() : TokenStore {
    private val delegate = SettingsTokenStore(
        PreferencesSettings(Preferences.userRoot().node("saien/quotadog"))
    )
    private val legacyJson = Json { ignoreUnknownKeys = true }
    private val legacyDir = File(System.getProperty("user.home"), ".quotadog")

    override suspend fun list(): List<StoredToken> {
        val stored = delegate.list().toMutableList()
        ProviderId.entries.forEach { providerId ->
            val legacy = loadLegacy(providerId) ?: return@forEach
            val accountKey = delegate.save(providerId, legacy)
            legacyTokenFile(providerId).delete()
            if (stored.none { it.accountKey == accountKey }) {
                stored += StoredToken(accountKey, legacy)
            }
        }
        return stored
    }

    override suspend fun load(accountKey: AccountKey): OAuthTokenBundle? = delegate.load(accountKey)

    override suspend fun save(providerId: ProviderId, token: OAuthTokenBundle): AccountKey {
        return delegate.save(providerId, token)
    }

    override suspend fun save(accountKey: AccountKey, token: OAuthTokenBundle) {
        delegate.save(accountKey, token)
    }

    override suspend fun delete(accountKey: AccountKey) {
        delegate.delete(accountKey)
        legacyTokenFile(accountKey.providerId).delete()
    }

    private fun loadLegacy(providerId: ProviderId): OAuthTokenBundle? {
        val file = legacyTokenFile(providerId)
        if (!file.exists()) return null
        return runCatching {
            legacyJson.decodeFromString(OAuthTokenBundle.serializer(), file.readText())
        }.getOrNull()
    }

    private fun legacyTokenFile(providerId: ProviderId): File {
        return File(legacyDir, "${providerId.name.lowercase()}-token.json")
    }
}

actual class PlatformBrowserLauncher actual constructor() : BrowserLauncher {
    override fun open(url: String): Boolean {
        return runCatching {
            if (!Desktop.isDesktopSupported()) return false
            Desktop.getDesktop().browse(URI(url))
            true
        }.getOrDefault(false)
    }
}

actual class PlatformOAuthCallbackServer actual constructor() : OAuthCallbackServer {
    override suspend fun waitForCallback(providerId: ProviderId, timeoutMillis: Long): String? {
        val config = when (providerId) {
            ProviderId.CODEX -> CallbackConfig(port = 1455, path = "/auth/callback")
            ProviderId.CLAUDE_CODE -> CallbackConfig(port = 54545, path = "/callback")
        }
        return withContext(Dispatchers.IO) {
            val result = CompletableDeferred<String?>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 0)
            val executor = Executors.newSingleThreadExecutor()
            server.executor = executor
            server.createContext(config.path) { exchange ->
                val callbackUrl = "http://localhost:${config.port}${exchange.requestURI}"
                exchange.respond(
                    200,
                    """
                    <html>
                      <body style="font-family: -apple-system, BlinkMacSystemFont, sans-serif; padding: 24px;">
                        <h2>QuotaDog</h2>
                        <p>Sign-in complete. You can return to the app.</p>
                      </body>
                    </html>
                    """.trimIndent()
                )
                result.complete(callbackUrl)
            }
            server.createContext("/") { exchange ->
                exchange.respond(404, "Not found")
            }
            try {
                server.start()
                withTimeoutOrNull(timeoutMillis) { result.await() }
            } finally {
                server.stop(0)
                executor.shutdownNow()
            }
        }
    }

    private data class CallbackConfig(val port: Int, val path: String)

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

actual fun createPreferenceSettings(): Settings =
    PreferencesSettings(Preferences.userRoot().node("saien/quotadog/prefs"))

actual fun createHttpClient(): HttpClient = HttpClient(Java) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also {
    SecureRandom().nextBytes(it)
}

actual fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

actual fun base64UrlNoPadding(input: ByteArray): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(input)
}

actual fun platformDebugLog(message: String) {
    // Intentionally disabled by default so account identifiers and provider errors do not
    // end up in stdout in public builds.
}
