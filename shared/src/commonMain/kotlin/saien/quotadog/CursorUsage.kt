package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class CursorUsageSnapshot(
    val membershipType: String?,
    val isUnlimited: Boolean,
    val billingCycleEnd: Instant?,
    val windows: List<UsageWindow>,
)

internal object CursorUsageFetcher {
    private const val ENDPOINT = "https://api2.cursor.sh/auth/usage-summary"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(httpClient: HttpClient, accessToken: String): CursorUsageSnapshot {
        val response = httpClient.get(ENDPOINT) {
            header("Authorization", "Bearer $accessToken")
            header("Accept", "application/json")
            header("User-Agent", "Mozilla/5.0 (QuotaDog; Kotlin)")
        }
        return parseResponse(response)
    }

    internal suspend fun parseResponse(response: HttpResponse): CursorUsageSnapshot {
        val statusCode = response.status.value
        val body = response.bodyAsText()
        if (statusCode == 401 || statusCode == 403) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Cursor rejected credentials. Sign in to Cursor again, then re-import.",
                statusCode,
            )
        }
        if (!response.status.isSuccess()) {
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(
                AuthState.Error,
                "Cursor usage request failed: HTTP $statusCode: $preview",
                statusCode,
            )
        }
        return parseUsageSummaryJson(body)
    }

    internal fun parseUsageSummaryJson(
        text: String,
        now: Instant = Clock.System.now(),
    ): CursorUsageSnapshot {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Cursor usage response was not valid JSON.")
        }
        val membershipType = root["membershipType"]?.jsonPrimitive?.contentOrNull
        val isUnlimited = root["isUnlimited"]?.jsonPrimitive?.booleanOrNull ?: false
        val billingCycleEnd = parseInstant(root["billingCycleEnd"]?.jsonPrimitive?.contentOrNull)
        val billingCycleStart = parseInstant(root["billingCycleStart"]?.jsonPrimitive?.contentOrNull)
        val individualUsage = root["individualUsage"]?.jsonObject

        val windows = mutableListOf<UsageWindow>()
        if (isUnlimited) {
            windows += UsageWindow(
                id = "plan-usage",
                label = "Unlimited",
                usedRatio = 0.0,
                resetsAt = billingCycleEnd,
                durationSeconds = inferCursorCycleDurationSeconds(billingCycleStart, billingCycleEnd, now),
            )
        } else {
            val plan = individualUsage?.get("plan")?.jsonObject
            if (plan != null && (plan["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)) {
                val usedRatio = planUsedRatio(plan)
                windows += UsageWindow(
                    id = "plan-usage",
                    label = "Plan usage",
                    usedRatio = usedRatio,
                    resetsAt = billingCycleEnd,
                    durationSeconds = inferCursorCycleDurationSeconds(billingCycleStart, billingCycleEnd, now),
                )
            }
            val onDemand = individualUsage?.get("onDemand")?.jsonObject
            if (onDemand != null && (onDemand["enabled"]?.jsonPrimitive?.booleanOrNull ?: false)) {
                val used = onDemand["used"]?.jsonPrimitive?.intOrNull ?: 0
                val limit = onDemand["limit"]?.jsonPrimitive?.intOrNull
                val usedRatio = if (limit != null && limit > 0) {
                    (used.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
                windows += UsageWindow(
                    id = "on-demand",
                    label = if (limit != null && limit > 0) {
                        "On-demand"
                    } else {
                        "On-demand · $used used"
                    },
                    usedRatio = usedRatio,
                    resetsAt = billingCycleEnd,
                    durationSeconds = inferCursorCycleDurationSeconds(billingCycleStart, billingCycleEnd, now),
                )
            }
        }

        if (windows.isEmpty()) {
            windows += UsageWindow(
                id = "plan-usage",
                label = "Plan usage",
                usedRatio = 0.0,
                resetsAt = billingCycleEnd,
                durationSeconds = inferCursorCycleDurationSeconds(billingCycleStart, billingCycleEnd, now),
            )
        }

        return CursorUsageSnapshot(
            membershipType = membershipType,
            isUnlimited = isUnlimited,
            billingCycleEnd = billingCycleEnd,
            windows = windows,
        )
    }

    private fun planUsedRatio(plan: kotlinx.serialization.json.JsonObject): Double {
        val totalPercentUsed = plan["totalPercentUsed"]?.jsonPrimitive?.doubleOrNull
        if (totalPercentUsed != null) return normalizePercent(totalPercentUsed)
        val used = plan["used"]?.jsonPrimitive?.intOrNull
        val limit = plan["limit"]?.jsonPrimitive?.intOrNull
        if (used != null && limit != null && limit > 0) {
            return (used.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0)
        }
        val remaining = plan["remaining"]?.jsonPrimitive?.intOrNull
        if (remaining != null && limit != null && limit > 0) {
            return (1.0 - remaining.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0)
        }
        return 0.0
    }

    private fun parseInstant(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    private fun inferCursorCycleDurationSeconds(
        start: Instant?,
        end: Instant?,
        now: Instant,
    ): Long? {
        if (start != null && end != null) {
            val seconds = (end.toEpochMilliseconds() - start.toEpochMilliseconds()) / 1_000L
            if (seconds > 0) return seconds
        }
        if (end != null) {
            val remaining = (end.toEpochMilliseconds() - now.toEpochMilliseconds()) / 1_000L
            if (remaining > 0) return remaining
        }
        return THIRTY_DAY_SECONDS
    }

    private const val THIRTY_DAY_SECONDS = 30L * 24L * 60L * 60L
}
