package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DroidOAuthTest {
    @Test
    fun parsesDeviceCodeResponse() {
        val device = DroidOAuth.parseDeviceCodeResponse(
            statusCode = 200,
            body = """
            {
              "device_code": "dev-123",
              "user_code": "RPDN-LHVT",
              "verification_uri": "https://auth.factory.ai/device",
              "verification_uri_complete": "https://auth.factory.ai/device?user_code=RPDN-LHVT",
              "expires_in": 300,
              "interval": 5
            }
            """.trimIndent(),
        )
        assertEquals("dev-123", device.deviceCode)
        assertEquals("RPDN-LHVT", device.userCode)
        assertEquals("https://auth.factory.ai/device?user_code=RPDN-LHVT", device.authorizationUrl)
        assertEquals(300, device.expiresIn)
        assertEquals(5, device.intervalSeconds)
    }

    @Test
    fun fallsBackToVerificationUriWhenCompleteMissing() {
        val device = DroidOAuth.parseDeviceCodeResponse(
            statusCode = 200,
            body = """
            {
              "device_code": "dev-123",
              "user_code": "RPDN-LHVT",
              "verification_uri": "https://auth.factory.ai/device"
            }
            """.trimIndent(),
        )
        assertEquals("https://auth.factory.ai/device", device.authorizationUrl)
        assertEquals(300, device.expiresIn)
    }

    @Test
    fun rejectsDeviceCodeResponseWithoutCodes() {
        assertFailsWith<ProviderException> {
            DroidOAuth.parseDeviceCodeResponse(
                statusCode = 200,
                body = """{"verification_uri": "https://auth.factory.ai/device"}""",
            )
        }
    }

    @Test
    fun rejectsUntrustedVerificationUri() {
        assertFailsWith<ProviderException> {
            DroidOAuth.parseDeviceCodeResponse(
                statusCode = 200,
                body = """
                {
                  "device_code": "dev-123",
                  "user_code": "RPDN-LHVT",
                  "verification_uri": "https://evil.example.com/device"
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun acceptsWorkosVerificationUri() {
        assertEquals(
            "https://api.workos.com/device",
            DroidOAuth.validateVerificationUri("https://api.workos.com/device"),
        )
    }

    @Test
    fun rejectsNonHttpsVerificationUri() {
        assertFailsWith<ProviderException> {
            DroidOAuth.validateVerificationUri("http://auth.factory.ai/device")
        }
    }

    @Test
    fun treatsAuthorizationPendingAsContinue() {
        val result = DroidOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"authorization_pending"}""",
            intervalSeconds = 5,
        )
        assertIs<DroidTokenPollResult.Pending>(result)
        assertEquals(5, result.nextIntervalSeconds)
    }

    @Test
    fun slowsDownByOneSecond() {
        val result = DroidOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"slow_down"}""",
            intervalSeconds = 5,
        )
        assertIs<DroidTokenPollResult.Pending>(result)
        assertEquals(6, result.nextIntervalSeconds)
    }

    @Test
    fun mapsAccessDenied() {
        val result = DroidOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"access_denied"}""",
            intervalSeconds = 5,
        )
        val failed = assertIs<DroidTokenPollResult.Failed>(result)
        assertEquals(AuthState.Unauthorized, failed.error.state)
    }

    @Test
    fun mapsExpiredTokenToTimeout() {
        val result = DroidOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"expired_token"}""",
            intervalSeconds = 5,
        )
        val failed = assertIs<DroidTokenPollResult.Failed>(result)
        assertEquals(AuthState.Error, failed.error.state)
    }

    @Test
    fun mapsInvalidGrantToRelogin() {
        val result = DroidOAuth.interpretTokenPayload(
            statusCode = 400,
            body = """{"error":"invalid_grant","error_description":"Refresh token already exchanged."}""",
            intervalSeconds = 5,
        )
        val failed = assertIs<DroidTokenPollResult.Failed>(result)
        assertEquals(AuthState.RequiresRelogin, failed.error.state)
    }

    @Test
    fun parsesSuccessfulTokenWithUserIdentity() {
        val accessToken = unsignedJwt("""{"email":"dev@factory.ai","exp":4102444800}""")
        val result = DroidOAuth.interpretTokenPayload(
            statusCode = 200,
            body = """
            {
              "access_token": "$accessToken",
              "refresh_token": "refresh-1",
              "organization_id": "org_01ABC",
              "user": { "email": "dev@factory.ai" }
            }
            """.trimIndent(),
            intervalSeconds = 5,
        )
        val success = assertIs<DroidTokenPollResult.Success>(result)
        assertEquals(accessToken, success.token.accessToken)
        assertEquals("refresh-1", success.token.refreshToken)
        assertEquals("dev@factory.ai", success.token.email)
        assertEquals("org_01ABC", success.token.accountId)
        assertTrue(!success.token.isExpired())
    }

    @Test
    fun rejectsNonJsonPollBody() {
        val result = DroidOAuth.interpretTokenPayload(
            statusCode = 500,
            body = "not json",
            intervalSeconds = 5,
        )
        assertIs<DroidTokenPollResult.Failed>(result)
    }

    private fun unsignedJwt(payloadJson: String): String {
        val header = base64UrlNoPadding("""{"alg":"none"}""".encodeToByteArray())
        val payload = base64UrlNoPadding(payloadJson.encodeToByteArray())
        return "$header.$payload.sig"
    }
}
