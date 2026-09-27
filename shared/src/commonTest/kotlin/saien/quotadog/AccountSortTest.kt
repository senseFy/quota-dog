package saien.quotadog

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountSortTest {
    @Test
    fun nameSortIsAlphabeticalAcrossProviders() {
        val accounts = listOf(
            account(ProviderId.GROK, "z@example.com", email = "Zed@example.com"),
            account(ProviderId.CODEX, "a@example.com", email = "amy@example.com"),
            account(ProviderId.CLAUDE_CODE, "m@example.com", email = "Mia@example.com"),
        )

        val sorted = accounts.sortedAccounts(AccountSortMode.Name).map { it.accountKey.accountId }

        assertEquals(listOf("a@example.com", "m@example.com", "z@example.com"), sorted)
        val reversed = accounts.sortedAccounts(AccountSortMode.Name, reversed = true).map { it.accountKey.accountId }
        assertEquals(listOf("z@example.com", "m@example.com", "a@example.com"), reversed)
    }

    @Test
    fun refreshSortPutsNewestFirstAndNeverRefreshedLast() {
        val accounts = listOf(
            account(ProviderId.CODEX, "old", email = "old@example.com", refreshedAt = 100),
            account(ProviderId.GROK, "new", email = "new@example.com", refreshedAt = 500),
            account(ProviderId.CLAUDE_CODE, "none"),
        )

        val sorted = accounts.sortedAccounts(AccountSortMode.RefreshTime).map { it.accountKey.accountId }

        assertEquals(listOf("new", "old", "none"), sorted)
        val reversed = accounts.sortedAccounts(AccountSortMode.RefreshTime, reversed = true)
            .map { it.accountKey.accountId }
        assertEquals(listOf("old", "new", "none"), reversed)
    }

    @Test
    fun manualOrderKeepsStoredKeysAndAppendsNewAccountsByName() {
        val stored = listOf(
            AccountKey(ProviderId.CODEX, "c"),
            AccountKey(ProviderId.CODEX, "a"),
            AccountKey(ProviderId.GROK, "deleted"),
        )
        val accounts = listOf(
            account(ProviderId.CODEX, "a", email = "a@example.com"),
            account(ProviderId.CODEX, "c", email = "c@example.com"),
            account(ProviderId.CLAUDE_CODE, "d", email = "d@example.com"),
            account(ProviderId.GROK, "b", email = "b@example.com"),
        )

        val sorted = accounts.sortedAccounts(AccountSortMode.Manual, stored).map { it.accountKey.accountId }

        assertEquals(listOf("c", "a", "b", "d"), sorted)
        val reversed = accounts.sortedAccounts(AccountSortMode.Manual, stored, reversed = true)
            .map { it.accountKey.accountId }
        assertEquals(listOf("d", "b", "a", "c"), reversed)
    }

    @Test
    fun reorderSwapsVisibleAccountsWithoutMovingHiddenOnes() {
        val codexA = AccountKey(ProviderId.CODEX, "a")
        val claude = AccountKey(ProviderId.CLAUDE_CODE, "b")
        val codexC = AccountKey(ProviderId.CODEX, "c")
        val full = listOf(codexA, claude, codexC)

        val updated = reorderVisibleAccount(full, listOf(codexA, codexC), codexC, -1)

        assertEquals(listOf(codexC, claude, codexA), updated)
    }

    @Test
    fun reversedManualMoveUsesTheOnScreenOrder() {
        val codexA = AccountKey(ProviderId.CODEX, "a")
        val claude = AccountKey(ProviderId.CLAUDE_CODE, "b")
        val codexC = AccountKey(ProviderId.CODEX, "c")
        val accounts = listOf(codexA, claude, codexC).map { key ->
            account(key.providerId, key.accountId, email = "${key.accountId}@example.com")
        }

        val updated = reorderDisplayedManualOrder(
            accounts = accounts,
            stored = listOf(codexA, claude, codexC),
            reversed = true,
            provider = ProviderId.CODEX,
            key = codexC,
            delta = 1,
        )

        assertEquals(listOf(codexC, claude, codexA), updated)
    }

    @Test
    fun reorderAtTheEndsLeavesTheOrderAlone() {
        val order = listOf(AccountKey(ProviderId.CODEX, "a"), AccountKey(ProviderId.GROK, "b"))

        assertEquals(order, reorderVisibleAccount(order, order, order.first(), -1))
        assertEquals(order, reorderVisibleAccount(order, order, order.last(), 1))
    }

    @Test
    fun manualOrderRoundTripsAccountIds() {
        val order = listOf(
            AccountKey(ProviderId.GROK, "user\"quote\"@example.com"),
            AccountKey(ProviderId.CODEX, "line\nbreak"),
        )

        assertEquals(order, decodeAccountOrder(encodeAccountOrder(order)))
        assertEquals(emptyList(), decodeAccountOrder("  "))
        assertNull(decodeAccountOrder("{"))
    }

    @Test
    fun decodeSkipsUnknownProviders() {
        val raw = """{"accounts":[{"providerId":"NOT_A_PROVIDER","accountId":"x"},{"providerId":"DROID","accountId":"y"}]}"""

        assertEquals(listOf(AccountKey(ProviderId.DROID, "y")), decodeAccountOrder(raw))
    }

    private fun account(
        provider: ProviderId,
        id: String,
        email: String? = null,
        refreshedAt: Long? = null,
    ): AccountUiState {
        val snapshot = if (email == null && refreshedAt == null) {
            null
        } else {
            ProviderUsageSnapshot(
                providerId = provider,
                authState = AuthState.LoggedIn,
                windows = emptyList(),
                collectedAt = Instant.fromEpochMilliseconds(refreshedAt ?: 0L),
                accountEmail = email,
            )
        }
        return AccountUiState(
            accountKey = AccountKey(provider, id),
            added = true,
            authState = AuthState.LoggedIn,
            snapshot = snapshot,
        )
    }
}
