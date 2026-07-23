package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AntigravityUsageSnapshot(
    val windows: List<UsageWindow>,
    val planLabel: String?,
    val projectId: String?,
)

/**
 * Fetches Antigravity quota via Google Cloud Code internal APIs used by the CLI/IDE.
 *
 * Primary: `retrieveUserQuotaSummary`
 * Fallback: `fetchAvailableModels` (per-model remainingFraction)
 */
internal object AntigravityUsageFetcher {
    private const val QUOTA_SUMMARY_URL =
        "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary"
    private const val AVAILABLE_MODELS_URL =
        "https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels"
    private const val LOAD_CODE_ASSIST_URL =
        "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"

    // Public OAuth client embedded by Antigravity / CLIProxy-compatible tools.
    internal const val OAUTH_CLIENT_ID =
        "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    internal const val OAUTH_CLIENT_SECRET = "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"

    private const val USER_AGENT = "antigravity/1.11.3 Darwin/arm64"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(httpClient: HttpClient, accessToken: String): AntigravityUsageSnapshot {
        val codeAssist = postCloudCode(
            httpClient = httpClient,
            url = LOAD_CODE_ASSIST_URL,
            accessToken = accessToken,
            payload = """{"metadata":{"ideType":"ANTIGRAVITY"}}""",
            allowFailure = true,
        )
        val projectId = codeAssist?.stringField("cloudaicompanionProject")
            ?: codeAssist?.stringField("cloudaiCompanionProject")
            ?: codeAssist?.stringField("project")
        val planLabel = codeAssist?.get("currentTier")?.jsonObject?.stringField("name")
            ?: codeAssist?.get("currentTier")?.jsonObject?.stringField("id")
        val summaryWindows = fetchQuotaSummaryWindows(httpClient, accessToken, projectId)
        val windows = if (!summaryWindows.isNullOrEmpty()) {
            summaryWindows
        } else {
            fetchAvailableModelWindows(httpClient, accessToken, projectId)
        }
        if (windows.isEmpty()) {
            throw ProviderException(
                AuthState.Error,
                "Antigravity returned no quota windows for this account.",
            )
        }
        return AntigravityUsageSnapshot(
            windows = windows,
            planLabel = planLabel,
            projectId = projectId,
        )
    }

