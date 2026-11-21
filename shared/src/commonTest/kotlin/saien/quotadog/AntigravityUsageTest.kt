package saien.quotadog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AntigravityUsageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesQuotaSummaryGroupsIntoWindows() {
        val root = json.parseToJsonElement(
            """
            {
              "groups": [
                {
                  "displayName": "Gemini Models",
                  "buckets": [
                    {
                      "bucketId": "gemini-weekly",
                      "displayName": "Weekly Limit",
                      "window": "weekly",
                      "resetTime": "2026-07-30T09:24:25Z",
                      "remainingFraction": 0.75
                    },
                    {
                      "bucketId": "gemini-5h",
                      "displayName": "Five Hour Limit",
                      "window": "5h",
                      "resetTime": "2026-07-23T14:24:25Z",
                      "remainingFraction": 1
                    }
                  ]
                },
                {
                  "displayName": "Claude and GPT models",
                  "buckets": [
                    {
                      "bucketId": "3p-weekly",
                      "displayName": "Weekly Limit",
                      "window": "weekly",
                      "resetTime": "2026-07-30T09:24:25Z",
                      "remainingFraction": 0.5
                    },
                    {
                      "bucketId": "3p-5h",
                      "displayName": "Five Hour Limit",
                      "window": "5h",
                      "resetTime": "2026-07-23T14:24:25Z",
                      "remainingFraction": 0.2
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        ).jsonObjectLike()

        val windows = AntigravityUsageFetcher.parseQuotaSummary(root)

        assertEquals(4, windows.size)
        assertEquals("antigravity-gemini-session", windows[0].id)
        assertEquals(0.0, windows[0].usedRatio, absoluteTolerance = 1e-9)
        assertEquals("antigravity-gemini-weekly", windows[1].id)
        assertEquals(0.25, windows[1].usedRatio, absoluteTolerance = 1e-9)
        assertEquals("antigravity-claude-gpt-session", windows[2].id)
        assertEquals(0.8, windows[2].usedRatio, absoluteTolerance = 1e-9)
        assertEquals("antigravity-claude-gpt-weekly", windows[3].id)
        assertEquals(0.5, windows[3].usedRatio, absoluteTolerance = 1e-9)
        assertTrue(windows[0].label.contains("Gemini"))
        assertTrue(windows[2].label.contains("Claude/GPT"))
    }

    @Test
    fun parsesAvailableModelsFallback() {
        val root = json.parseToJsonElement(
            """
            {
              "models": {
                "gemini-3-flash": {
                  "displayName": "Gemini 3 Flash",
                  "quotaInfo": {
                    "remainingFraction": 0.4,
                    "resetTime": "2026-07-23T14:24:26Z"
                  }
                },
                "claude-sonnet-4-5": {
                  "displayName": "Claude Sonnet 4.5",
                  "quotaInfo": {
                    "remainingFraction": 0.9,
                    "resetTime": "2026-07-23T14:24:26Z"
                  }
                },
                "some-other-model": {
                  "displayName": "Other",
                  "quotaInfo": { "remainingFraction": 0.1 }
                }
              }
            }
            """.trimIndent(),
        ).jsonObjectLike()

        val windows = AntigravityUsageFetcher.parseAvailableModels(root)
        assertEquals(2, windows.size)
        assertEquals(0.6, windows.first { it.id.contains("gemini") }.usedRatio, absoluteTolerance = 1e-9)
        assertEquals(0.1, windows.first { it.id.contains("claude") }.usedRatio, absoluteTolerance = 1e-9)
    }

    @Test
    fun preservesArgonGroupAndTreatsOmittedRemainingFractionWithResetTimeAsExhausted() {
        val root = json.parseToJsonElement(
            """
            {
              "groups": [
                {
                  "displayName": "Gemini Models",
                  "buckets": [
                    {
                      "bucketId": "gemini-5h",
                      "displayName": "Five Hour Limit Remaining",
                      "window": "5h",
                      "resetTime": "2026-11-21T16:33:06Z"
                    },
                    {
                      "bucketId": "gemini-weekly",
                      "displayName": "Weekly Limit Remaining",
                      "window": "weekly",
                      "resetTime": "2026-11-28T11:33:06Z",
                      "remainingFraction": 0.9
                    }
                  ]
                },
                {
                  "displayName": "Gemini 4 Argon",
                  "buckets": [
                    {
                      "bucketId": "gemini-argon-wtu-weekly",
                      "displayName": "Gemini 4 Argon Weekly Limit",
                      "window": "weekly",
                      "resetTime": "2026-11-28T12:21:10Z",
                      "remainingFraction": 1
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        ).jsonObjectLike()

        val windows = AntigravityUsageFetcher.parseQuotaSummary(root)

        assertEquals(3, windows.size)
        assertEquals("antigravity-gemini-session", windows[0].id)
        assertEquals(1.0, windows[0].usedRatio, absoluteTolerance = 1e-9)
        assertEquals("antigravity-gemini-weekly", windows[1].id)
        assertEquals(0.1, windows[1].usedRatio, absoluteTolerance = 1e-9)
        assertEquals("antigravity-gemini-argon-weekly", windows[2].id)
        assertEquals("Gemini 4 Argon · Weekly", windows[2].label)
        assertEquals(0.0, windows[2].usedRatio, absoluteTolerance = 1e-9)
    }

    @Test
    fun prefersPaidTierOverCurrentTierForPlanLabel() {
        val root = json.parseToJsonElement(
            """
            {
              "currentTier": { "id": "free-tier", "name": "Free" },
              "paidTier": { "id": "g1-pro-tier", "name": "Google AI Pro" }
            }
            """.trimIndent(),
        ).jsonObjectLike()

        assertEquals("Google AI Pro", AntigravityUsageFetcher.extractPlanLabel(root))

        val idOnly = json.parseToJsonElement(
            """
            {
              "currentTier": { "id": "free-tier" },
              "paidTier": { "id": "g1-pro-tier" }
            }
            """.trimIndent(),
        ).jsonObjectLike()

        assertEquals("Google AI Pro", formatAntigravityPlanLabel(AntigravityUsageFetcher.extractPlanLabel(idOnly)))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectLike() =
        this as kotlinx.serialization.json.JsonObject
}
