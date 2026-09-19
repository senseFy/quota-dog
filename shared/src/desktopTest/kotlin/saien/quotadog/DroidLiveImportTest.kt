package saien.quotadog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live desktop smoke test against the local Factory droid CLI credentials.
 * Skips automatically when `droid` has not been signed in on this machine.
 */
class DroidLiveImportTest {
    @Test
    fun importsLocalCredentialsAndFetchesUsageWindows() = runBlocking {
        runCatching { loadDroidCredentialsFromCli() }.getOrElse { error ->
            if (error is ProviderException && error.state == AuthState.NotConfigured) {
                println("SKIP live Droid import: ${error.message}")
                return@runBlocking
            }
            throw error
        }

        val client = QuotaDogClient()
        var accountKey: AccountKey? = null
        try {
            accountKey = client.importDroidAccount()
            assertTrue(accountKey.providerId == ProviderId.DROID)
            assertTrue(accountKey.accountId.isNotBlank())

            val snapshot = runCatching { client.refreshUsage(accountKey) }.getOrElse { error ->
                // A stored-but-stale session (expired access token plus a rotated
                // refresh token) is a local precondition, like being signed out.
                if (error is ProviderException && error.state == AuthState.RequiresRelogin) {
                    println("SKIP live Droid import: ${error.message}")
                    return@runBlocking
                }
                fail("refreshUsage failed: ${error.message}")
            }

            assertTrue(snapshot.windows.isNotEmpty(), "expected quota windows")
            println(
                "Droid live OK account=${if (snapshot.accountEmail.isNullOrBlank()) "missing" else "present"} " +
                    "windows=${snapshot.windows.joinToString { "${it.id}:${it.usedRatio}" }} " +
                    "message=${snapshot.message}",
            )
        } finally {
            accountKey?.let { client.logout(it) }
        }
    }
}
