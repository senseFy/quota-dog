package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.quotadog.hostsync.LocalQuota
import saien.quotadog.hostsync.QuotaDetails
import saien.quotadog.hostsync.QuotaWindow
import saien.quotadog.hostsync.QuotaWindowKind
import saien.quotadog.hostsync.quotaWindow

internal data class CursorUsageSnapshot(
    val membershipType: String?,
    val isUnlimited: Boolean,
    val billingCycleEnd: Instant?,
    val windows: List<UsageWindow>,
    val includedSpendCents: Int? = null,
    val includedLimitCents: Int? = null,
    val localQuota: LocalQuota,
)

internal object CursorUsageFetcher {
    private const val ENDPOINT = "https://api2.cursor.sh/auth/usage-summary"
    private const val ME_ENDPOINT = "https://api2.cursor.sh/aiserver.v1.DashboardService/GetMe"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchUserEmail(httpClient: HttpClient, accessToken: String): String? {
        val response = httpClient.post(ME_ENDPOINT) {
            header("Authorization", "Bearer $accessToken")
            header("Connect-Protocol-Version", "1")
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        if (!response.status.isSuccess()) return null
        return parseMeEmail(response.bodyAsText())
    }

    internal fun parseMeEmail(text: String): String? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return root["email"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

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
        val individualUsage = root["individualUsage"] as? JsonObject

        val windows = mutableListOf<UsageWindow>()
        val quotaWindows = mutableListOf<QuotaWindow>()
        // A billing cycle's time remaining is not its duration.
        val reportedCycle = if (billingCycleStart != null && billingCycleEnd != null) {
            billingCycleEnd.epochSeconds - billingCycleStart.epochSeconds
        } else null
        val cycleSeconds = inferCursorCycleDurationSeconds(billingCycleStart, billingCycleEnd, now)
        val plan = (individualUsage?.get("plan") as? JsonObject)?.takeUnless { it["enabled"]?.jsonPrimitive?.booleanOrNull == false }
        if (isUnlimited) {
            quotaWindows += quotaWindow(QuotaWindowKind.CursorIncluded, null, billingCycleEnd, reportedCycle, unlimited = true)
            windows += UsageWindow(
                id = "plan-usage",
                label = "Unlimited",
                usedRatio = 0.0,
                resetsAt = billingCycleEnd,
                durationSeconds = cycleSeconds,
            )
        } else if (plan != null) {
            // Match cursor-agent: Included uses totalPercentUsed, then spend/limit.
            val included = planUsedRatio(plan)
            quotaWindows += quotaWindow(QuotaWindowKind.CursorIncluded, included, billingCycleEnd, reportedCycle)
            windows += UsageWindow(
                id = "plan-usage",
                label = "Included",
                usedRatio = included ?: 0.0,
                resetsAt = billingCycleEnd,
                durationSeconds = cycleSeconds,
            )
            percentWindow(plan, "autoPercentUsed", "cursor-auto", "Auto", billingCycleEnd, cycleSeconds)
                ?.let { windows += it; quotaWindows += quotaWindow(QuotaWindowKind.CursorAuto, it.usedRatio, billingCycleEnd, reportedCycle) }
            percentWindow(plan, "apiPercentUsed", "cursor-api", "API", billingCycleEnd, cycleSeconds)
                ?.let { windows += it; quotaWindows += quotaWindow(QuotaWindowKind.CursorApi, it.usedRatio, billingCycleEnd, reportedCycle) }
        }
        val onDemand = (individualUsage?.get("onDemand") as? JsonObject)?.takeIf { it["enabled"]?.jsonPrimitive?.booleanOrNull == true }
        val used = onDemand?.intField("used")
        val limit = onDemand?.intField("limit")
        if (onDemand != null) {
            val usedRatio = if (limit != null && limit > 0 && used != null) (used.toDouble() / limit).coerceIn(0.0, 1.0) else null
            quotaWindows += quotaWindow(QuotaWindowKind.CursorOnDemand, usedRatio, billingCycleEnd, reportedCycle)
            windows += UsageWindow(
                id = "on-demand",
                label = if (limit != null && limit > 0) {
                    "On-demand"
                } else {
                    if (used != null) "On-demand · $used used" else "On-demand"
                },
                usedRatio = usedRatio ?: 0.0,
                resetsAt = billingCycleEnd,
                durationSeconds = cycleSeconds,
            )
        }

        if (windows.isEmpty()) {
            // A recognized membership/cycle with no numeric quota means unknown, never 0%.
            if (membershipType != null || billingCycleEnd != null || individualUsage != null) {
                quotaWindows += quotaWindow(QuotaWindowKind.CursorIncluded, null, billingCycleEnd, reportedCycle)
            }
            windows += UsageWindow(
                id = "plan-usage",
                label = "Plan usage",
                usedRatio = 0.0,
                resetsAt = billingCycleEnd,
                durationSeconds = inferCursorCycleDurationSeconds(billingCycleStart, billingCycleEnd, now),
            )
        }

        val includedSpend = plan?.intField("used") ?: plan?.intField("includedSpend")
        val includedLimit = plan?.intField("limit")
        val amounts = if (listOf(includedSpend, includedLimit, used, limit).any { it != null }) {
            QuotaDetails.Cursor(includedSpend, includedLimit, used, limit)
        } else null
        return CursorUsageSnapshot(
            membershipType = membershipType,
            isUnlimited = isUnlimited,
            billingCycleEnd = billingCycleEnd,
            windows = windows,
            includedSpendCents = includedSpend,
            includedLimitCents = includedLimit,
            localQuota = LocalQuota(ProviderId.CURSOR, quotaWindows, amounts),
        )
    }

    private fun planUsedRatio(plan: JsonObject): Double? {
        // cursor-agent: percentage ?? (used/limit*100). Do not prefer spend/limit
        // when totalPercentUsed exists — Ultra spend can be ~98% while Included is ~16%.
        val totalPercentUsed = plan.percent("totalPercentUsed")
        if (totalPercentUsed != null) return normalizePercent(totalPercentUsed)
        val used = plan.intField("used") ?: plan.intField("includedSpend")
        val limit = plan.intField("limit")
        if (used != null && limit != null && limit > 0) {
            return (used.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0)
        }
        val remaining = plan.intField("remaining")
        if (remaining != null && limit != null && limit > 0) {
            return (1.0 - remaining.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0)
        }
        return null
    }

    private fun percentWindow(
        plan: JsonObject,
        key: String,
        id: String,
        label: String,
        resetsAt: Instant?,
        durationSeconds: Long?,
    ): UsageWindow? {
        val percent = plan.percent(key) ?: return null
        return UsageWindow(
            id = id,
            label = label,
            usedRatio = normalizePercent(percent),
            resetsAt = resetsAt,
            durationSeconds = durationSeconds,
        )
    }

    private fun JsonObject.intField(name: String) = (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
    private fun JsonObject.percent(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

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
