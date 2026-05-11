package saien.quotadog

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageParserTest {
    @Test
    fun normalizesPercentAndRatioValues() {
        assertEquals(0.33, normalizeUtilization(33.0))
        assertEquals(0.33, normalizeUtilization(0.33))
        assertEquals(1.0, normalizeUtilization(120.0))
        assertEquals(0.0, normalizeUtilization(-1.0))
    }

    @Test
    fun preservesControlledProviderMessages() {
        val error = ProviderException(AuthState.RateLimited, "Codex usage was rate limited", 429)

        assertEquals("Codex usage was rate limited", safeUserMessage(error))
    }

    @Test
    fun hidesUnexpectedThrowableMessages() {
        val error = IllegalStateException("raw provider response should stay hidden")

        assertEquals("Refresh failed. Please try again.", safeUserMessage(error, "Refresh failed. Please try again."))
    }

    @Test
    fun projectsFiveHourUsageFromCurrentPace() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val window = UsageWindow(
            id = "five_hour",
            label = "5-hour window",
            usedRatio = 0.30,
            resetsAt = now.plusTestSeconds(2L * 60L * 60L + 30L * 60L),
            durationSeconds = 5L * 60L * 60L
        )

        assertEquals(0.60, window.projectedUsedRatio(now)!!, 0.000001)
    }

    @Test
    fun projectsWeeklyUsageFromCurrentPace() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val window = UsageWindow(
            id = "seven_day",
            label = "7-day window",
            usedRatio = 0.20,
            resetsAt = now.plusTestSeconds(5L * 24L * 60L * 60L + 6L * 60L * 60L),
        )

        assertEquals(0.80, window.projectedUsedRatio(now)!!, 0.000001)
    }

    @Test
    fun skipsProjectionWithoutWindowTiming() {
        val window = UsageWindow(
            id = "custom",
            label = "Custom window",
            usedRatio = 0.20,
            resetsAt = null,
        )

        assertNull(window.projectedUsedRatio(Instant.fromEpochSeconds(1_700_000_000)))
    }
}

private fun Instant.plusTestSeconds(seconds: Long): Instant {
    return Instant.fromEpochMilliseconds(toEpochMilliseconds() + seconds * 1_000L)
}
