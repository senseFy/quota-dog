package saien.quotadog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live desktop smoke test against the local Devin CLI credentials file.
 * Skips automatically when `devin auth login` has not been run on this machine.
 */
class DevinLiveImportTest {
    @Test
    fun importsLocalCredentialsAndFetchesUsageWindows() = runBlocking {
        val token = runCatching { loadDevinCredentialsFromCli() }.getOrElse { error ->
            if (error is ProviderException && error.state == AuthState.NotConfigured) {
                println("SKIP live Devin import: ${error.message}")
                return@runBlocking
            }
            throw error
        }

        val client = QuotaDogClient()
        var accountKey: AccountKey? = null
        try {
            accountKey = client.importDevinAccount()
            assertTrue(accountKey.providerId == ProviderId.DEVIN)
            assertTrue(accountKey.accountId.isNotBlank())

            val snapshot = runCatching { client.refreshUsage(accountKey) }.getOrElse { error ->
                fail("refreshUsage failed: ${error.message}")
            }

            assertTrue(snapshot.windows.isNotEmpty(), "expected quota windows")
            println(
                "Devin live OK account=${if (snapshot.accountEmail.isNullOrBlank()) "missing" else "present"} " +
                    "windows=${snapshot.windows.joinToString { "${it.id}:${it.usedRatio}" }} " +
                    "message=${snapshot.message}",
            )
        } finally {
            accountKey?.let { client.logout(it) }
        }
    }
}
