package saien.quotadog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Optional live path against the local Antigravity CLI keyring + usage API.
 * Skips cleanly when the CLI is not signed in (CI / machines without `agy`).
 */
class AntigravityStoreRefreshTest {
    @Test
    fun storeRefreshSavesSnapshotWhenCliCredentialsPresent() = runBlocking {
        val probe = runCatching { loadAntigravityCredentialsFromCli() }
        if (probe.exceptionOrNull() is ProviderException) {
            val state = (probe.exceptionOrNull() as ProviderException).state
            if (state == AuthState.NotConfigured) {
                println("SKIP Antigravity store refresh: CLI credentials not configured")
                return@runBlocking
            }
        }
        if (probe.isFailure) {
            throw probe.exceptionOrNull()!!
        }

        val client = QuotaDogClient()
        val store = QuotaDogStore(client = client)
        try {
            store.beginLoginAndWait(ProviderId.ANTIGRAVITY)
            val accounts = store.state.value.accounts.values.filter {
                it.providerId == ProviderId.ANTIGRAVITY && it.added && !it.accountKey.isPending
            }
            assertTrue(accounts.isNotEmpty(), "expected imported antigravity account")
            val account = accounts.first()
            store.refresh(account.accountKey)
            val after = store.state.value.accounts[account.accountKey]
            assertTrue(
                after?.authState == AuthState.LoggedIn,
                "auth=${after?.authState} msg=${after?.message}",
            )
            assertTrue((after?.snapshot?.windows?.size ?: 0) >= 1)
            store.delete(account.accountKey)
        } catch (t: Throwable) {
            store.state.value.accounts.keys
                .filter { it.providerId == ProviderId.ANTIGRAVITY }
                .forEach { runCatching { store.delete(it) } }
            throw t
        }
    }
}
