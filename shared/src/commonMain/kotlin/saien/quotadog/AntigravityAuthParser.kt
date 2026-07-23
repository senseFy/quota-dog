package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Antigravity CLI (`agy`) keyring payloads.
 *
 * Observed shape (go-keyring on macOS):
 * `go-keyring-base64:` + base64(JSON):
 * ```
 * {
 *   "token": {
 *     "access_token": "...",
 *     "token_type": "Bearer",
 *     "refresh_token": "...",
 *     "expiry": "2026-07-23T17:53:30.039416+08:00"
 *   },
 *   "auth_method": "consumer"
 * }
 * ```
 */
internal object AntigravityAuthParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val GO_KEYRING_PREFIX = "go-keyring-base64:"
    private const val DEFAULT_TTL_MILLIS = 55L * 60L * 1000L

    fun parseKeyringSecret(raw: String): OAuthTokenBundle {
        val text = unwrapGoKeyringPayload(raw)
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(
                AuthState.Error,
                "Antigravity CLI keyring payload was not valid JSON.",
            )
        }
        val tokenObj = root["token"]?.jsonObject ?: root
        val accessToken = tokenObj.stringField("access_token")
            ?: tokenObj.stringField("accessToken")
            ?: throw ProviderException(
                AuthState.NotConfigured,
                "Antigravity CLI keyring has no access token. Run `agy` and sign in first.",
            )
        val refreshToken = tokenObj.stringField("refresh_token")
            ?: tokenObj.stringField("refreshToken")
            ?: ""
        val email = root.stringField("email")
            ?: tokenObj.stringField("email")
        val expiresAt = parseExpiryEpochMillis(
            tokenObj.stringField("expiry")
                ?: tokenObj.stringField("expires_at")
                ?: tokenObj.stringField("expiresAt")
                ?: tokenObj.stringField("expired"),
        ) ?: (Clock.System.now().toEpochMilliseconds() + DEFAULT_TTL_MILLIS)

        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = refreshToken,
            email = email,
            expiresAtEpochMillis = expiresAt,
        )
    }

    internal fun unwrapGoKeyringPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw ProviderException(
                AuthState.NotConfigured,
                "Antigravity CLI keyring entry is empty. Run `agy` and sign in first.",
            )
        }
        if (!trimmed.startsWith(GO_KEYRING_PREFIX)) {
            // Some tooling may store bare JSON or a raw access token.
            if (trimmed.startsWith("{")) return trimmed
            return trimmed
        }
        val encoded = trimmed.removePrefix(GO_KEYRING_PREFIX).trim()
        val decoded = runCatching { standardBase64Decode(encoded).decodeToString() }.getOrElse {
            throw ProviderException(
                AuthState.Error,
                "Antigravity CLI keyring payload was not valid base64.",
            )
        }
        if (decoded.isBlank()) {
            throw ProviderException(
                AuthState.NotConfigured,
                "Antigravity CLI keyring payload decoded to empty content.",
            )
        }
        return decoded
    }

    internal fun parseExpiryEpochMillis(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { epoch ->
            // Accept seconds or millis.
            return if (epoch > 10_000_000_000L) epoch else epoch * 1_000L
        }
        return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
            ?: runCatching {
                // kotlinx Instant.parse is strict; normalize common offsets without colon.
                Instant.parse(value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2"))
                    .toEpochMilliseconds()
            }.getOrNull()
    }

    private fun JsonObject.stringField(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun standardBase64Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = input.trim().trimEnd('=').filter { it != '\n' && it != '\r' && it != ' ' }
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0
        for (char in clean) {
            val value = alphabet.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        return output.toByteArray()
    }
}
