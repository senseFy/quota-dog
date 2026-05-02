package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
