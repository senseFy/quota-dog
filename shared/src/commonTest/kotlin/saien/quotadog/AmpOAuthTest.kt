package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AmpOAuthTest {

    @Test
    fun parsesDeviceCodeResponse() {
        val device = AmpOAuth.parseDeviceCodeResponse(
            statusCode = 200,
            body = """
            {
              "device_code": "dev-abc",
              "user_code": "V5N-D9R-9B7",
              "verification_uri": "https://ampcode.com/auth/cli/device",
              "verification_uri_complete": "https://ampcode.com/auth/cli/device?user_code=V5N-D9R-9B7",
              "expires_in": 300,
              "interval": 5
            }
            """.trimIndent(),
        )

        assertEquals("dev-abc", device.deviceCode)
        assertEquals("V5N-D9R-9B7", device.userCode)
        assertEquals("https://ampcode.com/auth/cli/device?user_code=V5N-D9R-9B7", device.authorizationUrl)
        assertEquals(300, device.expiresIn)
        assertEquals(5, device.intervalSeconds)
    }

    @Test
    fun rejectsUntrustedOrNonHttpsVerificationUri() {
        assertFailsWith<ProviderException> {
            AmpOAuth.validateVerificationUri("https://evil.example.com/auth/cli/device")
        }
        assertFailsWith<ProviderException> {
            AmpOAuth.validateVerificationUri("http://ampcode.com/auth/cli/device")
        }
    }

    @Test
    fun handlesPendingSlowDownAndTerminalPollErrors() {
        val pending = AmpOAuth.interpretTokenPayload(400, """{"error":"authorization_pending"}""", 5)
        assertIs<AmpTokenPollResult.Pending>(pending)
        assertEquals(5, pending.nextIntervalSeconds)

        val slowDown = AmpOAuth.interpretTokenPayload(400, """{"error":"slow_down"}""", 5)
        assertIs<AmpTokenPollResult.Pending>(slowDown)
        assertEquals(10, slowDown.nextIntervalSeconds)

        val denied = AmpOAuth.interpretTokenPayload(400, """{"error":"access_denied"}""", 5)
        assertEquals(AuthState.Unauthorized, assertIs<AmpTokenPollResult.Failed>(denied).error.state)

        val expired = AmpOAuth.interpretTokenPayload(400, """{"error":"expired_token"}""", 5)
        assertEquals(AuthState.Error, assertIs<AmpTokenPollResult.Failed>(expired).error.state)

        val invalidGrant = AmpOAuth.interpretTokenPayload(400, """{"error":"invalid_grant"}""", 5)
        assertEquals(AuthState.RequiresRelogin, assertIs<AmpTokenPollResult.Failed>(invalidGrant).error.state)
    }

    @Test
    fun parsesSuccessfulDeviceTokenResponse() {
        val result = AmpOAuth.interpretTokenPayload(
            statusCode = 200,
            body = """{"access_token":"sgamp_user_live","token_type":"Bearer"}""",
            intervalSeconds = 5,
        )
        val success = assertIs<AmpTokenPollResult.Success>(result)
        assertEquals("sgamp_user_live", success.token.accessToken)
        assertTrue(!success.token.isExpired())
    }
}
