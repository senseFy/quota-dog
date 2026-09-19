package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSignInSupportTest {

    @Test
    fun codexAndClaudeCodeSupportOAuthOnly() {
        assertEquals(setOf(ProviderSignInMethod.OAuth), ProviderId.CODEX.supportedSignInMethods())
        assertEquals(setOf(ProviderSignInMethod.OAuth), ProviderId.CLAUDE_CODE.supportedSignInMethods())
        assertTrue(ProviderId.CODEX.oauthAvailable())
        assertTrue(ProviderId.CLAUDE_CODE.oauthAvailable())
        assertFalse(ProviderId.CODEX.cliImportAvailable())
        assertFalse(ProviderId.CLAUDE_CODE.cliImportAvailable())
    }

    @Test
    fun cursorAntigravityAndDevinSupportCliImportOnly() {
        for (provider in listOf(ProviderId.CURSOR, ProviderId.ANTIGRAVITY, ProviderId.DEVIN)) {
            assertEquals(setOf(ProviderSignInMethod.CliImport), provider.supportedSignInMethods())
            assertFalse(provider.oauthAvailable())
        }
    }

    @Test
    fun grokAndDroidSupportBothMethods() {
        for (provider in listOf(ProviderId.GROK, ProviderId.DROID)) {
            assertEquals(
                setOf(ProviderSignInMethod.OAuth, ProviderSignInMethod.CliImport),
                provider.supportedSignInMethods(),
            )
            assertTrue(provider.oauthAvailable())
        }
    }

    @Test
    fun grokAndDroidCliImportMirrorsPlatformSupport() {
        assertEquals(grokCliImportAvailable(), ProviderId.GROK.cliImportAvailable())
        assertEquals(droidCliImportAvailable(), ProviderId.DROID.cliImportAvailable())
    }

    @Test
    fun oauthAvailabilityMatchesProviderCapability() {
        for (provider in ProviderId.entries) {
            val expected = ProviderSignInMethod.OAuth in provider.supportedSignInMethods()
            assertEquals(expected, provider.oauthAvailable(), "OAuth support for $provider")
        }
    }
}
