package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object GrokAuthParser {
    private const val OIDC_SCOPE_PREFIX = "https://auth.x.ai::"
    private const val LEGACY_SESSION_SCOPE = "https://accounts.x.ai/sign-in"

    private val json = Json { ignoreUnknownKeys = true }

    fun parseAuthJson(text: String): OAuthTokenBundle {
        val root = json.parseToJsonElement(text).jsonObject
        val selected = selectPreferredEntry(root)
            ?: throw ProviderException(
                AuthState.NotConfigured,
                "Grok auth.json contains no access tokens. Run `grok login` first."
            )
        val entry = selected.entry
        val accessToken = selected.accessToken
        val refreshToken = entry.stringField("refresh_token") ?: ""
        val email = entry.stringField("email")
        val accountId = entry.stringField("user_id") ?: entry.stringField("team_id")
        val expiresAt = entry.stringField("expires_at")?.let(::parseInstant)
            ?: Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + 7L * 24L * 60L * 60L * 1000L)
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accountId = accountId,
            email = email,
            expiresAtEpochMillis = expiresAt.toEpochMilliseconds(),
        )
    }

    private fun selectPreferredEntry(root: JsonObject): SelectedGrokEntry? {
        var oidcCandidate: SelectedGrokEntry? = null
        var legacyCandidate: SelectedGrokEntry? = null
        for ((scope, value) in root) {
            val entry = value as? JsonObject ?: continue
            val key = entry.stringField("key") ?: continue
            val candidate = SelectedGrokEntry(entry, key)
            when {
                scope.startsWith(OIDC_SCOPE_PREFIX) -> oidcCandidate = candidate
                scope == LEGACY_SESSION_SCOPE || scope.contains("/sign-in") -> legacyCandidate = candidate
            }
        }
        return oidcCandidate ?: legacyCandidate
    }

    private fun parseInstant(raw: String): Instant {
        return runCatching { Instant.parse(raw) }.getOrElse {
            throw ProviderException(AuthState.Error, "Grok auth.json has an invalid expires_at timestamp.")
        }
    }

    private fun JsonObject.stringField(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private data class SelectedGrokEntry(
    val entry: JsonObject,
    val accessToken: String,
)
