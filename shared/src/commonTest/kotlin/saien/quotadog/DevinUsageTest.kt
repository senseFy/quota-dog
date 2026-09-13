package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevinAuthParserTest {
    @Test
    fun parsesCredentialsToml() {
        val token = DevinAuthParser.parseCredentialsToml(
            """
            api_server_url = "https://server.codeium.com"
            devin_api_url = "https://api.devin.ai"
            devin_webapp_host = "app.devin.ai"
            windsurf_api_key = "sk-test-key"
            """.trimIndent()
        )

        assertEquals("sk-test-key", token.accessToken)
        assertEquals("https://server.codeium.com", token.apiServerUrl)
        assertTrue(!token.isExpired())
    }

    @Test
    fun toleratesCommentsAndSingleQuotes() {
        val token = DevinAuthParser.parseCredentialsToml(
            """
            # comment line
            windsurf_api_key = 'sk-quoted' # trailing comment
            """.trimIndent()
        )

        assertEquals("sk-quoted", token.accessToken)
        assertNull(token.apiServerUrl)
    }

    @Test
    fun rejectsNonHttpsServerUrl() {
        val token = DevinAuthParser.parseCredentialsToml(
            """
            api_server_url = "http://insecure.example.com/"
            windsurf_api_key = "sk-test-key"
            """.trimIndent()
        )

        assertNull(token.apiServerUrl)
    }

    @Test
    fun trimsTrailingSlashOnServerUrl() {
        assertEquals(
            "https://server.codeium.com",
            DevinAuthParser.cleanApiServerUrl("https://server.codeium.com/"),
        )
    }

    @Test
    fun requiresApiKey() {
        val error = assertFailsWith<ProviderException> {
            DevinAuthParser.parseCredentialsToml("""api_server_url = "https://server.codeium.com"""")
        }
        assertEquals(AuthState.NotConfigured, error.state)
    }
}

class DevinUsageParserTest {
    @Test
    fun mapsRemainingPercentsToUsedWindows() {
        val snapshot = DevinUsageFetcher.parseUserStatusJson(
            """
            {
              "userStatus": {
                "email": "user@devin.ai",
                "planStatus": {
                  "planInfo": { "planName": "Pro" },
                  "dailyQuotaRemainingPercent": 75,
                  "weeklyQuotaRemainingPercent": 25,
                  "dailyQuotaResetAtUnix": "1789286400",
                  "weeklyQuotaResetAtUnix": "1789804800",
                  "overageBalanceMicros": "2500000"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("Pro", snapshot.planName)
        assertEquals("user@devin.ai", snapshot.email)
        assertEquals(2.5, snapshot.overageBalanceDollars!!, 0.000001)

        val daily = snapshot.windows.first { it.id == "devin-daily" }
        assertEquals(0.25, daily.usedRatio, 0.000001)
        assertEquals(1789286400L, daily.resetsAt?.epochSeconds)
        assertEquals(86400L, daily.durationSeconds)

        val weekly = snapshot.windows.first { it.id == "devin-weekly" }
        assertEquals(0.75, weekly.usedRatio, 0.000001)
        assertEquals(1789804800L, weekly.resetsAt?.epochSeconds)
        assertEquals(604800L, weekly.durationSeconds)
    }

    @Test
    fun hidesDailyWindowWhenServerHidesDailyQuota() {
        val snapshot = DevinUsageFetcher.parseUserStatusJson(
            """
            {
              "userStatus": {
                "planStatus": {
                  "planInfo": { "planName": "Pro", "hideDailyQuota": true },
                  "dailyQuotaRemainingPercent": 90,
                  "weeklyQuotaRemainingPercent": 40
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, snapshot.windows.size)
        assertEquals("devin-weekly", snapshot.windows.single().id)
        assertEquals(0.6, snapshot.windows.single().usedRatio, 0.000001)
    }

    @Test
    fun fallsBackToDailyFigureWhenWeeklyMissingAndDailyHidden() {
        val snapshot = DevinUsageFetcher.parseUserStatusJson(
            """
            {
              "userStatus": {
                "planStatus": {
                  "planInfo": { "hideDailyQuota": true },
                  "dailyQuotaRemainingPercent": 50
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, snapshot.windows.size)
        val weekly = snapshot.windows.single()
        assertEquals("devin-weekly", weekly.id)
        assertEquals(0.5, weekly.usedRatio, 0.000001)
    }

    @Test
    fun acceptsNumericStringsAndNumbers() {
        val snapshot = DevinUsageFetcher.parseUserStatusJson(
            """
            {
              "userStatus": {
                "planStatus": {
                  "dailyQuotaRemainingPercent": "33.5",
                  "weeklyQuotaRemainingPercent": 10
                }
              }
            }
            """.trimIndent()
        )

        val daily = snapshot.windows.first { it.id == "devin-daily" }
        assertEquals(0.665, daily.usedRatio, 0.000001)
    }

    @Test
    fun rejectsResponseWithoutQuota() {
        assertFailsWith<ProviderException> {
            DevinUsageFetcher.parseUserStatusJson("""{"userStatus":{"planStatus":{"planInfo":{"planName":"Pro"}}}}""")
        }
    }

    @Test
    fun rejectsNonJson() {
        assertFailsWith<ProviderException> {
            DevinUsageFetcher.parseUserStatusJson("not json")
        }
    }

    @Test
    fun missingOverageBalanceStaysNull() {
        val snapshot = DevinUsageFetcher.parseUserStatusJson(
            """
            {
              "userStatus": {
                "planStatus": { "weeklyQuotaRemainingPercent": 100, "overageBalanceMicros": null }
              }
            }
            """.trimIndent()
        )

        assertNull(snapshot.overageBalanceDollars)
        assertNotNull(snapshot.windows.firstOrNull { it.id == "devin-weekly" })
    }
}
