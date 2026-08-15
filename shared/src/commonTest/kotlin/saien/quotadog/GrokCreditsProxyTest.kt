package saien.quotadog

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GrokCreditsProxyTest {
    private val now = Instant.parse("2026-08-15T00:00:00Z")

    @Test
    fun parsesCreditUsagePercentAndWeeklyPeriod() {
        val snapshot = GrokCreditsProxyFetcher.parseCreditsJson(
            """
            {
              "config": {
                "creditUsagePercent": 42.5,
                "currentPeriod": {
                  "type": "USAGE_PERIOD_TYPE_WEEKLY",
                  "end": "2026-08-22T00:00:00Z"
                }
              },
              "subscriptionTier": "SuperGrok"
            }
            """.trimIndent(),
            now = now,
        )
        assertEquals(42.5, snapshot.usedPercent)
        assertEquals(Instant.parse("2026-08-22T00:00:00Z"), snapshot.resetsAt)
        assertEquals("USAGE_PERIOD_TYPE_WEEKLY", snapshot.periodType)
        assertEquals("SuperGrok", snapshot.subscriptionTier)
    }

    @Test
    fun fallsBackToOnDemandRatio() {
        val snapshot = GrokCreditsProxyFetcher.parseCreditsJson(
            """
            {
              "config": {
                "onDemandCap": { "val": 200 },
                "onDemandUsed": { "val": 50 },
                "billingPeriodEnd": "2026-09-01T00:00:00Z"
              }
            }
            """.trimIndent(),
            now = now,
        )
        assertEquals(25.0, snapshot.usedPercent)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), snapshot.resetsAt)
        assertNull(snapshot.subscriptionTier)
    }

    @Test
    fun treatsPeriodWithoutUsageAsZero() {
        val snapshot = GrokCreditsProxyFetcher.parseCreditsJson(
            """
            {
              "config": {
                "currentPeriod": { "end": "2026-08-22T00:00:00Z" }
              }
            }
            """.trimIndent(),
            now = now,
        )
        assertEquals(0.0, snapshot.usedPercent)
        assertEquals(Instant.parse("2026-08-22T00:00:00Z"), snapshot.resetsAt)
    }

    @Test
    fun rejectsUnauthorizedBillingResponses() {
        assertFailsWith<ProviderException> {
            GrokCreditsProxyFetcher.parseResponse(statusCode = 401, body = "")
        }
    }

    @Test
    fun rejectsMissingConfig() {
        assertFailsWith<ProviderException> {
            GrokCreditsProxyFetcher.parseCreditsJson("{}", now = now)
        }
    }
}
