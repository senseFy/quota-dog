package saien.quotadog

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AmpUsageTest {

    @Test
    fun parsesPaidTierFreeAllowanceAndCredits() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        val snapshot = AmpUsageFetcher.parseBalanceJson(
            """
            {
              "ok": true,
              "result": {
                "displayText": "Signed in as user@example.com (alice)\nAmp Free: 100% remaining today (resets daily) - https://ampcode.com/settings\nAmp Megawatt Tier: agent usage ${'$'}15.00 of ${'$'}20.00 remaining (75%), orb usage 600h of 750h a1.small orb hours remaining (80%) - period 2026-10-30 to 2026-11-30, resets upon renewal in 8 days\nIndividual credits: ${'$'}14.41 remaining (set up auto-reload to avoid running out) - https://ampcode.com/settings"
              }
            }
            """.trimIndent(),
            now = now,
        )

        assertEquals("user@example.com", snapshot.email)
        assertEquals("Megawatt", snapshot.planName)
        assertEquals(14.41, snapshot.individualCreditsDollars ?: -1.0, absoluteTolerance = 1e-6)
        assertEquals(3, snapshot.windows.size)

        val agent = snapshot.windows[0]
        assertEquals("amp-agent", agent.id)
        assertEquals(0.25, agent.usedRatio, absoluteTolerance = 1e-9)
        assertEquals(Instant.parse("2026-11-30T00:00:00Z"), agent.resetsAt)
        assertEquals(31L * 24L * 60L * 60L, agent.durationSeconds)

        val orb = snapshot.windows[1]
        assertEquals("amp-orb", orb.id)
        assertEquals(0.20, orb.usedRatio, absoluteTolerance = 1e-9)
        assertEquals(Instant.parse("2026-11-30T00:00:00Z"), orb.resetsAt)

        val free = snapshot.windows[2]
        assertEquals("amp-free", free.id)
        assertEquals(0.0, free.usedRatio, absoluteTolerance = 1e-9)
    }

    @Test
    fun parsesDollarFreeGrantWithHourlyReplenishment() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        val snapshot = AmpUsageFetcher.parseDisplayText(
            """
            Signed in as dev@example.com
            Amp Free: ${'$'}7.50/${'$'}10.00 remaining (replenishes +${'$'}0.50/hour) - https://ampcode.com/settings
            """.trimIndent(),
            now = now,
        )

        assertEquals("dev@example.com", snapshot.email)
        assertEquals("Free", snapshot.planName)
        assertEquals(1, snapshot.windows.size)
        val free = snapshot.windows.first()
        assertEquals("amp-free", free.id)
        assertEquals(0.25, free.usedRatio, absoluteTolerance = 1e-9)
        assertEquals(Instant.parse("2026-11-21T17:00:00Z"), free.resetsAt)
        assertEquals(20L * 3600L, free.durationSeconds)
    }

    @Test
    fun fallsBackToCreditsWindowForPayAsYouGoAccounts() {
        val snapshot = AmpUsageFetcher.parseDisplayText(
            """
            Signed in as payg@example.com
            Individual credits: ${'$'}12.50 remaining - https://ampcode.com/settings
            """.trimIndent(),
        )

        assertEquals(1, snapshot.windows.size)
        assertEquals("amp-credits", snapshot.windows[0].id)
        assertEquals(0.0, snapshot.windows[0].usedRatio, absoluteTolerance = 1e-9)
        assertNotNull(snapshot.individualCreditsDollars)
    }

    @Test
    fun rejectsRpcErrorsAndMissingDisplayText() {
        assertFailsWith<ProviderException> {
            AmpUsageFetcher.parseBalanceJson("""{"ok":false,"error":{"message":"Unauthorized"}}""")
        }
        assertFailsWith<ProviderException> {
            AmpUsageFetcher.parseBalanceJson("""{"ok":true,"result":{}}""")
        }
    }
}
