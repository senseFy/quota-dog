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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import saien.quotadog.hostsync.LocalQuota
import saien.quotadog.hostsync.QuotaDetails
import saien.quotadog.hostsync.QuotaWindow
import saien.quotadog.hostsync.QuotaWindowKind
import saien.quotadog.hostsync.quotaWindow

internal data class DroidUsageSnapshot(
    val email: String?,
    val planLabel: String?,
    val windows: List<UsageWindow>,
    val extraUsageBalanceCents: Long? = null,
    val localQuota: LocalQuota,
    val quotaTarget: String?,
)

/**
 * Reads Factory Droid quota through the same endpoints the Factory web app uses:
 *
 * - `GET https://api.factory.ai/api/billing/limits` — token-rate-limit pools
 *   (`standard` / `core`, each with `fiveHour` / `weekly` / `monthly` windows).
 * - `GET https://api.factory.ai/api/organization/subscription/usage?useCache=true` —
 *   legacy billing-period `standard` / `premium` ratios, used as a fallback.
 * - `GET https://api.factory.ai/api/app/auth/me` — account email and plan label.
 *
 * Authenticated with the WorkOS `access_token` from the droid CLI credentials.
 */
internal object DroidUsageFetcher {
    private const val API_BASE = "https://api.factory.ai"
    private const val APP_BASE = "https://app.factory.ai"
    private const val FIVE_HOUR_SECONDS = 5L * 60L * 60L
    private const val WEEK_SECONDS = 7L * 24L * 60L * 60L
    private const val MONTH_SECONDS = 30L * 24L * 60L * 60L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(httpClient: HttpClient, accessToken: String): DroidUsageSnapshot {
        val billing = fetchBillingLimits(httpClient, accessToken)
        val observed = if (billing != null) billingQuotaWindows(billing) else legacyQuotaWindows(fetchSubscriptionUsage(httpClient, accessToken))
        val localQuota = droidLocalQuota(observed, billing?.extraUsageBalanceCents)
        if (!localQuota.hasAccountReading) {
            throw ProviderException(AuthState.Error, "Droid did not report quota data for this account.")
        }
        val identity = runCatching { fetchAuthMe(httpClient, accessToken) }.getOrNull()
        return DroidUsageSnapshot(
            email = identity?.email,
            planLabel = identity?.planLabel,
            windows = observed.mapNotNull { it.toDisplayWindow() },
            extraUsageBalanceCents = billing?.extraUsageBalanceCents,
            localQuota = localQuota,
            quotaTarget = identity?.organizationId?.let { "${if (billing != null) "rolling" else "legacy"}:$it" },
        )
    }

    private suspend fun fetchBillingLimits(httpClient: HttpClient, accessToken: String): DroidBillingLimits? {
        val response = httpClient.get("$API_BASE/api/billing/limits") {
            applyDroidHeaders(accessToken)
        }
        if (!response.status.isSuccess()) return null
        val text = response.bodyAsText()
        return runCatching { parseBillingLimitsJson(text) }.getOrNull()
            ?.takeIf { it.usesTokenRateLimitsBilling && (it.standard != null || it.core != null || it.extraUsageBalanceCents != null) }
    }

    private suspend fun fetchSubscriptionUsage(httpClient: HttpClient, accessToken: String): JsonObject {
        val response = httpClient.get("$API_BASE/api/organization/subscription/usage?useCache=true") {
            applyDroidHeaders(accessToken)
        }
        return parseJsonResponse(response, "Droid usage")
    }

