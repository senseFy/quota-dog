package saien.quotadog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live desktop smoke test against the local Cursor app DB or `cursor-agent` keychain.
 * Skips automatically when neither source is signed in on this machine.
 */
class CursorLiveImportTest {
    @Test
    fun importsLocalCredentialsAndFetchesUsageWindows() = runBlocking {
        val token = runCatching { loadCursorCredentialsFromLocalApp() }.getOrElse { error ->
            if (error is ProviderException && error.state == AuthState.NotConfigured) {
                println("SKIP live Cursor import: ${error.message}")
                return@runBlocking
            }
            throw error
        }

        val client = QuotaDogClient()
        var accountKey: AccountKey? = null
        try {
            accountKey = client.importCursorAccount()
            assertTrue(accountKey.providerId == ProviderId.CURSOR)
            assertTrue(accountKey.accountId.isNotBlank())

            val snapshot = runCatching { client.refreshUsage(accountKey) }.getOrElse { error ->
                fail("refreshUsage failed: ${error.message}")
            }

            assertTrue(snapshot.windows.isNotEmpty(), "expected quota windows")
            println(
                "Cursor live OK account=${if (snapshot.accountEmail.isNullOrBlank()) "missing" else "present"} " +
                    "windows=${snapshot.windows.joinToString { "${it.id}:${it.usedRatio}" }} " +
                    "message=${snapshot.message}",
            )
        } finally {
            accountKey?.let { client.logout(it) }
        }
    }
}
