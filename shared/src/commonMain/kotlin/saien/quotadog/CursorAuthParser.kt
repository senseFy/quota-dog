package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object CursorAuthParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val DEFAULT_TTL_MILLIS = 30L * 24L * 60L * 60L * 1000L

    fun toTokenBundle(
        accessToken: String,
        refreshToken: String?,
        email: String?,
        membershipType: String? = null,
    ): OAuthTokenBundle {
        val expiresAt = jwtExpiryEpochMillis(accessToken)
            ?: (Clock.System.now().toEpochMilliseconds() + DEFAULT_TTL_MILLIS)
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = refreshToken.orEmpty(),
            accountId = membershipType?.takeIf { it.isNotBlank() },
            email = email?.takeIf { it.isNotBlank() },
            expiresAtEpochMillis = expiresAt,
        )
    }

    fun fromAuthRows(rows: Map<String, String>): OAuthTokenBundle {
        val accessToken = rows["cursorAuth/accessToken"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ProviderException(
                AuthState.NotConfigured,
                "Cursor is installed but no access token was found. Sign in to Cursor, then import again.",
            )
        return toTokenBundle(
            accessToken = accessToken,
            refreshToken = rows["cursorAuth/refreshToken"],
            email = rows["cursorAuth/cachedEmail"],
            membershipType = rows["cursorAuth/stripeMembershipType"],
        )
    }

    internal fun jwtExpiryEpochMillis(jwt: String): Long? {
        val payload = jwt.split(".").getOrNull(1) ?: return null
        val decoded = runCatching { base64UrlDecode(payload).decodeToString() }.getOrNull() ?: return null
        val claims = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
        val expSeconds = claims["exp"]?.jsonPrimitive?.longOrNull ?: return null
        return expSeconds * 1_000L
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
