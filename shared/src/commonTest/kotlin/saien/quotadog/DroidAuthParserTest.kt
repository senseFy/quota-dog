package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock

class DroidAuthParserTest {
    private fun unsignedJwt(payload: String): String {
        val header = "eyJhbGciOiJSUzI1NiJ9" // {"alg":"RS256"}
        return "$header.$payload.signature"
    }

    // {"email":"dev@factory.ai","exp":4102444800} (2100-01-01)
    private val liveJwt = unsignedJwt(
        "eyJlbWFpbCI6ImRldkBmYWN0b3J5LmFpIiwiZXhwIjo0MTAyNDQ0ODAwfQ"
    )

    // {"email":"dev@factory.ai","exp":1} (expired 1970)
    private val expiredJwt = unsignedJwt(
        "eyJlbWFpbCI6ImRldkBmYWN0b3J5LmFpIiwiZXhwIjoxfQ"
    )

    @Test
    fun parsesDecryptedAuthPayload() {
        val token = DroidAuthParser.parseAuthJson(
            """
            {
              "access_token": "$liveJwt",
              "refresh_token": "rt-25-chars-session-token",
              "active_organization_id": "org_01ABC"
            }
            """.trimIndent()
        )

        assertEquals(liveJwt, token.accessToken)
        assertEquals("rt-25-chars-session-token", token.refreshToken)
        assertEquals("org_01ABC", token.accountId)
        assertEquals("dev@factory.ai", token.email)
        assertTrue(!token.isExpired())
        assertEquals(4102444800_000L, token.expiresAtEpochMillis)
    }

    @Test
    fun readsJwtExpiryEvenWhenExpired() {
        val token = DroidAuthParser.parseAuthJson(
            """{"access_token": "$expiredJwt", "refresh_token": "rt"}"""
        )

        assertTrue(token.isExpired(bufferMillis = 0))
        assertEquals("dev@factory.ai", token.email)
    }

    @Test
    fun fallsBackToSevenDayExpiryForOpaqueTokens() {
        val before = Clock.System.now().toEpochMilliseconds()
        val token = DroidAuthParser.parseAuthJson(
            """{"access_token": "opaque-token", "refresh_token": "rt"}"""
        )

        assertNull(token.email)
        assertTrue(token.expiresAtEpochMillis >= before + 7L * 24L * 60L * 60L * 1000L)
        assertTrue(!token.isExpired())
    }

    @Test
    fun requiresAccessToken() {
        val error = assertFailsWith<ProviderException> {
            DroidAuthParser.parseAuthJson("""{"refresh_token": "rt"}""")
        }
        assertEquals(AuthState.NotConfigured, error.state)
    }

    @Test
    fun rejectsNonJson() {
        val error = assertFailsWith<ProviderException> {
            DroidAuthParser.parseAuthJson("not json")
        }
        assertEquals(AuthState.Error, error.state)
    }

    @Test
    fun toleratesMissingRefreshToken() {
        val token = DroidAuthParser.parseAuthJson(
            """{"access_token": "$liveJwt"}"""
        )
        assertEquals("", token.refreshToken)
        assertNotNull(token.accessToken)
    }
}