    suspend fun fetchUserEmail(httpClient: HttpClient, accessToken: String): String? {
        val response = httpClient.get(USERINFO_URL) {
            header("Authorization", "Bearer $accessToken")
            header("Accept", "application/json")
            header("User-Agent", USER_AGENT)
        }
        if (response.status.value == 401 || response.status.value == 403) return null
        if (!response.status.isSuccess()) return null
        val root = runCatching {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        }.getOrNull() ?: return null
        return root["email"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun refreshAccessToken(
        httpClient: HttpClient,
        refreshToken: String,
    ): OAuthTokenBundle {
        val response = httpClient.post(TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("User-Agent", USER_AGENT)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("client_id", OAUTH_CLIENT_ID)
                        append("client_secret", OAUTH_CLIENT_SECRET)
                        append("refresh_token", refreshToken)
                        append("grant_type", "refresh_token")
                    },
                ),
            )
        }
        val status = response.status.value
        val body = response.bodyAsText()
        if (status == 401 || status == 403) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Antigravity rejected the refresh token. Run `agy` and sign in again.",
                status,
            )
        }
        if (!response.status.isSuccess()) {
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(
                AuthState.Error,
                "Antigravity token refresh failed: HTTP $status: $preview",
                status,
            )
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Antigravity token refresh returned invalid JSON.")
        }
        val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            throw ProviderException(AuthState.Error, "Antigravity token refresh missing access_token.")
        }
        val expiresIn = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        val nextRefresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: refreshToken
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = nextRefresh,
            expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() + expiresIn * 1_000L,
        )
    }

    private suspend fun fetchQuotaSummaryWindows(
        httpClient: HttpClient,
        accessToken: String,
        projectId: String?,
    ): List<UsageWindow>? {
        val payloads = buildList {
            if (!projectId.isNullOrBlank()) add("""{"project":${jsonPrimitive(projectId)}}""")
            add("{}")
        }
        for (payload in payloads) {
            val root = postCloudCode(
                httpClient = httpClient,
                url = QUOTA_SUMMARY_URL,
                accessToken = accessToken,
                payload = payload,
                allowFailure = true,
            ) ?: continue
            val windows = parseQuotaSummary(root)
            if (windows.isNotEmpty()) return windows
        }
        return null
    }

    private suspend fun fetchAvailableModelWindows(
        httpClient: HttpClient,
        accessToken: String,
        projectId: String?,
    ): List<UsageWindow> {
        val payload = if (!projectId.isNullOrBlank()) {
            """{"project":${jsonPrimitive(projectId)}}"""
        } else {
            "{}"
        }
        val root = postCloudCode(
            httpClient = httpClient,
            url = AVAILABLE_MODELS_URL,
            accessToken = accessToken,
            payload = payload,
            allowFailure = false,
        ) ?: return emptyList()
        return parseAvailableModels(root)
    }

    private suspend fun postCloudCode(
        httpClient: HttpClient,
        url: String,
        accessToken: String,
        payload: String,
        allowFailure: Boolean,
    ): JsonObject? {
        val response = httpClient.post(url) {
            header("Authorization", "Bearer $accessToken")
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        return parseCloudCodeResponse(response, label = url.substringAfterLast(':'), allowFailure = allowFailure)
    }

    internal suspend fun parseCloudCodeResponse(
        response: HttpResponse,
        label: String,
        allowFailure: Boolean,
    ): JsonObject? {
        val status = response.status.value
        val body = response.bodyAsText()
        if (status == 401 || status == 403) {
            if (allowFailure) return null
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Antigravity rejected credentials. Run `agy` and sign in again.",
                status,
            )
        }
        if (!response.status.isSuccess()) {
            if (allowFailure) return null
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(
                AuthState.Error,
                "Antigravity $label failed: HTTP $status: $preview",
                status,
            )
        }
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            if (allowFailure) null
            else throw ProviderException(AuthState.Error, "Antigravity $label returned invalid JSON.")
        }
    }

    internal fun parseQuotaSummary(root: JsonObject): List<UsageWindow> {
        val groups = quotaSummaryGroups(root) ?: return emptyList()
        val windows = mutableListOf<UsageWindow>()
        for (groupElement in groups) {
            val group = groupElement as? JsonObject ?: continue
            val groupName = group.stringField("displayName")
                ?: group.stringField("name")
                ?: continue
            val groupId = quotaSummaryGroupId(groupName) ?: continue
            val buckets = group["buckets"] as? JsonArray ?: continue
            for (bucketElement in buckets) {
                val bucket = bucketElement as? JsonObject ?: continue
                if (bucket["disabled"]?.jsonPrimitive?.booleanOrNull == true) continue
                val bucketId = bucket.stringField("bucketId") ?: bucket.stringField("id") ?: ""
                val bucketName = bucket.stringField("displayName")
                    ?: bucket.stringField("name")
                    ?: bucketId
                val bucketWindow = bucket.stringField("window") ?: ""
                val period = quotaSummaryPeriod("$bucketId $bucketName $bucketWindow") ?: continue
                val remainingFraction = quotaSummaryRemainingFraction(bucket) ?: continue
                val usedRatio = (1.0 - remainingFraction.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
                val resetAt = parseInstant(
                    bucket.stringField("resetTime")
                        ?: bucket.stringField("reset_time")
                        ?: bucket.stringField("resetAt")
                        ?: bucket.stringField("reset_at"),
                )
                val id = "antigravity-$groupId-${period.id}"
                windows += UsageWindow(
                    id = id,
                    label = "${period.groupLabel(groupId)} · ${period.displayName}",
                    usedRatio = usedRatio,
                    resetsAt = resetAt,
                    durationSeconds = period.durationSeconds,
                )
            }
        }
        val order = listOf(
            "antigravity-gemini-session",
            "antigravity-gemini-weekly",
            "antigravity-claude-gpt-session",
            "antigravity-claude-gpt-weekly",
        )
        val seen = linkedSetOf<String>()
        return windows
            .filter { seen.add(it.id) }
            .sortedWith(
                compareBy<UsageWindow> { order.indexOf(it.id).let { index -> if (index < 0) order.size else index } }
                    .thenBy { it.resetsAt?.toEpochMilliseconds() ?: Long.MAX_VALUE },
            )
    }

    internal fun parseAvailableModels(root: JsonObject): List<UsageWindow> {
        val models = root["models"]?.jsonObject ?: return emptyList()
        val windows = mutableListOf<UsageWindow>()
        for ((name, infoElement) in models) {
            val lower = name.lowercase()
            if (!lower.contains("gemini") && !lower.contains("claude") && !lower.contains("gpt")) {
                continue
            }
            val info = infoElement as? JsonObject ?: continue
            val quotaInfo = info["quotaInfo"]?.jsonObject ?: continue
            val remaining = quotaInfo["remainingFraction"]?.jsonPrimitive?.doubleOrNull
                ?: quotaInfo["remaining_fraction"]?.jsonPrimitive?.doubleOrNull
                ?: continue
            val usedRatio = (1.0 - remaining.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
            val resetAt = parseInstant(
                quotaInfo.stringField("resetTime") ?: quotaInfo.stringField("reset_time"),
            )
            val display = info.stringField("displayName") ?: name
            windows += UsageWindow(
                id = "model-$name",
                label = display,
                usedRatio = usedRatio,
                resetsAt = resetAt,
                durationSeconds = FIVE_HOUR_SECONDS,
            )
        }
        return windows
            .sortedBy { it.resetsAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }
            .take(12)
    }

    private fun quotaSummaryGroups(root: JsonObject): JsonArray? {
        root["groups"]?.jsonArray?.let { return it }
        root["response"]?.jsonObject?.get("groups")?.jsonArray?.let { return it }
        root["summary"]?.jsonObject?.get("groups")?.jsonArray?.let { return it }
        return null
    }

    private fun quotaSummaryGroupId(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.contains("gemini") -> "gemini"
            lower.contains("claude") || lower.contains("gpt") || lower.contains("3p") -> "claude-gpt"
            else -> null
        }
    }

    private data class Period(val id: String, val displayName: String, val durationSeconds: Long) {
        fun groupLabel(groupId: String): String = when (groupId) {
            "gemini" -> "Gemini"
            "claude-gpt" -> "Claude/GPT"
            else -> groupId
        }
    }

    private fun quotaSummaryPeriod(label: String): Period? {
        val lower = label.lowercase()
        return when {
            lower.contains("week") || lower.contains("7d") || lower.contains("seven") ->
                Period("weekly", "Weekly", SEVEN_DAY_SECONDS)
            lower.contains("session") ||
                lower.contains("5h") ||
                lower.contains("5-hour") ||
                lower.contains("five hour") ||
                lower.contains("five-hour") ||
                Regex("""\b5\b""").containsMatchIn(lower) ->
                Period("session", "5-hour", FIVE_HOUR_SECONDS)
            else -> null
        }
    }

    private fun quotaSummaryRemainingFraction(bucket: JsonObject): Double? {
        bucket["remainingFraction"]?.jsonPrimitive?.doubleOrNull?.let { return it }
        bucket["remaining_fraction"]?.jsonPrimitive?.doubleOrNull?.let { return it }
        val remaining = bucket["remaining"] as? JsonObject ?: return null
        remaining["remainingFraction"]?.jsonPrimitive?.doubleOrNull?.let { return it }
        remaining["remaining_fraction"]?.jsonPrimitive?.doubleOrNull?.let { return it }
        if (remaining.stringField("case") == "remainingFraction") {
            return remaining["value"]?.jsonPrimitive?.doubleOrNull
        }
        return null
    }

    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching {
                Instant.parse(value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2"))
            }.getOrNull()
    }

    private fun JsonObject.stringField(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun jsonPrimitive(value: String): String {
        return JsonPrimitive(value).toString()
    }

    private const val FIVE_HOUR_SECONDS = 5L * 60L * 60L
    private const val SEVEN_DAY_SECONDS = 7L * 24L * 60L * 60L
}
