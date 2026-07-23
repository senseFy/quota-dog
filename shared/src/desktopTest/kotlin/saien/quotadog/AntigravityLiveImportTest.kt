package saien.quotadog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live desktop smoke test against the local Antigravity CLI keyring.
 * Skips automatically when the CLI is not signed in on this machine.
 */
class AntigravityLiveImportTest {
    @Test
    fun importsKeychainAndFetchesQuotaWindows() = runBlocking {
        val token = runCatching { loadAntigravityCredentialsFromCli() }.getOrElse { error ->
            if (error is ProviderException && error.state == AuthState.NotConfigured) {
                println("SKIP live Antigravity import: ${error.message}")
                return@runBlocking
            }
            throw error
        }

        val client = QuotaDogClient()
        // Exercise the same refresh/import path the UI uses.
        val accountKey = client.importAntigravityAccount()
        assertTrue(accountKey.providerId == ProviderId.ANTIGRAVITY)
        assertTrue(accountKey.accountId.isNotBlank())

        val snapshot = runCatching { client.refreshUsage(accountKey) }.getOrElse { error ->
            // If access expired, ensureFresh should refresh; still report failure otherwise.
            fail("refreshUsage failed: ${error.message}")
        }

        assertTrue(snapshot.windows.isNotEmpty(), "expected quota windows")
        println(
            "Antigravity live OK account=${snapshot.accountEmail ?: accountKey.accountId} " +
                "windows=${snapshot.windows.joinToString { "${it.id}:${it.usedRatio}" }} " +
                "message=${snapshot.message}",
        )
        client.logout(accountKey)
    }
}
