package saien.quotadog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live desktop smoke test against the local Amp CLI secrets store.
 * Skips automatically when `amp login` has not been run on this machine.
 */
class AmpLiveImportTest {
    @Test
    fun importsLocalCredentialsAndFetchesUsageWindows() = runBlocking {
        runCatching { loadAmpCredentialsFromCli() }.getOrElse { error ->
            if (error is ProviderException && error.state == AuthState.NotConfigured) {
                println("SKIP live Amp import: ${error.message}")
                return@runBlocking
            }
            throw error
        }

        val client = QuotaDogClient()
        var accountKey: AccountKey? = null
        try {
            accountKey = client.importAmpAccount()
            assertTrue(accountKey.providerId == ProviderId.AMP)
            assertTrue(accountKey.accountId.isNotBlank())

            val snapshot = runCatching { client.refreshUsage(accountKey) }.getOrElse { error ->
                if (error is ProviderException && error.state == AuthState.RequiresRelogin) {
                    println("SKIP live Amp import: ${error.message}")
                    return@runBlocking
                }
                fail("refreshUsage failed: ${error.message}")
            }

            assertTrue(snapshot.windows.isNotEmpty(), "expected quota windows")
            println(
                "Amp live OK account=${if (snapshot.accountEmail.isNullOrBlank()) "missing" else "present"} " +
                    "plan=${snapshot.planLabel} " +
                    "windows=${snapshot.windows.joinToString { "${it.id}:${it.usedRatio}" }} " +
                    "message=${snapshot.message}",
            )
        } finally {
            accountKey?.let { client.logout(it) }
        }
    }
}
