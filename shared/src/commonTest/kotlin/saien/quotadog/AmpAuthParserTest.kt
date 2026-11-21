package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmpAuthParserTest {

    @Test
    fun readsDefaultAmpcodeKeyAndIgnoresLocalhostEntries() {
        val token = AmpAuthParser.parseSecretsJson(
            """
            {
              "apiKey@http://127.0.0.1:28317": "sgamp_local_dev",
              "apiKey@https://ampcode.com/": "sgamp_user_prod_123",
              "mcp-oauth-token@#linear": "other"
            }
            """.trimIndent(),
        )

        assertEquals("sgamp_user_prod_123", token.accessToken)
        assertEquals("", token.refreshToken)
        assertNull(token.apiServerUrl)
        assertTrue(!token.isExpired())
    }

    @Test
    fun honorsCustomHttpsServerUrl() {
        val token = AmpAuthParser.parseSecretsJson(
            """
            {
              "apiKey@https://amp.example.com/": "sgamp_enterprise_key"
            }
            """.trimIndent(),
            preferredServerUrl = "https://amp.example.com/",
        )

        assertEquals("sgamp_enterprise_key", token.accessToken)
        assertEquals("https://amp.example.com", token.apiServerUrl)
    }

    @Test
    fun fallsBackToOtherHttpsEntryWhenDefaultMissing() {
        val token = AmpAuthParser.parseSecretsJson(
            """
            {
              "apiKey@http://localhost:3000": "local-ignored",
              "apiKey@https://amp.corp.dev/": "sgamp_corp"
            }
            """.trimIndent(),
        )

        assertEquals("sgamp_corp", token.accessToken)
        assertEquals("https://amp.corp.dev", token.apiServerUrl)
    }

    @Test
    fun ignoresNonStringApiKeyEntriesSafely() {
        val token = AmpAuthParser.parseSecretsJson(
            """
            {
              "apiKey@https://ampcode.com/": {"nested": true},
              "apiKey@https://amp.other.dev/": 12345,
              "apiKey@https://amp.valid.dev/": "sgamp_valid_key"
            }
            """.trimIndent(),
        )

        assertEquals("sgamp_valid_key", token.accessToken)
        assertEquals("https://amp.valid.dev", token.apiServerUrl)
    }

    @Test
    fun rejectsMissingHttpsApiKeyAndInvalidJson() {
        val missing = assertFailsWith<ProviderException> {
            AmpAuthParser.parseSecretsJson("""{"apiKey@http://127.0.0.1:28317":"local-only"}""")
        }
        assertEquals(AuthState.NotConfigured, missing.state)

        val badJson = assertFailsWith<ProviderException> {
            AmpAuthParser.parseSecretsJson("not json")
        }
        assertEquals(AuthState.Error, badJson.state)
    }
}
