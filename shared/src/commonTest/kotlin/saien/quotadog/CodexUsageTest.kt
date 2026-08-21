package saien.quotadog

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexUsageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesUsageWindowsAndResetCount() {
        val parsed = CodexUsageParser.parseUsage(
            """
            {
              "plan_type": "plus",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 27,
                  "limit_window_seconds": 18000,
                  "reset_at": 1782770922
                },
                "secondary_window": {
                  "used_percent": 4,
                  "limit_window_seconds": 604800,
                  "reset_at": 1783357722
                }
              },
              "rate_limit_reset_credits": {
                "available_count": 2
              }
            }
            """.trimIndent(),
        )

        assertEquals("plus", parsed.planType)
        assertEquals(2, parsed.resetCreditsAvailable)
        assertEquals(listOf("primary", "secondary"), parsed.windows.map { it.id })
        assertEquals(0.27, parsed.windows[0].usedRatio, 0.000001)
        assertEquals(18_000L, parsed.windows[0].durationSeconds)
        assertEquals(Instant.fromEpochSeconds(1_782_770_922), parsed.windows[0].resetsAt)
    }

    @Test
    fun treatsMissingResetCreditsAsUnknown() {
        val parsed = CodexUsageParser.parseUsage(
            """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 1,
                  "limit_window_seconds": 18000,
                  "reset_at": 1782770922
                }
              }
            }
            """.trimIndent(),
        )

        assertNull(parsed.resetCreditsAvailable)
        assertEquals(0.01, parsed.windows.single().usedRatio, 0.000001)
    }

    @Test
    fun parsesResetCreditDetailsAndIgnoresRedeemedRows() {
        val details = CodexUsageParser.parseResetCredits(
            """
            {
              "available_count": 2,
              "credits": [
                {
                  "id": "RateLimitResetCredit_a",
                  "reset_type": "codex_rate_limits",
                  "status": "available",
                  "granted_at": "2026-06-17T00:00:00Z",
                  "expires_at": "2026-07-17T00:00:00Z",
                  "title": "Full reset (Weekly + 5 hr)",
                  "description": "Ready to redeem"
                },
                {
                  "id": "RateLimitResetCredit_b",
                  "status": "redeemed",
                  "granted_at": "2026-05-01T00:00:00Z",
                  "expires_at": "2026-06-01T00:00:00Z",
                  "title": "Used reset"
                },
                {
                  "id": "RateLimitResetCredit_c",
                  "status": "available",
                  "granted_at": "2026-06-20T00:00:00Z",
                  "expires_at": "2026-07-20T00:00:00Z",
                  "title": "Referral reset"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, details.availableCount)
        assertEquals(3, details.credits.size)
        val merged = CodexUsageParser.mergeResetCredits(2, details)
        assertEquals(2, merged.first)
        assertEquals(
            listOf("RateLimitResetCredit_a", "RateLimitResetCredit_c"),
            merged.second.map { it.id },
        )
        assertEquals("Full reset (Weekly + 5 hr)", merged.second[0].title)
        assertEquals(Instant.parse("2026-07-17T00:00:00Z"), merged.second[0].expiresAt)
    }

    @Test
    fun prefersDetailCountOverUsageCount() {
        val details = CodexResetCreditsDetails(
            availableCount = 0,
            credits = emptyList(),
        )
        val merged = CodexUsageParser.mergeResetCredits(2, details)
        assertEquals(0, merged.first)
        assertEquals(emptyList(), merged.second)
    }

    @Test
    fun keepsUsageCountWhenDetailsAreMissing() {
        val merged = CodexUsageParser.mergeResetCredits(3, null)
        assertEquals(3, merged.first)
        assertEquals(emptyList(), merged.second)
    }

    @Test
    fun buildsResetSummarySortedByExpiry() {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val snapshot = ProviderUsageSnapshot(
            providerId = ProviderId.CODEX,
            authState = AuthState.LoggedIn,
            windows = emptyList(),
            collectedAt = now,
            resetCreditsAvailable = 2,
            resetCredits = listOf(
                CodexResetCredit(
                    id = "later",
                    status = "available",
                    title = "Later",
                    expiresAt = Instant.parse("2026-07-28T00:00:00Z"),
                ),
                CodexResetCredit(
                    id = "sooner",
                    status = "available",
                    title = "Sooner",
                    expiresAt = Instant.parse("2026-07-03T12:00:00Z"),
                ),
            ),
        )

        val summary = snapshot.codexResetSummary(now)!!
        assertEquals(2, summary.availableCount)
        assertEquals(listOf("sooner", "later"), summary.credits.map { it.id })
        assertEquals(Instant.parse("2026-07-03T12:00:00Z"), summary.nearestExpiresAt)
        assertTrue(summary.expiringSoon)
        assertEquals("2 resets available", summary.countLabel)
        assertEquals("2 resets · 2d12h", summary.compactLabel(now))
    }

    @Test
    fun hidesSummaryWhenNoResetsAreAvailable() {
        val snapshot = ProviderUsageSnapshot(
            providerId = ProviderId.CODEX,
            authState = AuthState.LoggedIn,
            windows = emptyList(),
            collectedAt = Instant.fromEpochSeconds(1),
            resetCreditsAvailable = 0,
        )
        assertNull(snapshot.codexResetSummary())
    }

    @Test
    fun doesNotMarkResetsExpiringSoonWhenExpiryIsFarAway() {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val snapshot = ProviderUsageSnapshot(
            providerId = ProviderId.CODEX,
            authState = AuthState.LoggedIn,
            windows = emptyList(),
            collectedAt = now,
            resetCreditsAvailable = 1,
            resetCredits = listOf(
                CodexResetCredit(
                    id = "later",
                    status = "available",
                    expiresAt = Instant.parse("2026-07-20T00:00:00Z"),
                ),
            ),
        )
        val summary = snapshot.codexResetSummary(now)!!
        assertFalse(summary.expiringSoon)
        assertEquals("1 reset available", summary.countLabel)
        assertEquals("1 reset · 19d", summary.compactLabel(now))
    }

    @Test
    fun loadsLegacySnapshotWithoutResetCredits() {
        val encoded = """
            {"providerId":"CODEX","authState":"LoggedIn","windows":[],"collectedAt":"1970-01-01T00:00:01Z"}
        """.trimIndent()
        val snapshot = json.decodeFromString(ProviderUsageSnapshot.serializer(), encoded)
        assertNull(snapshot.resetCreditsAvailable)
        assertEquals(emptyList(), snapshot.resetCredits)
    }
}
