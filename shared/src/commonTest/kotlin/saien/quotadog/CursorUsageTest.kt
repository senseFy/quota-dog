package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CursorAuthParserTest {
    @Test
    fun buildsTokenBundleFromAuthRows() {
        val rows = mapOf(
            "cursorAuth/accessToken" to sampleJwt(expSeconds = 1_800_000_000L),
            "cursorAuth/refreshToken" to "refresh-token",
            "cursorAuth/cachedEmail" to "user@cursor.com",
            "cursorAuth/stripeMembershipType" to "pro_student",
        )

        val token = CursorAuthParser.fromAuthRows(rows)

        assertEquals("user@cursor.com", token.email)
        assertEquals("pro_student", token.accountId)
        assertEquals("refresh-token", token.refreshToken)
        assertEquals(1_800_000_000_000L, token.expiresAtEpochMillis)
    }

    @Test
    fun requiresAccessToken() {
        val error = kotlin.test.assertFailsWith<ProviderException> {
            CursorAuthParser.fromAuthRows(mapOf("cursorAuth/cachedEmail" to "user@cursor.com"))
        }
        assertEquals(AuthState.NotConfigured, error.state)
    }

    @Test
    fun returnsNullExpiryForMalformedJwt() {
        assertNull(CursorAuthParser.jwtExpiryEpochMillis("not-a-jwt"))
    }

    @Test
    fun readsEmailFromJwtWhenCachedEmailMissing() {
        val token = CursorAuthParser.fromAuthRows(
            mapOf("cursorAuth/accessToken" to sampleJwt(expSeconds = 1_800_000_000L)),
        )
        assertEquals("user@cursor.com", token.email)
    }

    @Test
    fun treatsEmailLikeSubAsAccountEmail() {
        val header = "eyJhbGciOiJub25lIn0"
        val payload = base64UrlEncode("""{"exp":1800000000,"sub":"cli@cursor.com"}""".encodeToByteArray())
        assertEquals("cli@cursor.com", CursorAuthParser.emailFromAccessToken("$header.$payload.sig"))
    }

    private fun sampleJwt(expSeconds: Long): String {
        val header = "eyJhbGciOiJub25lIn0"
        val payloadJson = """{"exp":$expSeconds,"email":"user@cursor.com"}"""
        val payload = base64UrlEncode(payloadJson.encodeToByteArray())
        return "$header.$payload.sig"
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val output = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                output.append(alphabet[(buffer shr bits) and 0x3f])
            }
        }
        if (bits > 0) {
            output.append(alphabet[(buffer shl (6 - bits)) and 0x3f])
        }
        return output.toString()
    }
}

class CursorUsageParserTest {
    @Test
    fun parsesPlanAndOnDemandWindows() {
        val snapshot = CursorUsageFetcher.parseUsageSummaryJson(
            """
            {
              "membershipType": "pro",
              "isUnlimited": false,
              "billingCycleStart": "2026-06-01T00:00:00.000Z",
              "billingCycleEnd": "2026-07-01T00:00:00.000Z",
              "individualUsage": {
                "plan": {
                  "enabled": true,
                  "used": 250,
                  "limit": 1000,
                  "remaining": 750,
                  "totalPercentUsed": 25.0
                },
                "onDemand": {
                  "enabled": true,
                  "used": 40,
                  "limit": 100,
                  "remaining": 60
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("pro", snapshot.membershipType)
        assertEquals(2, snapshot.windows.size)
        val plan = snapshot.windows.first { it.id == "plan-usage" }
        assertEquals("Included", plan.label)
        assertEquals(0.25, plan.usedRatio, 0.000001)
        assertNotNull(plan.resetsAt)
        val onDemand = snapshot.windows.first { it.id == "on-demand" }
        assertEquals(0.4, onDemand.usedRatio, 0.000001)
    }

    @Test
    fun parsesOnePercentPlanUsageAsOnePercent() {
        val snapshot = CursorUsageFetcher.parseUsageSummaryJson(
            """
            {
              "membershipType": "pro",
              "isUnlimited": false,
              "billingCycleEnd": "2026-07-01T00:00:00.000Z",
              "individualUsage": {
                "plan": {
                  "enabled": true,
                  "totalPercentUsed": 1.0
                }
              }
            }
            """.trimIndent()
        )

        val plan = snapshot.windows.first { it.id == "plan-usage" }
        assertEquals(0.01, plan.usedRatio, 0.000001)
    }

    @Test
    fun parsesUnlimitedPlan() {
        val snapshot = CursorUsageFetcher.parseUsageSummaryJson(
            """
            {
              "membershipType": "ultra",
              "isUnlimited": true,
              "billingCycleEnd": "2026-07-01T00:00:00Z",
              "individualUsage": {}
            }
            """.trimIndent()
        )

        assertTrue(snapshot.isUnlimited)
        assertEquals(1, snapshot.windows.size)
        assertEquals("Unlimited", snapshot.windows.single().label)
        assertEquals(0.0, snapshot.windows.single().usedRatio)
    }

    @Test
    fun prefersTotalPercentWhenItDivergesFromSpend() {
        val snapshot = CursorUsageFetcher.parseUsageSummaryJson(
            """
            {
              "membershipType": "ultra",
              "isUnlimited": false,
              "billingCycleEnd": "2026-09-12T03:11:40.000Z",
              "individualUsage": {
                "plan": {
                  "enabled": true,
                  "used": 39273,
                  "limit": 40000,
                  "remaining": 727,
                  "autoPercentUsed": 0.171,
                  "apiPercentUsed": 77.862,
                  "totalPercentUsed": 15.7092
                }
              }
            }
            """.trimIndent()
        )

        val plan = snapshot.windows.first { it.id == "plan-usage" }
        assertEquals(0.157092, plan.usedRatio, 0.000001)
        assertEquals(39273, snapshot.includedSpendCents)
        assertEquals(40000, snapshot.includedLimitCents)
        assertEquals(0.00171, snapshot.windows.first { it.id == "cursor-auto" }.usedRatio, 0.000001)
        assertEquals(0.77862, snapshot.windows.first { it.id == "cursor-api" }.usedRatio, 0.000001)
    }

    @Test
    fun parsesGetMeEmail() {
        assertEquals("user@cursor.com", CursorUsageFetcher.parseMeEmail("""{"email":"user@cursor.com","userId":1}"""))
        assertNull(CursorUsageFetcher.parseMeEmail("""{"userId":1}"""))
    }
}
