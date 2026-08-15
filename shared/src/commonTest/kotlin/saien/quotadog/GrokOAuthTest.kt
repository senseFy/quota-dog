package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrokOAuthTest {
    @Test
    fun acceptsXaiHttpsEndpoints() {
        val url = GrokOAuth.validateOAuthEndpoint(
            "https://auth.x.ai/oauth2/token",
            "token_endpoint",
        )
        assertEquals("https://auth.x.ai/oauth2/token", url)
    }

    @Test
    fun rejectsNonHttpsEndpoints() {
        assertFailsWith<ProviderException> {
            GrokOAuth.validateOAuthEndpoint("http://auth.x.ai/oauth2/token", "token_endpoint")
        }
    }

    @Test
    fun rejectsNonXaiHosts() {
        assertFailsWith<ProviderException> {
            GrokOAuth.validateOAuthEndpoint("https://example.com/oauth2/token", "token_endpoint")
        }
    }

    @Test
    fun parsesDiscoveryJson() {
        val discovery = GrokOAuth.parseDiscoveryJson(
            """
            {
              "device_authorization_endpoint": "https://auth.x.ai/oauth2/device/code",
              "token_endpoint": "https://auth.x.ai/oauth2/token"
            }
            """.trimIndent()
        )
        assertEquals("https://auth.x.ai/oauth2/device/code", discovery.deviceAuthorizationEndpoint)
        assertEquals("https://auth.x.ai/oauth2/token", discovery.tokenEndpoint)
    }

    @Test
    fun parsesDeviceCodeResponse() {
        val device = GrokOAuth.parseDeviceCodeResponse(
            statusCode = 200,
            body = """
            {
              "device_code": "dev-123",
              "user_code": "ABCD-EFGH",
              "verification_uri": "https://accounts.x.ai/device",
              "verification_uri_complete": "https://accounts.x.ai/device?user_code=ABCD-EFGH",
              "expires_in": 900,
              "interval": 5
            }
            """.trimIndent(),
            tokenEndpoint = "https://auth.x.ai/oauth2/token",
        )
        assertEquals("dev-123", device.deviceCode)
        assertEquals("ABCD-EFGH", device.userCode)
        assertEquals("https://accounts.x.ai/device?user_code=ABCD-EFGH", device.authorizationUrl)
        assertEquals(900, device.expiresIn)
        assertEquals(5, device.intervalSeconds)
    }

    @Test
    fun treatsAuthorizationPendingAsContinue() {
        val result = GrokOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"authorization_pending"}""",
            intervalSeconds = 5,
        )
        assertIs<GrokTokenPollResult.Pending>(result)
        assertEquals(5, result.nextIntervalSeconds)
    }

    @Test
    fun slowsDownWhenRequested() {
        val result = GrokOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"slow_down"}""",
            intervalSeconds = 5,
        )
        assertIs<GrokTokenPollResult.Pending>(result)
        assertEquals(10, result.nextIntervalSeconds)
    }

    @Test
    fun mapsAccessDenied() {
        val result = GrokOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"access_denied"}""",
            intervalSeconds = 5,
        )
        val failed = assertIs<GrokTokenPollResult.Failed>(result)
        assertEquals(AuthState.Unauthorized, failed.error.state)
    }

    @Test
    fun parsesSuccessfulTokenWithJwtIdentity() {
        val idToken = unsignedJwt("""{"email":"user@example.com","sub":"user-123"}""")
        val result = GrokOAuth.interpretTokenPayload(
            statusCode = 200,
            body = """
            {
              "access_token": "access-1",
              "refresh_token": "refresh-1",
              "id_token": "$idToken",
              "expires_in": 3600
            }
            """.trimIndent(),
            intervalSeconds = 5,
        )
        val success = assertIs<GrokTokenPollResult.Success>(result)
        assertEquals("access-1", success.token.accessToken)
        assertEquals("refresh-1", success.token.refreshToken)
        assertEquals("user@example.com", success.token.email)
        assertEquals("user-123", success.token.accountId)
        assertTrue(success.token.expiresAtEpochMillis > 0)
    }

    @Test
    fun parsesJwtIdentityFromPayload() {
        val identity = GrokOAuth.parseJwtIdentity(
            unsignedJwt("""{"email":"a@b.com","sub":"sub-9"}""")
        )
        assertEquals("a@b.com", identity.email)
        assertEquals("sub-9", identity.subject)
        val empty = GrokOAuth.parseJwtIdentity(null)
        assertNull(empty.email)
        assertNull(empty.subject)
    }

    private fun unsignedJwt(payloadJson: String): String {
        val header = base64UrlNoPadding("""{"alg":"none"}""".encodeToByteArray())
        val payload = base64UrlNoPadding(payloadJson.encodeToByteArray())
        return "$header.$payload.sig"
    }
}
