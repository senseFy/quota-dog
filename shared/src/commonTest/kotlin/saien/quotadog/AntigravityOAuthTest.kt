package saien.quotadog

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AntigravityOAuthTest {

    @Test
    fun beginLoginBuildsGooglePkceAuthorizationUrl() {
        val client = QuotaDogClient(
            tokenStore = FakeTokenStore(),
            browserLauncher = NoopBrowserLauncher(),
            callbackServer = NoopCallbackServer(),
        )

        val start = client.beginLogin(ProviderId.ANTIGRAVITY, openBrowser = false)
        val url = Url(start.authorizationUrl)

        assertEquals(ProviderId.ANTIGRAVITY, start.providerId)
        assertEquals(AntigravityUsageFetcher.OAUTH_REDIRECT_URI, start.redirectUri)
        assertEquals("accounts.google.com", url.host)
        assertEquals("/o/oauth2/v2/auth", url.encodedPath)
        assertEquals(AntigravityUsageFetcher.OAUTH_CLIENT_ID, url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals(AntigravityUsageFetcher.OAUTH_REDIRECT_URI, url.parameters["redirect_uri"])
        assertEquals(AntigravityUsageFetcher.OAUTH_SCOPE, url.parameters["scope"])
        assertEquals(start.state, url.parameters["state"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(
            base64UrlNoPadding(sha256(start.codeVerifier.encodeToByteArray())),
            url.parameters["code_challenge"],
        )
        assertEquals("offline", url.parameters["access_type"])
        assertEquals("select_account consent", url.parameters["prompt"])
    }

    @Test
    fun completeLoginHandlesDeniedGoogleConsentWithoutCode() = runBlocking {
        val client = QuotaDogClient(
            tokenStore = FakeTokenStore(),
            browserLauncher = NoopBrowserLauncher(),
            callbackServer = NoopCallbackServer(),
        )
        val start = client.beginLogin(ProviderId.ANTIGRAVITY, openBrowser = false)

        val error = assertFailsWith<ProviderException> {
            client.completeLogin(
                start = start,
                callbackUri = "${start.redirectUri}?error=access_denied&state=${start.state}",
            )
        }
        assertEquals(AuthState.Unauthorized, error.state)
    }

    @Test
    fun completeLoginRejectsMismatchedStateAndMissingCode() = runBlocking {
        val client = QuotaDogClient(
            tokenStore = FakeTokenStore(),
            browserLauncher = NoopBrowserLauncher(),
            callbackServer = NoopCallbackServer(),
        )
        val start = client.beginLogin(ProviderId.ANTIGRAVITY, openBrowser = false)

        val mismatch = assertFailsWith<ProviderException> {
            client.completeLogin(
                start = start,
                callbackUri = "${start.redirectUri}?code=abc&state=wrong-state",
            )
        }
        assertEquals(AuthState.Error, mismatch.state)

        val missingCode = assertFailsWith<ProviderException> {
            client.completeLogin(
                start = start,
                callbackUri = "${start.redirectUri}?state=${start.state}",
            )
        }
        assertEquals(AuthState.Error, missingCode.state)
    }

    @Test
    fun parsesTokenExchangeResponse() {
        val now = 1_700_000_000_000L
        val bundle = AntigravityUsageFetcher.parseTokenResponse(
            status = 200,
            body = """
            {
              "access_token": "ya29.test-access",
              "refresh_token": "1//test-refresh",
              "expires_in": 3599,
              "id_token": "eyJhbGciOiJub25lIn0.eyJlbWFpbCI6InVzZXJAZ21haWwuY29tIn0.sig",
              "token_type": "Bearer"
            }
            """.trimIndent(),
            nowEpochMillis = now,
        )

        assertEquals("ya29.test-access", bundle.accessToken)
        assertEquals("1//test-refresh", bundle.refreshToken)
        assertNotNull(bundle.idToken)
        assertEquals(now + 3_599_000L, bundle.expiresAtEpochMillis)
    }

    @Test
    fun preservesFallbackRefreshTokenWhenRefreshOmitsIt() {
        val bundle = AntigravityUsageFetcher.parseTokenResponse(
            status = 200,
            body = """{"access_token":"ya29.fresh","expires_in":1800}""",
            fallbackRefreshToken = "1//existing-refresh",
            contextLabel = "token refresh",
            nowEpochMillis = 1_700_000_000_000L,
        )

        assertEquals("ya29.fresh", bundle.accessToken)
        assertEquals("1//existing-refresh", bundle.refreshToken)
    }

    @Test
    fun mapsGoogleInvalidGrantAndAuthErrorsToRequiresRelogin() {
        val invalidGrant = assertFailsWith<ProviderException> {
            AntigravityUsageFetcher.parseTokenResponse(
                status = 400,
                body = """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""",
                contextLabel = "token refresh",
            )
        }
        assertEquals(AuthState.RequiresRelogin, invalidGrant.state)
        assertEquals(400, invalidGrant.statusCode)

        val unauthorized = assertFailsWith<ProviderException> {
            AntigravityUsageFetcher.parseTokenResponse(
                status = 401,
                body = """{"error":"unauthorized_client"}""",
                contextLabel = "token refresh",
            )
        }
        assertEquals(AuthState.RequiresRelogin, unauthorized.state)
    }

    @Test
    fun mapsServerErrorsAndMalformedPayloadsToError() {
        val serverErr = assertFailsWith<ProviderException> {
            AntigravityUsageFetcher.parseTokenResponse(
                status = 503,
                body = "service unavailable",
                contextLabel = "token exchange",
            )
        }
        assertEquals(AuthState.Error, serverErr.state)

        val badJson = assertFailsWith<ProviderException> {
            AntigravityUsageFetcher.parseTokenResponse(
                status = 200,
                body = "not-json",
                contextLabel = "token exchange",
            )
        }
        assertEquals(AuthState.Error, badJson.state)

        val missingAccess = assertFailsWith<ProviderException> {
            AntigravityUsageFetcher.parseTokenResponse(
                status = 200,
                body = """{"refresh_token":"1//only-refresh"}""",
                contextLabel = "token exchange",
            )
        }
        assertEquals(AuthState.Error, missingAccess.state)
        assertTrue(missingAccess.message.orEmpty().contains("access_token"))
    }

    private class FakeTokenStore : TokenStore {
        private val tokens = linkedMapOf<AccountKey, OAuthTokenBundle>()
        override suspend fun list(): List<StoredToken> = tokens.entries.map { StoredToken(it.key, it.value) }
        override suspend fun load(accountKey: AccountKey): OAuthTokenBundle? = tokens[accountKey]
        override suspend fun save(providerId: ProviderId, token: OAuthTokenBundle): AccountKey {
            val key = accountKeyForToken(providerId, token)
            tokens[key] = token
            return key
        }
        override suspend fun save(accountKey: AccountKey, token: OAuthTokenBundle) {
            tokens[accountKey] = token
        }
        override suspend fun delete(accountKey: AccountKey) {
            tokens.remove(accountKey)
        }
        override suspend fun exportTokensForSync(): List<CloudSyncAccountRecord> = emptyList()
        override suspend fun importTokenForSync(
            accountKey: AccountKey,
            token: OAuthTokenBundle,
            updatedAtEpochMillis: Long,
        ) {
            tokens[accountKey] = token
        }
        override suspend fun deleteForSync(accountKey: AccountKey) {
            tokens.remove(accountKey)
        }
    }

    private class NoopBrowserLauncher : BrowserLauncher {
        override fun open(url: String): Boolean = true
    }

    private class NoopCallbackServer : OAuthCallbackServer {
        override suspend fun waitForCallback(providerId: ProviderId, timeoutMillis: Long): String? = null
        override suspend fun waitForCallback(port: Int, path: String, timeoutMillis: Long): String? = null
    }
}
