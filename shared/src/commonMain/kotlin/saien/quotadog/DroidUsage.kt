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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class DroidUsageSnapshot(
    val email: String?,
    val planLabel: String?,
    val windows: List<UsageWindow>,
    val extraUsageBalanceCents: Long? = null,
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
        val windows = billing?.let { windowsFromBillingLimits(it) }
            ?: windowsFromSubscriptionUsage(fetchSubscriptionUsage(httpClient, accessToken))
        if (windows.isEmpty()) {
            throw ProviderException(AuthState.Error, "Droid did not report quota data for this account.")
        }
        val identity = runCatching { fetchAuthMe(httpClient, accessToken) }.getOrNull()
        return DroidUsageSnapshot(
            email = identity?.email,
            planLabel = identity?.planLabel,
            windows = windows,
            extraUsageBalanceCents = billing?.extraUsageBalanceCents,
        )
    }

    private suspend fun fetchBillingLimits(httpClient: HttpClient, accessToken: String): DroidBillingLimits? {
        val response = httpClient.get("$API_BASE/api/billing/limits") {
            applyDroidHeaders(accessToken)
        }
        if (!response.status.isSuccess()) return null
        val text = response.bodyAsText()
        return runCatching { parseBillingLimitsJson(text) }.getOrNull()
            ?.takeIf { it.usesTokenRateLimitsBilling && (it.standard != null || it.core != null) }
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
        val usedPercent: Double,
        val windowEnd: Instant?,
        val secondsRemaining: Double?,
    ) {
        fun resetsAt(now: Instant): Instant? {
            windowEnd?.let { return it }
            val seconds = secondsRemaining?.takeIf { it > 0.0 && it.isFinite() } ?: return null
            return Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + (seconds * 1000.0).toLong())
        }

        val hasUsageData: Boolean
            get() = usedPercent > 0.0 || windowEnd != null || (secondsRemaining ?: 0.0) > 0.0
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
            extraUsageBalanceCents = root["extraUsageBalanceCents"]?.jsonPrimitive?.longOrNull,
        )
    }

    private fun parsePool(pool: JsonObject): Map<String, DroidBillingWindow> {
        val windows = mutableMapOf<String, DroidBillingWindow>()
        for (key in listOf("fiveHour", "weekly", "monthly")) {
            val element = pool[key] as? JsonObject ?: continue
            windows[key] = DroidBillingWindow(
                usedPercent = element["usedPercent"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                windowEnd = element["windowEnd"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                secondsRemaining = element["secondsRemaining"]?.jsonPrimitive?.doubleOrNull,
            )
        }
        return windows
    }

    internal fun windowsFromBillingLimits(billing: DroidBillingLimits): List<UsageWindow> {
        val now = Clock.System.now()
        val windows = mutableListOf<UsageWindow>()
        billing.standard?.let { pool ->
            pool["fiveHour"]?.let { windows += it.toUsageWindow("droid-standard-5h", "Standard 5-hour", FIVE_HOUR_SECONDS, now) }
            pool["weekly"]?.let { windows += it.toUsageWindow("droid-standard-weekly", "Standard weekly", WEEK_SECONDS, now) }
            pool["monthly"]?.let { windows += it.toUsageWindow("droid-standard-monthly", "Standard monthly", MONTH_SECONDS, now) }
        }
        billing.core?.let { pool ->
            pool["fiveHour"]?.takeIf { it.hasUsageData }?.let { windows += it.toUsageWindow("droid-core-5h", "Core 5-hour", FIVE_HOUR_SECONDS, now) }
            pool["weekly"]?.takeIf { it.hasUsageData }?.let { windows += it.toUsageWindow("droid-core-weekly", "Core weekly", WEEK_SECONDS, now) }
            pool["monthly"]?.takeIf { it.hasUsageData }?.let { windows += it.toUsageWindow("droid-core-monthly", "Core monthly", MONTH_SECONDS, now) }
        }
        return windows
    }

    private fun DroidBillingWindow.toUsageWindow(id: String, label: String, durationSeconds: Long, now: Instant): UsageWindow {
        return UsageWindow(
            id = id,
            label = label,
            usedRatio = normalizePercent(usedPercent),
            resetsAt = resetsAt(now),
            durationSeconds = durationSeconds,
        )
    }

    internal fun windowsFromSubscriptionUsage(root: JsonObject): List<UsageWindow> {
        val usage = root["usage"] as? JsonObject
            ?: throw ProviderException(AuthState.Error, "Droid usage response had no usage object.")
        val resetsAt = usage["endDate"]?.jsonPrimitive?.longOrNull?.let(Instant::fromEpochMilliseconds)
        val durationSeconds = usage["startDate"]?.jsonPrimitive?.longOrNull
            ?.let { start -> usage["endDate"]?.jsonPrimitive?.longOrNull?.let { end -> (end - start) / 1000L } }

        val windows = mutableListOf<UsageWindow>()
        (usage["standard"] as? JsonObject)?.let { standard ->
            standard["usedRatio"]?.jsonPrimitive?.doubleOrNull?.let { ratio ->
                windows += UsageWindow(
                    id = "droid-standard",
                    label = "Standard tokens",
                    usedRatio = ratio.coerceIn(0.0, 1.0),
                    resetsAt = resetsAt,
                    durationSeconds = durationSeconds,
                )
            }
        }
        (usage["premium"] as? JsonObject)?.let { premium ->
            val allowance = premium["totalAllowance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val ratio = premium["usedRatio"]?.jsonPrimitive?.doubleOrNull
            if (allowance > 0.0 && ratio != null) {
                windows += UsageWindow(
                    id = "droid-premium",
                    label = "Premium tokens",
                    usedRatio = ratio.coerceIn(0.0, 1.0),
                    resetsAt = resetsAt,
                    durationSeconds = durationSeconds,
                )
            }
        }
        return windows
    }

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
        return DroidIdentity(email = email, planLabel = planLabel)
    }

    private fun JsonObject.stringField(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
