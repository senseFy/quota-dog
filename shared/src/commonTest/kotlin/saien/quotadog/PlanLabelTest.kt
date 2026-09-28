package saien.quotadog

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanLabelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun formatsCursorPlans() {
        assertEquals("Ultra", formatCursorPlanLabel("ultra"))
        assertEquals("Pro", formatCursorPlanLabel("pro"))
        assertEquals("Pro+", formatCursorPlanLabel("pro_plus"))
        assertEquals("Pro+", formatCursorPlanLabel("Pro+"))
        assertEquals("Pro Student", formatCursorPlanLabel("pro_student"))
        assertEquals("Hobby", formatCursorPlanLabel("free"))
        assertEquals("Trial", formatCursorPlanLabel("free_trial"))
        assertEquals("Teams", formatCursorPlanLabel("teams"))
        assertEquals("Start", formatCursorPlanLabel("start"))
        assertNull(formatCursorPlanLabel("  "))
        assertNull(formatCursorPlanLabel(null))
    }

    @Test
    fun formatsCodexPlans() {
        assertEquals("Plus", formatCodexPlanLabel("plus"))
        assertEquals("Pro 5x", formatCodexPlanLabel("prolite"))
        assertEquals("Pro 20x", formatCodexPlanLabel("pro"))
        assertEquals("Pro Max", formatCodexPlanLabel("promax"))
        assertEquals("Team", formatCodexPlanLabel("team"))
        assertEquals("Business", formatCodexPlanLabel("self_serve_business_usage_based"))
        assertEquals("Business Pro", formatCodexPlanLabel("self_serve_business_prolite"))
        assertEquals("Enterprise", formatCodexPlanLabel("enterprise"))
        assertEquals("Edu Plus", formatCodexPlanLabel("edu_plus"))
        assertEquals("Free", formatCodexPlanLabel("free_workspace"))
    }

    @Test
    fun shortensDroidProductNamesToTheTier() {
        assertEquals("Pro", formatDroidPlanLabel("Factory Pro Annual"))
        assertEquals("Plus", formatDroidPlanLabel("Factory Plus Monthly"))
        assertEquals("Max", formatDroidPlanLabel("Factory Max"))
        assertEquals("Team", formatDroidPlanLabel("Team"))
        assertEquals("Enterprise", formatDroidPlanLabel("enterprise"))

        val stored = snapshot(ProviderId.DROID, planLabel = "Factory Pro Annual")
        assertEquals("Pro", stored.displayPlanLabel())
    }

    @Test
    fun formatsGrokPlans() {
        assertEquals("SuperGrok", formatGrokPlanLabel("SuperGrok"))
        assertEquals("SuperGrok Heavy", formatGrokPlanLabel("supergrok_heavy"))
        assertEquals("SuperGrok Pro", formatGrokPlanLabel("SuperGrok Pro"))
        assertNull(formatGrokPlanLabel(" "))
    }

    @Test
    fun formatsOtherSubscriptionPlans() {
        assertEquals("Max", formatSubscriptionPlanLabel("max"))
        assertEquals("Max", formatSubscriptionPlanLabel("Max"))
        assertEquals("Pro", formatSubscriptionPlanLabel("Pro"))
        assertEquals("Plus", formatSubscriptionPlanLabel("plus"))
        assertEquals("SuperGrok", formatSubscriptionPlanLabel("SuperGrok"))
        assertEquals("SuperGrok Pro", formatSubscriptionPlanLabel("super_grok_pro"))
        assertEquals("Google AI Pro", formatSubscriptionPlanLabel("Google AI Pro"))
        assertNull(formatSubscriptionPlanLabel("   "))
    }

    @Test
    fun readsPlanFromOlderSnapshotMessages() {
        val codex = snapshot(ProviderId.CODEX, message = "Plan: pro")
        assertEquals("Pro 20x", codex.displayPlanLabel())

        val cursor = snapshot(ProviderId.CURSOR, message = "Plan: Pro Plus · Source: Cursor")
        assertEquals("Pro+", cursor.displayPlanLabel())

        val droid = snapshot(ProviderId.DROID, message = "Plan: Max · Extra usage balance: $10")
        assertEquals("Max", droid.displayPlanLabel())

        val stored = snapshot(ProviderId.CURSOR, planLabel = "Ultra", message = "Source: Cursor")
        assertEquals("Ultra", stored.displayPlanLabel())

        assertNull(snapshot(ProviderId.CLAUDE_CODE, message = "Source: Claude").displayPlanLabel())
    }

    @Test
    fun decodesSnapshotsThatPredatePlanLabel() {
        val decoded = json.decodeFromString(
            ProviderUsageSnapshot.serializer(),
            """
            {
              "providerId": "CODEX",
              "authState": "LoggedIn",
              "windows": [],
              "collectedAt": "1970-01-01T00:00:01Z",
              "message": "Plan: prolite"
            }
            """.trimIndent(),
        )
        assertNull(decoded.planLabel)
        assertEquals("Pro 5x", decoded.displayPlanLabel())
    }

    @Test
    fun distinguishesPlanCodesFromAccountIds() {
        assertTrue("ultra".looksLikePlanCode())
        assertTrue("pro_student".looksLikePlanCode())
        assertTrue("pro+".looksLikePlanCode())
        assertFalse("user@cursor.com".looksLikePlanCode())
        assertFalse("auth0|user_123".looksLikePlanCode())
        assertFalse("6f3c2a10-1b2c-4d5e-8f90-aabbccddeeff".looksLikePlanCode())
    }

    private fun snapshot(
        providerId: ProviderId,
        message: String? = null,
        planLabel: String? = null,
    ): ProviderUsageSnapshot {
        return ProviderUsageSnapshot(
            providerId = providerId,
            authState = AuthState.LoggedIn,
            windows = emptyList(),
            collectedAt = Instant.fromEpochSeconds(1),
            message = message,
            planLabel = planLabel,
        )
    }
}
