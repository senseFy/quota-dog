package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonObject

class DroidUsageTest {
    private fun billingLimitsJson(
        standard: String = """
            "standard": {
              "fiveHour": { "usedPercent": 42.5, "windowEnd": "2026-03-01T10:00:00Z", "secondsRemaining": 9000 },
              "weekly": { "usedPercent": 30, "windowEnd": "2026-03-08T00:00:00Z", "secondsRemaining": 500000 },
              "monthly": { "usedPercent": 12, "windowEnd": "2026-04-01T00:00:00Z", "secondsRemaining": 2000000 }
            }
        """.trimIndent(),
        core: String = """
            "core": {
              "fiveHour": { "usedPercent": 0, "windowEnd": null, "secondsRemaining": 0 },
              "weekly": { "usedPercent": 80, "windowEnd": "2026-03-08T00:00:00Z", "secondsRemaining": 500000 },
              "monthly": { "usedPercent": 66.6, "windowEnd": "2026-04-01T00:00:00Z", "secondsRemaining": 2000000 }
            }
        """.trimIndent(),
        extra: String = """
            "usesTokenRateLimitsBilling": true,
            "extraUsageBalanceCents": 12345
        """.trimIndent(),
    ): String {
        val pools = listOf(standard, core).filter { it.isNotBlank() }.joinToString(",")
        return """{ $extra, "limits": { $pools } }"""
    }

    @Test
    fun parsesBillingLimitsIntoStandardAndCoreWindows() {
        val billing = DroidUsageFetcher.parseBillingLimitsJson(billingLimitsJson())

        assertTrue(billing.usesTokenRateLimitsBilling)
        assertEquals(12345L, billing.extraUsageBalanceCents)
        assertEquals(42.5, billing.standard?.get("fiveHour")?.usedPercent)
        assertEquals(Instant.parse("2026-03-08T00:00:00Z"), billing.standard?.get("weekly")?.windowEnd)

        val windows = DroidUsageFetcher.windowsFromBillingLimits(billing)
        // 3 standard + weekly/monthly core (empty core fiveHour is hidden).
        assertEquals(5, windows.size)

        val fiveHour = windows.first { it.id == "droid-standard-5h" }
        assertEquals("Standard 5-hour", fiveHour.label)
        assertEquals(0.425, fiveHour.usedRatio, 0.000001)
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), fiveHour.resetsAt)
        assertEquals(5L * 60L * 60L, fiveHour.durationSeconds)

        val coreWeekly = windows.first { it.id == "droid-core-weekly" }
        assertEquals(0.8, coreWeekly.usedRatio, 0.000001)
    }

    @Test
    fun derivesResetFromSecondsRemainingWhenWindowEndMissing() {
        val billing = DroidUsageFetcher.parseBillingLimitsJson(
            billingLimitsJson(
                standard = """
                    "standard": {
                      "weekly": { "usedPercent": 10, "windowEnd": null, "secondsRemaining": 3600 }
                    }
                """.trimIndent(),
                core = "",
            )
        )

        val weekly = DroidUsageFetcher.windowsFromBillingLimits(billing)
            .single { it.id == "droid-standard-weekly" }
        val resetsAt = weekly.resetsAt
        assertNotNull(resetsAt)
        val deltaSeconds = resetsAt.epochSeconds - kotlinx.datetime.Clock.System.now().epochSeconds
        assertTrue(deltaSeconds in 3500..3700, "expected ~3600s until reset, got $deltaSeconds")
    }

    @Test
    fun ignoresBillingLimitsWhenFlagDisabled() {
        val billing = DroidUsageFetcher.parseBillingLimitsJson(
            billingLimitsJson(extra = """"usesTokenRateLimitsBilling": false""")
        )
        assertTrue(!billing.usesTokenRateLimitsBilling)
        // windowsFromBillingLimits still maps whatever pools exist; the fetcher
        // is responsible for falling back when the flag is off.
        assertTrue(DroidUsageFetcher.windowsFromBillingLimits(billing).isNotEmpty())
    }

    @Test
    fun rejectsNonJsonBillingLimits() {
        assertFailsWith<ProviderException> {
            DroidUsageFetcher.parseBillingLimitsJson("not json")
        }
    }

    @Test
    fun mapsLegacySubscriptionUsage() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "usage": {
                "startDate": 1740787200000,
                "endDate": 1743379200000,
                "standard": { "usedRatio": 0.42 },
                "premium": { "usedRatio": 0.9, "totalAllowance": 1000000 }
              }
            }
            """.trimIndent()
        ).jsonObject

        val windows = DroidUsageFetcher.windowsFromSubscriptionUsage(root)
        assertEquals(2, windows.size)

        val standard = windows.first { it.id == "droid-standard" }
        assertEquals("Standard tokens", standard.label)
        assertEquals(0.42, standard.usedRatio, 0.000001)
        assertEquals(Instant.fromEpochMilliseconds(1743379200000), standard.resetsAt)
        assertEquals((1743379200000L - 1740787200000L) / 1000L, standard.durationSeconds)

        val premium = windows.first { it.id == "droid-premium" }
        assertEquals(0.9, premium.usedRatio, 0.000001)
    }

    @Test
    fun hidesPremiumWindowWithoutAllowance() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "usage": {
                "endDate": 1743379200000,
                "standard": { "usedRatio": 0.5 },
                "premium": { "usedRatio": 0.9, "totalAllowance": 0 }
              }
            }
            """.trimIndent()
        ).jsonObject

        val windows = DroidUsageFetcher.windowsFromSubscriptionUsage(root)
        assertEquals(listOf("droid-standard"), windows.map { it.id })
    }

    @Test
    fun clampsOutOfRangeRatios() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"usage": {"standard": {"usedRatio": 1.7}}}"""
        ).jsonObject
        assertEquals(1.0, DroidUsageFetcher.windowsFromSubscriptionUsage(root).single().usedRatio)
    }

    @Test
    fun rejectsSubscriptionUsageWithoutUsageObject() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement("""{"other": true}""").jsonObject
        assertFailsWith<ProviderException> {
            DroidUsageFetcher.windowsFromSubscriptionUsage(root)
        }
    }

    @Test
    fun parsesAuthMeIdentity() {
        val identity = DroidUsageFetcher.parseAuthMeJson(
            """
            {
              "user": { "email": "dev@factory.ai" },
              "organization": { "planName": "Pro" }
            }
            """.trimIndent()
        )
        assertEquals("dev@factory.ai", identity.email)
        assertEquals("Pro", identity.planLabel)
    }

    @Test
    fun fallsBackToSubscriptionPlanName() {
        val identity = DroidUsageFetcher.parseAuthMeJson(
            """
            {
              "userProfile": { "email": "dev@factory.ai" },
              "organization": {
                "subscription": { "orbSubscription": { "plan": { "name": "Team" } } }
              }
            }
            """.trimIndent()
        )
        assertEquals("dev@factory.ai", identity.email)
        assertEquals("Team", identity.planLabel)
    }

    @Test
    fun toleratesNonJsonAuthMe() {
        val identity = DroidUsageFetcher.parseAuthMeJson("not json")
        assertNull(identity.email)
        assertNull(identity.planLabel)
    }
}