    private suspend fun fetchAuthMe(httpClient: HttpClient, accessToken: String): DroidIdentity {
        val response = httpClient.get("$API_BASE/api/app/auth/me") {
            applyDroidHeaders(accessToken)
        }
        if (!response.status.isSuccess()) return DroidIdentity(null, null)
        return parseAuthMeJson(response.bodyAsText())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyDroidHeaders(accessToken: String) {
        header("Authorization", "Bearer $accessToken")
        header("Accept", "application/json")
        header("Content-Type", "application/json")
        header("Origin", APP_BASE)
        header("Referer", "$APP_BASE/")
        header("x-factory-client", "web-app")
    }

    private suspend fun parseJsonResponse(response: HttpResponse, label: String): JsonObject {
        val statusCode = response.status.value
        val body = response.bodyAsText()
        if (statusCode == 401 || statusCode == 403) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "$label rejected credentials. Run `droid`, sign in, then re-import.",
                statusCode,
            )
        }
        if (!response.status.isSuccess()) {
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(AuthState.Error, "$label request failed: HTTP $statusCode: $preview", statusCode)
        }
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "$label response was not valid JSON.")
        }
    }

    internal data class DroidBillingWindow(
        val usedPercent: Double?,
        val windowEnd: Instant?,
        val secondsRemaining: Double?,
    ) {
        fun resetsAt(now: Instant): Instant? {
            windowEnd?.let { return it }
            val seconds = secondsRemaining?.takeIf { it > 0.0 && it.isFinite() } ?: return null
            return Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + (seconds * 1000.0).toLong())
        }

        val hasUsageData: Boolean
            get() = (usedPercent ?: 0.0) > 0.0 || windowEnd != null || (secondsRemaining ?: 0.0) > 0.0
    }

    internal data class DroidBillingLimits(
        val usesTokenRateLimitsBilling: Boolean,
        val standard: Map<String, DroidBillingWindow>?,
        val core: Map<String, DroidBillingWindow>?,
        val extraUsageBalanceCents: Long?,
    )

    internal data class DroidIdentity(
        val email: String?,
        val planLabel: String?,
        val organizationId: String? = null,
    )

    internal fun parseBillingLimitsJson(text: String): DroidBillingLimits {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Droid billing response was not valid JSON.")
        }
        val limits = root["limits"] as? JsonObject
        return DroidBillingLimits(
            usesTokenRateLimitsBilling = root["usesTokenRateLimitsBilling"]
                ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true,
            standard = (limits?.get("standard") as? JsonObject)?.let(::parsePool),
            core = (limits?.get("core") as? JsonObject)?.let(::parsePool),
            extraUsageBalanceCents = (root["extraUsageBalanceCents"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 },
        )
    }

    private fun parsePool(pool: JsonObject): Map<String, DroidBillingWindow> {
        val windows = mutableMapOf<String, DroidBillingWindow>()
        for (key in listOf("fiveHour", "weekly", "monthly")) {
            val element = pool[key] as? JsonObject ?: continue
            windows[key] = DroidBillingWindow(
                usedPercent = (element["usedPercent"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 },
                windowEnd = element["windowEnd"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                secondsRemaining = element["secondsRemaining"]?.jsonPrimitive?.doubleOrNull,
            )
        }
        return windows
    }

    internal fun billingQuotaWindows(billing: DroidBillingLimits, now: Instant = Clock.System.now()) = buildList {
        fun pool(values: Map<String, DroidBillingWindow>?, core: Boolean) {
            fun window(key: String, kind: QuotaWindowKind, seconds: Long) {
                val item = values?.get(key) ?: return
                if (!core || item.hasUsageData) add(quotaWindow(kind, item.usedPercent?.let(::normalizePercent), item.resetsAt(now), seconds))
            }
            window("fiveHour", if (core) QuotaWindowKind.DroidCoreFiveHour else QuotaWindowKind.DroidStandardFiveHour, FIVE_HOUR_SECONDS)
            window("weekly", if (core) QuotaWindowKind.DroidCoreWeekly else QuotaWindowKind.DroidStandardWeekly, WEEK_SECONDS)
            window("monthly", if (core) QuotaWindowKind.DroidCoreMonthly else QuotaWindowKind.DroidStandardMonthly, MONTH_SECONDS)
        }
        pool(billing.standard, false)
        pool(billing.core, true)
    }

    internal fun legacyQuotaWindows(root: JsonObject): List<QuotaWindow> {
        val usage = root["usage"] as? JsonObject
            ?: throw ProviderException(AuthState.Error, "Droid usage response had no usage object.")
        val resetsAt = usage["endDate"]?.jsonPrimitive?.longOrNull?.let(Instant::fromEpochMilliseconds)
        val durationSeconds = usage["startDate"]?.jsonPrimitive?.longOrNull
            ?.let { start -> usage["endDate"]?.jsonPrimitive?.longOrNull?.let { end -> (end - start) / 1000L } }

        val windows = mutableListOf<QuotaWindow>()
        fun JsonObject.ratio() = (this["usedRatio"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }?.coerceAtMost(1.0)
        (usage["standard"] as? JsonObject)?.let { standard ->
            windows += quotaWindow(QuotaWindowKind.DroidStandardPeriod, standard.ratio(), resetsAt, durationSeconds)
        }
        (usage["premium"] as? JsonObject)?.let { premium ->
            val allowance = premium["totalAllowance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (allowance.isFinite() && allowance > 0) windows += quotaWindow(QuotaWindowKind.DroidPremiumPeriod, premium.ratio(), resetsAt, durationSeconds)
        }
        return windows
    }

    // Neither billing/limits nor the unfiltered legacy organization/subscription/usage response
    // identifies the seat owning its pools. /auth/me membership and an active org are NOT sufficient.
    internal fun droidLocalQuota(windows: List<QuotaWindow>, extraCents: Long?) = LocalQuota(
        ProviderId.DROID, windows, extraCents?.takeIf { it >= 0 }?.let { QuotaDetails.Droid(it) },
    )

    private fun QuotaWindow.toDisplayWindow(): UsageWindow? {
        val (id, label) = when (kind) {
            QuotaWindowKind.DroidStandardFiveHour -> "droid-standard-5h" to kind.label
            QuotaWindowKind.DroidStandardWeekly -> "droid-standard-weekly" to kind.label
            QuotaWindowKind.DroidStandardMonthly -> "droid-standard-monthly" to kind.label
            QuotaWindowKind.DroidCoreFiveHour -> "droid-core-5h" to kind.label
            QuotaWindowKind.DroidCoreWeekly -> "droid-core-weekly" to kind.label
            QuotaWindowKind.DroidCoreMonthly -> "droid-core-monthly" to kind.label
            QuotaWindowKind.DroidStandardPeriod -> "droid-standard" to "Standard tokens"
            QuotaWindowKind.DroidPremiumPeriod -> "droid-premium" to "Premium tokens"
            else -> return null
        }
        return UsageWindow(id, label, used ?: return null, resetsAt?.let(Instant::fromEpochMilliseconds), durationSeconds = durationSeconds)
    }

    internal fun windowsFromBillingLimits(billing: DroidBillingLimits) = billingQuotaWindows(billing).mapNotNull { it.toDisplayWindow() }
    internal fun windowsFromSubscriptionUsage(root: JsonObject) = legacyQuotaWindows(root).mapNotNull { it.toDisplayWindow() }

    internal fun parseAuthMeJson(text: String): DroidIdentity {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { return DroidIdentity(null, null) }
        val email = (root["user"] as? JsonObject)?.stringField("email")
            ?: (root["userProfile"] as? JsonObject)?.stringField("email")
        val organization = root["organization"] as? JsonObject
        val subscription = organization?.get("subscription") as? JsonObject
        val planLabel = organization?.stringField("planName")
            ?: ((subscription?.get("orbSubscription") as? JsonObject)
                ?.get("plan") as? JsonObject)?.stringField("name")
            ?: subscription?.stringField("factoryTier")
            ?: subscription?.stringField("factoryTiers")
            ?: organization?.stringField("tier")
        return DroidIdentity(email, planLabel, (organization?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) })
    }

    private fun JsonObject.stringField(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
