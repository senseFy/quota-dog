package saien.quotadog

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaExhaustionTest {
    @Test
    fun anySpentWindowBlocksCodexGrokAndDevin() {
        assertTrue(spent(ProviderId.CODEX, "primary" to 0.4, "secondary" to 1.0))
        assertTrue(spent(ProviderId.GROK, "credits" to 1.0))
        assertTrue(spent(ProviderId.DEVIN, "devin-daily" to 1.0, "devin-weekly" to 0.2))
        assertFalse(spent(ProviderId.CODEX, "primary" to 0.99))
        assertFalse(spent(ProviderId.CODEX))
    }

    @Test
    fun claudeModelWeeklyCapsLeaveTheAccountUsable() {
        assertFalse(spent(ProviderId.CLAUDE_CODE, "five_hour" to 0.3, "seven_day_opus" to 1.0))
        assertFalse(spent(ProviderId.CLAUDE_CODE, "seven_day_sonnet" to 1.0))
        assertTrue(spent(ProviderId.CLAUDE_CODE, "five_hour" to 1.0, "seven_day" to 0.5))
        assertTrue(spent(ProviderId.CLAUDE_CODE, "seven_day" to 1.0, "seven_day_opus" to 0.2))
    }

    @Test
    fun cursorOnDemandBudgetKeepsTheAccountUsable() {
        assertFalse(spent(ProviderId.CURSOR, "plan-usage" to 1.0, "on-demand" to 0.4))
        assertTrue(spent(ProviderId.CURSOR, "plan-usage" to 1.0, "on-demand" to 1.0))
        assertTrue(spent(ProviderId.CURSOR, "plan-usage" to 1.0))
        assertFalse(spent(ProviderId.CURSOR, "plan-usage" to 0.5, "on-demand" to 1.0))
    }

    @Test
    fun cursorAutoAndApiPoolsDoNotBlockTheAccount() {
        assertFalse(spent(ProviderId.CURSOR, "plan-usage" to 0.6, "cursor-api" to 1.0))
        assertFalse(spent(ProviderId.CURSOR, "plan-usage" to 0.8, "cursor-auto" to 1.0))
    }

    @Test
    fun antigravityIsSpentOnlyWhenEveryGroupIs() {
        assertFalse(
            spent(
                ProviderId.ANTIGRAVITY,
                "antigravity-gemini-session" to 1.0,
                "antigravity-claude-gpt-session" to 0.2,
            ),
        )
        assertTrue(
            spent(
                ProviderId.ANTIGRAVITY,
                "antigravity-gemini-session" to 1.0,
                "antigravity-gemini-weekly" to 0.4,
                "antigravity-claude-gpt-session" to 0.1,
                "antigravity-claude-gpt-weekly" to 1.0,
            ),
        )
        assertFalse(spent(ProviderId.ANTIGRAVITY, "model-gemini-2.5-pro" to 1.0, "model-claude-sonnet-4" to 0.5))
        assertTrue(spent(ProviderId.ANTIGRAVITY, "model-gemini-2.5-pro" to 1.0, "model-claude-sonnet-4" to 1.0))
    }

    @Test
    fun droidCorePoolKeepsTheAccountUsable() {
        assertTrue(spent(ProviderId.DROID, "droid-standard-5h" to 1.0, "droid-standard-weekly" to 0.3))
        assertFalse(spent(ProviderId.DROID, "droid-standard-weekly" to 1.0, "droid-core-5h" to 0.2))
        assertTrue(spent(ProviderId.DROID, "droid-standard-weekly" to 1.0, "droid-core-weekly" to 1.0))
        assertFalse(spent(ProviderId.DROID, "droid-standard" to 0.5, "droid-premium" to 1.0))
    }

    @Test
    fun spentWindowResetIsTheEarliestResetTheSnapshotPredates() {
        val account = account(
            ProviderId.CODEX,
            listOf(
                window("primary", 1.0, resetsAt = 5_000),
                window("secondary", 1.0, resetsAt = 3_000),
                window("other", 0.5, resetsAt = 2_000),
            ),
            collectedAt = 1_000,
        )

        assertEquals(Instant.fromEpochMilliseconds(3_000), account.spentWindowResetAt())
    }

    @Test
    fun spentWindowResetSkipsResetsTheSnapshotAlreadyCovers() {
        // A refresh after the reset that still reads 100% must not schedule another one.
        val refreshedAfterReset = account(
            ProviderId.CODEX,
            listOf(window("primary", 1.0, resetsAt = 3_000)),
            collectedAt = 4_000,
        )

        assertNull(refreshedAfterReset.spentWindowResetAt())
    }

    @Test
    fun spentWindowResetNeedsASpentWindowWithAResetTime() {
        assertNull(account(ProviderId.CODEX, listOf(window("primary", 0.5, resetsAt = 9_000))).spentWindowResetAt())
        assertNull(account(ProviderId.CODEX, listOf(window("primary", 1.0))).spentWindowResetAt())
        assertNull(account(ProviderId.CODEX, emptyList()).spentWindowResetAt())
    }

    private fun spent(provider: ProviderId, vararg windows: Pair<String, Double>): Boolean {
        return account(provider, windows.map { (id, used) -> window(id, used) }).isQuotaExhausted()
    }

    private fun account(provider: ProviderId, windows: List<UsageWindow>, collectedAt: Long = 0): AccountUiState {
        val snapshot = if (windows.isEmpty()) {
            null
        } else {
            ProviderUsageSnapshot(
                providerId = provider,
                authState = AuthState.LoggedIn,
                windows = windows,
                collectedAt = Instant.fromEpochMilliseconds(collectedAt),
            )
        }
        return AccountUiState(
            accountKey = AccountKey(provider, "user@example.com"),
            added = true,
            authState = AuthState.LoggedIn,
            snapshot = snapshot,
        )
    }

    private fun window(id: String, usedRatio: Double, resetsAt: Long? = null): UsageWindow {
        return UsageWindow(
            id = id,
            label = id,
            usedRatio = usedRatio,
            resetsAt = resetsAt?.let(Instant::fromEpochMilliseconds),
        )
    }
}
