package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrokAuthParserTest {
    @Test
    fun prefersOidcScopeOverLegacySession() {
        val token = GrokAuthParser.parseAuthJson(
            """
            {
              "https://accounts.x.ai/sign-in": {
                "key": "legacy-token",
                "refresh_token": "legacy-refresh",
                "email": "legacy@example.com",
                "expires_at": "2030-01-01T00:00:00Z"
              },
              "https://auth.x.ai::desktop-client": {
                "key": "oidc-token",
                "refresh_token": "oidc-refresh",
                "email": "user@example.com",
                "user_id": "user-123",
                "expires_at": "2030-06-01T00:00:00Z",
                "auth_mode": "oidc"
              }
            }
            """.trimIndent()
        )

        assertEquals("oidc-token", token.accessToken)
        assertEquals("oidc-refresh", token.refreshToken)
        assertEquals("user@example.com", token.email)
        assertEquals("user-123", token.accountId)
    }

    @Test
    fun fallsBackToLegacySessionWhenOidcMissingKey() {
        val token = GrokAuthParser.parseAuthJson(
            """
            {
              "https://accounts.x.ai/sign-in": {
                "key": "legacy-token",
                "refresh_token": "legacy-refresh",
                "email": "legacy@example.com",
                "expires_at": "2030-01-01T00:00:00Z"
              },
              "https://auth.x.ai::desktop-client": {
                "refresh_token": "missing-key"
              }
            }
            """.trimIndent()
        )

        assertEquals("legacy-token", token.accessToken)
        assertEquals("legacy@example.com", token.email)
    }

    @Test
    fun rejectsMissingTokens() {
        assertFailsWith<ProviderException> {
            GrokAuthParser.parseAuthJson("{}")
        }
    }
}