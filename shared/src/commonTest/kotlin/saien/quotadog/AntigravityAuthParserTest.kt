package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AntigravityAuthParserTest {
    @Test
    fun parsesGoKeyringWrappedPayload() {
        val json = """
            {
              "token": {
                "access_token": "ya29.access",
                "token_type": "Bearer",
                "refresh_token": "1//refresh",
                "expiry": "2030-01-01T00:00:00Z"
              },
              "auth_method": "consumer"
            }
        """.trimIndent()
        val wrapped = "go-keyring-base64:" + standardBase64Encode(json.encodeToByteArray())

        val token = AntigravityAuthParser.parseKeyringSecret(wrapped)

        assertEquals("ya29.access", token.accessToken)
        assertEquals("1//refresh", token.refreshToken)
        assertTrue(token.expiresAtEpochMillis > 1_800_000_000_000L)
    }

    @Test
    fun parsesBareJsonPayload() {
        val token = AntigravityAuthParser.parseKeyringSecret(
            """
            {
              "access_token": "access-direct",
              "refresh_token": "refresh-direct",
              "email": "user@example.com",
              "expiry": "2030-06-01T12:00:00+00:00"
            }
            """.trimIndent(),
        )

        assertEquals("access-direct", token.accessToken)
        assertEquals("refresh-direct", token.refreshToken)
        assertEquals("user@example.com", token.email)
    }

    @Test
    fun rejectsMissingAccessToken() {
        val error = assertFailsWith<ProviderException> {
            AntigravityAuthParser.parseKeyringSecret("""{"token":{"refresh_token":"only-refresh"}}""")
        }
        assertEquals(AuthState.NotConfigured, error.state)
    }

    @Test
    fun unwrapsGoKeyringPrefix() {
        val json = """{"token":{"access_token":"x"}}"""
        val wrapped = "go-keyring-base64:" + standardBase64Encode(json.encodeToByteArray())
        val unwrapped = AntigravityAuthParser.unwrapGoKeyringPayload(wrapped)
        assertTrue(unwrapped.contains("access_token"))
    }

    private fun standardBase64Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                output.append(alphabet[(buffer shr bits) and 0x3f])
            }
        }
        if (bits > 0) {
            output.append(alphabet[(buffer shl (6 - bits)) and 0x3f])
        }
        while (output.length % 4 != 0) {
            output.append('=')
        }
        return output.toString()
    }
}
