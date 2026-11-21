package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Parses the Amp CLI secrets store (`~/.local/share/amp/secrets.json`).
 *
 * Observed shape (written by `amp login`):
 * ```
 * {
 *   "apiKey@https://ampcode.com/": "sgamp_user_..."
 * }
 * ```
 * Localhost development entries (`apiKey@http://127.0.0.1:...`) and secondary
 * `#cli-account=` slots are skipped unless no primary HTTPS entry exists.
 */
internal object AmpAuthParser {
    private const val NO_EXPIRY_MILLIS = 100L * 365L * 24L * 60L * 60L * 1000L
    const val DEFAULT_API_SERVER_URL = "https://ampcode.com"
    private const val API_KEY_PREFIX = "apiKey@"

    private val json = Json { ignoreUnknownKeys = true }

    fun parseSecretsJson(
        text: String,
        preferredServerUrl: String? = null,
    ): OAuthTokenBundle {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(
                AuthState.Error,
                "Amp secrets.json was not valid JSON.",
            )
        }
        val targetBase = cleanServerUrl(preferredServerUrl) ?: DEFAULT_API_SERVER_URL
        val directKey = root.nonBlankString("$API_KEY_PREFIX$targetBase/")
            ?: root.nonBlankString("$API_KEY_PREFIX$targetBase")
        if (directKey != null) {
            return fromApiKey(directKey, targetBase)
        }

        val fallback = findHttpsApiKeyEntry(root)
            ?: throw ProviderException(
                AuthState.NotConfigured,
                "Amp secrets.json has no API key for $targetBase. Run `amp login` first.",
            )
        return fromApiKey(fallback.second, fallback.first)
    }

    fun fromApiKey(
        rawKey: String,
        serverUrl: String? = null,
    ): OAuthTokenBundle {
        val apiKey = rawKey.trim()
        if (apiKey.isEmpty()) {
            throw ProviderException(
                AuthState.NotConfigured,
                "Amp API key is empty. Run `amp login` or sign in with Amp first.",
            )
        }
        val cleanedServer = cleanServerUrl(serverUrl)
        val customServer = cleanedServer?.takeUnless { it.equals(DEFAULT_API_SERVER_URL, ignoreCase = true) }
        return OAuthTokenBundle(
            accessToken = apiKey,
            refreshToken = "",
            apiServerUrl = customServer,
            expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() + NO_EXPIRY_MILLIS,
        )
    }

    internal fun cleanServerUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        return trimmed.substringBefore('#').trimEnd('/').takeIf { it.length > "https://".length }
    }

    private fun findHttpsApiKeyEntry(root: JsonObject): Pair<String, String>? {
        var secondaryMatch: Pair<String, String>? = null
        for ((key, value) in root) {
            if (!key.startsWith(API_KEY_PREFIX)) continue
            val token = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            val rawUrl = key.removePrefix(API_KEY_PREFIX)
            val cleaned = cleanServerUrl(rawUrl) ?: continue
            if (!rawUrl.contains('#')) {
                return cleaned to token
            }
            if (secondaryMatch == null) {
                secondaryMatch = cleaned to token
            }
        }
        return secondaryMatch
    }

    private fun JsonObject.nonBlankString(key: String): String? {
        return (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
