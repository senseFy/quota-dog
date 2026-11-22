package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.quotadog.hostsync.LocalQuota
import saien.quotadog.hostsync.QuotaDetails
import saien.quotadog.hostsync.QuotaWindowKind
import saien.quotadog.hostsync.quotaWindow

internal data class DevinUsageSnapshot(
    val planName: String?,
    val email: String?,
    val windows: List<UsageWindow>,
    val overageBalanceDollars: Double? = null,
    val localQuota: LocalQuota,
)

/**
 * Reads Devin account quota through the same Connect RPC the Devin CLI uses:
 * `POST {apiServerUrl}/exa.seat_management_pb.SeatManagementService/GetUserStatus`
 * with the `windsurf_api_key` from `credentials.toml` in `metadata.apiKey`.
 */
internal object DevinUsageFetcher {
    private const val SERVICE = "exa.seat_management_pb.SeatManagementService"
    private const val COMPAT_VERSION = "1.108.2"
    private const val DAY_SECONDS = 24L * 60L * 60L
    private const val WEEK_SECONDS = 7L * 24L * 60L * 60L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(httpClient: HttpClient, apiKey: String, apiServerUrl: String?): DevinUsageSnapshot {
        val base = apiServerUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: DevinAuthParser.DEFAULT_API_SERVER_URL
        val response = httpClient.post("$base/$SERVICE/GetUserStatus") {
            header("Connect-Protocol-Version", "1")
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("metadata", buildJsonObject {
                    put("apiKey", apiKey)
                    put("ideName", "devin")
                    put("ideVersion", COMPAT_VERSION)
                    put("extensionName", "devin")
                    put("extensionVersion", COMPAT_VERSION)
                    put("locale", "en")
                })
            }.toString())
        }
        return parseResponse(response)
    }

    internal suspend fun parseResponse(response: HttpResponse): DevinUsageSnapshot {
        val statusCode = response.status.value
        val body = response.bodyAsText()
        if (statusCode == 401 || statusCode == 403) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Devin rejected credentials. Run `devin auth login` again, then re-import.",
                statusCode,
            )
        }
        if (!response.status.isSuccess()) {
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(
                AuthState.Error,
                "Devin usage request failed: HTTP $statusCode: $preview",
                statusCode,
            )
        }
        return parseUserStatusJson(body)
    }

    internal fun parseUserStatusJson(text: String): DevinUsageSnapshot {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Devin usage response was not valid JSON.")
        }
        val userStatus = root["userStatus"] as? JsonObject
            ?: throw ProviderException(AuthState.Error, "Devin usage response had no userStatus.")
        val planStatus = userStatus["planStatus"] as? JsonObject ?: JsonObject(emptyMap())
        val planInfo = planStatus["planInfo"] as? JsonObject ?: JsonObject(emptyMap())

        val planName = planInfo["planName"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
        val email = userStatus["email"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
        val hideDailyQuota = planInfo["hideDailyQuota"]?.jsonPrimitive?.booleanOrNull == true

        val dailyRemaining = planStatus["dailyQuotaRemainingPercent"]?.jsonPrimitive?.numberOrNull()
        val weeklyRemaining = planStatus["weeklyQuotaRemainingPercent"]?.jsonPrimitive?.numberOrNull()
        val dailyReset = planStatus["dailyQuotaResetAtUnix"]?.jsonPrimitive?.toInstant()
        val weeklyReset = planStatus["weeklyQuotaResetAtUnix"]?.jsonPrimitive?.toInstant()
        val overageBalanceDollars = planStatus["overageBalanceMicros"]?.jsonPrimitive?.numberOrNull()
            ?.let { micros -> micros / 1_000_000.0 }

        val windows = mutableListOf<UsageWindow>()
        // Devin reports quota as percent *remaining*; UsageWindow wants the *used* ratio.
        if (!hideDailyQuota && dailyRemaining != null) {
            windows += UsageWindow(
                id = "devin-daily",
                label = "Daily quota",
                usedRatio = 1.0 - normalizePercent(dailyRemaining),
                resetsAt = dailyReset,
                durationSeconds = DAY_SECONDS,
            )
        }
        if (weeklyRemaining != null) {
            windows += UsageWindow(
                id = "devin-weekly",
                label = "Weekly quota",
                usedRatio = 1.0 - normalizePercent(weeklyRemaining),
                resetsAt = weeklyReset,
                durationSeconds = WEEK_SECONDS,
            )
        }

        val sharedWindows = buildList {
            if (!hideDailyQuota && (dailyRemaining != null || dailyReset != null)) {
                add(quotaWindow(QuotaWindowKind.DevinDaily, dailyRemaining?.let { 1 - normalizePercent(it) }, dailyReset, DAY_SECONDS))
            }
            if (weeklyRemaining != null || weeklyReset != null) {
                add(quotaWindow(QuotaWindowKind.DevinWeekly, weeklyRemaining?.let { 1 - normalizePercent(it) }, weeklyReset, WEEK_SECONDS))
            }
        }
        val localQuota = LocalQuota(ProviderId.DEVIN, sharedWindows, overageBalanceDollars?.let { QuotaDetails.Devin(it) })
        // Import can identify the login even when Devin exposes no quota window. Refresh requires a reading.
        if (!localQuota.hasAccountReading && email == null) {
            throw ProviderException(AuthState.Error, "Devin did not report quota data for this account.")
        }

        return DevinUsageSnapshot(
            planName = planName,
            email = email,
            windows = windows,
            overageBalanceDollars = overageBalanceDollars,
            localQuota = localQuota,
        )
    }

    /** Proto3 JSON emits int64 fields as strings; accept both numbers and strings. */
    private fun JsonPrimitive.numberOrNull(): Double? {
        return doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
    }

    private fun JsonPrimitive.toInstant(): Instant? {
        val seconds = contentOrNull?.toLongOrNull()?.takeIf { it in 1..253402300799L } ?: return null
        return runCatching { Instant.fromEpochSeconds(seconds) }.getOrNull()
    }
}
