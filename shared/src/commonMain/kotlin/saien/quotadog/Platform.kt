package saien.quotadog

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient

interface TokenStore {
    suspend fun list(): List<StoredToken>
    suspend fun load(accountKey: AccountKey): OAuthTokenBundle?
    suspend fun save(providerId: ProviderId, token: OAuthTokenBundle): AccountKey
    suspend fun save(accountKey: AccountKey, token: OAuthTokenBundle)
    suspend fun delete(accountKey: AccountKey)
}

interface BrowserLauncher {
    fun open(url: String): Boolean
}

interface OAuthCallbackServer {
    suspend fun waitForCallback(providerId: ProviderId, timeoutMillis: Long = 300_000): String?
}

expect class PlatformTokenStore() : TokenStore

expect class PlatformBrowserLauncher() : BrowserLauncher

expect class PlatformOAuthCallbackServer() : OAuthCallbackServer

expect fun createHttpClient(): HttpClient

/** Persistent key/value store for non-secret app preferences (theme, refresh interval, etc.). */
expect fun createPreferenceSettings(): Settings

expect fun secureRandomBytes(size: Int): ByteArray

expect fun sha256(input: ByteArray): ByteArray

expect fun base64UrlNoPadding(input: ByteArray): String

expect fun platformDebugLog(message: String)
