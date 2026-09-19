package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses the Factory Droid CLI credentials payload.
 *
 * `droid` stores `{"access_token": ..., "refresh_token": ..., "active_organization_id": ...}`
 * AES-256-GCM encrypted under `~/.factory/` (the desktop source set decrypts it); the
 * legacy `auth.encrypted` and `auth.json` files carry the same JSON in plaintext.
 * `access_token` is a WorkOS JWT; email and expiry come from its claims.
 */
internal object DroidAuthParser {
    private const val FALLBACK_EXPIRY_MILLIS = 7L * 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun parseAuthJson(text: String): OAuthTokenBundle {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Droid credentials were not valid JSON.")
        }
        val accessToken = root.stringField("access_token")
            ?: throw ProviderException(
                AuthState.NotConfigured,
                "Droid credentials have no access_token. Run `droid` and sign in first.",
            )
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = root.stringField("refresh_token") ?: "",
            accountId = root.stringField("active_organization_id"),
            email = jwtStringClaim(accessToken, "email"),
            expiresAtEpochMillis = jwtExpiryMillis(accessToken)
                ?: Clock.System.now().toEpochMilliseconds() + FALLBACK_EXPIRY_MILLIS,
        )
    }

    internal fun JsonObject.stringField(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun jwtExpiryMillis(token: String): Long? {
        val seconds = jwtClaims(token)?.get("exp")?.jsonPrimitive?.longOrNull ?: return null
        return seconds * 1000L
    }

    internal fun jwtStringClaim(token: String, name: String): String? {
        return jwtClaims(token)?.get(name)?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun jwtClaims(token: String): JsonObject? {
        val payload = token.split(".").getOrNull(1) ?: return null
        val decoded = runCatching { base64UrlDecode(payload).decodeToString() }.getOrNull() ?: return null
        return runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
    }

    private fun base64UrlDecode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val clean = input.trimEnd('=')
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
