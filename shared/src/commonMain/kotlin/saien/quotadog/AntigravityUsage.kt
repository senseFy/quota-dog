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
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.quotadog.hostsync.LocalQuota
import saien.quotadog.hostsync.QuotaWindow
import saien.quotadog.hostsync.QuotaWindowKind
import saien.quotadog.hostsync.quotaWindow

internal data class AntigravityUsageSnapshot(
    val windows: List<UsageWindow>,
    val planLabel: String?,
    val projectId: String?,
    val localQuota: LocalQuota,
    val email: String?,
)

internal class AntigravityQuery(
    val windows: List<UsageWindow>,
    val quotaWindows: List<QuotaWindow>,
    private val knownPools: List<String>?,
) {
    // Only the project in the request which actually returned this quota is a target. A
    // project discovered by loadCodeAssist must not be attributed to a subsequent {} retry.
    fun localQuota(googleUserId: String?, project: String?, backend: String): LocalQuota {
        val user = googleUserId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        val scope = if (user != null && !project.isNullOrBlank() && !knownPools.isNullOrEmpty()) {
            listOf(user, project, backend) + knownPools.sorted()
        } else null
        return LocalQuota(ProviderId.ANTIGRAVITY, quotaWindows, matchParts = scope)
    }
}

/**
 * Fetches Antigravity quota via Google Cloud Code internal APIs used by the CLI/IDE.
 *
 * Primary: `retrieveUserQuotaSummary`
 * Fallback: `fetchAvailableModels` (per-model remainingFraction)
 */
internal object AntigravityUsageFetcher {
    private const val DAILY_CLOUD_CODE_BASE_URL = "https://daily-cloudcode-pa.googleapis.com"
    private const val PROD_CLOUD_CODE_BASE_URL = "https://cloudcode-pa.googleapis.com"
    private val CLOUD_CODE_BASE_URLS = listOf(DAILY_CLOUD_CODE_BASE_URL, PROD_CLOUD_CODE_BASE_URL)
    private const val LOAD_CODE_ASSIST_PATH = "/v1internal:loadCodeAssist"
    private const val QUOTA_SUMMARY_PATH = "/v1internal:retrieveUserQuotaSummary"
    private const val AVAILABLE_MODELS_PATH = "/v1internal:fetchAvailableModels"
    internal const val OAUTH_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    internal const val OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
    internal const val OAUTH_CALLBACK_PORT = 51121
    internal const val OAUTH_CALLBACK_PATH = "/oauth-callback"
    internal const val OAUTH_REDIRECT_URI = "http://localhost:$OAUTH_CALLBACK_PORT$OAUTH_CALLBACK_PATH"
    internal const val OAUTH_SCOPE =
        "https://www.googleapis.com/auth/cloud-platform " +
            "https://www.googleapis.com/auth/userinfo.email " +
            "https://www.googleapis.com/auth/userinfo.profile " +
            "https://www.googleapis.com/auth/cclog " +
            "https://www.googleapis.com/auth/experimentsandconfigs"

    private const val TOKEN_URL = OAUTH_TOKEN_URL
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"

    // Public OAuth client embedded by Antigravity / CLIProxy-compatible tools.
    internal const val OAUTH_CLIENT_ID =
        "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    internal const val OAUTH_CLIENT_SECRET = "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"

    // Cloud Code gates Gemini quota buckets on a recent Antigravity Hub User-Agent.
    private const val USER_AGENT = "antigravity/hub/2.24.0"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(httpClient: HttpClient, accessToken: String): AntigravityUsageSnapshot {
        val codeAssist = CLOUD_CODE_BASE_URLS.firstNotNullOfOrNull { baseUrl ->
            postCloudCode(
                httpClient = httpClient,
                url = "$baseUrl$LOAD_CODE_ASSIST_PATH",
                accessToken = accessToken,
                payload = """{"metadata":{"ideType":"ANTIGRAVITY"}}""",
                allowFailure = true,
            )
        }
        val projectId = codeAssist?.stringField("cloudaicompanionProject")
            ?: codeAssist?.stringField("cloudaiCompanionProject")
            ?: codeAssist?.stringField("project")
        val planLabel = extractPlanLabel(codeAssist)
        val result = fetchQuotaSummaryWindows(httpClient, accessToken, projectId)
            ?: fetchAvailableModelWindows(httpClient, accessToken, projectId)
        if (result == null) {
            throw ProviderException(
                AuthState.Error,
                "Antigravity returned no quota windows for this account.",
            )
        }
        val userInfo = try {
            fetchUserInfo(httpClient, accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // A separate identity lookup is optional; never lose a successful quota reading.
        }
        val id = userInfo?.stringField("id")?.takeIf { userInfo["sub"] == null || userInfo.stringField("sub") == it }
        return AntigravityUsageSnapshot(
            windows = result.query.windows,
            planLabel = planLabel,
            projectId = result.project,
            localQuota = result.query.localQuota(id, result.project, result.backend),
            email = userInfo?.stringField("email"),
        )
    }

    internal fun extractPlanLabel(codeAssist: JsonObject?): String? {
        val paidTier = codeAssist?.get("paidTier") as? JsonObject
        val currentTier = codeAssist?.get("currentTier") as? JsonObject
        return paidTier?.stringField("name")
            ?: paidTier?.stringField("id")
            ?: currentTier?.stringField("name")
            ?: currentTier?.stringField("id")
    }

    suspend fun fetchUserEmail(httpClient: HttpClient, accessToken: String) = fetchUserInfo(httpClient, accessToken)?.stringField("email")

    private suspend fun fetchUserInfo(httpClient: HttpClient, accessToken: String): JsonObject? {
        val response = httpClient.get(USERINFO_URL) {
            header("Authorization", "Bearer $accessToken")
            header("Accept", "application/json")
            header("User-Agent", USER_AGENT)
        }
        if (response.status.value == 401 || response.status.value == 403) return null
        if (!response.status.isSuccess()) return null
        return runCatching {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        }.getOrNull()
    }

    suspend fun exchangeAuthorizationCode(
        httpClient: HttpClient,
        code: String,
        codeVerifier: String,
        redirectUri: String = OAUTH_REDIRECT_URI,
    ): OAuthTokenBundle {
        val response = httpClient.post(TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("User-Agent", USER_AGENT)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("client_id", OAUTH_CLIENT_ID)
                        append("client_secret", OAUTH_CLIENT_SECRET)
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("code_verifier", codeVerifier)
                        append("grant_type", "authorization_code")
                    },
                ),
            )
        }
        val bundle = parseTokenResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            fallbackRefreshToken = "",
            contextLabel = "token exchange",
        )
        val email = bundle.email ?: runCatching {
            fetchUserEmail(httpClient, bundle.accessToken)
        }.getOrNull()
        return if (!email.isNullOrBlank()) bundle.copy(email = email) else bundle
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
        return parseTokenResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            fallbackRefreshToken = refreshToken,
            contextLabel = "token refresh",
        )
    }

    internal fun parseTokenResponse(
        status: Int,
        body: String,
        fallbackRefreshToken: String = "",
        contextLabel: String = "token exchange",
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): OAuthTokenBundle {
        val errorCode = runCatching {
            json.parseToJsonElement(body).jsonObject.stringField("error")
        }.getOrNull()
        if (status == 401 || status == 403 || (status == 400 && errorCode == "invalid_grant")) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Antigravity rejected the Google session. Sign in with Google again or re-import from `agy`.",
                status,
            )
        }
        if (status !in 200..299) {
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(
                AuthState.Error,
                "Antigravity $contextLabel failed: HTTP $status: $preview",
                status,
            )
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Antigravity $contextLabel returned invalid JSON.")
        }
        val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            throw ProviderException(AuthState.Error, "Antigravity $contextLabel missing access_token.")
        }
        val expiresIn = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        val nextRefresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackRefreshToken
        val idToken = root["id_token"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = nextRefresh,
            idToken = idToken,
            expiresAtEpochMillis = nowEpochMillis + expiresIn * 1_000L,
        )
    }

    private class Result(val query: AntigravityQuery, val project: String?, val backend: String)

    private fun projectPayload(project: String?) = if (project != null) """{"project":${JsonPrimitive(project)}}""" else "{}"

    private suspend fun fetchQuotaSummaryWindows(
        httpClient: HttpClient,
        accessToken: String,
        projectId: String?,
    ): Result? {
        for (baseUrl in CLOUD_CODE_BASE_URLS) {
            for (target in listOf(projectId, null).distinct()) {
                val root = postCloudCode(
                    httpClient = httpClient,
                    url = "$baseUrl$QUOTA_SUMMARY_PATH",
                    accessToken = accessToken,
                    payload = projectPayload(target),
                    allowFailure = true,
                ) ?: continue
                val query = quotaSummary(root)
                if (query.quotaWindows.isNotEmpty()) return Result(query, confirmedTarget(target, root), baseUrl)
            }
        }
        return null
    }

    private suspend fun fetchAvailableModelWindows(
        httpClient: HttpClient,
        accessToken: String,
        projectId: String?,
    ): Result? {
        CLOUD_CODE_BASE_URLS.forEachIndexed { index, baseUrl ->
            val isLast = index == CLOUD_CODE_BASE_URLS.lastIndex
            val root = postCloudCode(
                httpClient = httpClient,
                url = "$baseUrl$AVAILABLE_MODELS_PATH",
                accessToken = accessToken,
                payload = projectPayload(projectId),
                allowFailure = !isLast,
            ) ?: return@forEachIndexed
            val query = availableModels(root)
            if (query.quotaWindows.isNotEmpty()) return Result(query, confirmedTarget(projectId, root), baseUrl)
        }
        return null
    }

    private fun confirmedTarget(requestedProject: String?, root: JsonObject): String? {
        val target = requestedProject?.takeIf { it.length in 1..128 } ?: return null
        // Both flat and wrapped quota summaries are supported. A contradictory project echo in
        // either layer invalidates identity, not the returned usage.
        val echoes = listOfNotNull(root, root["response"] as? JsonObject, root["summary"] as? JsonObject)
            .mapNotNull { it["project"] }
        return target.takeIf { echoes.all { it is JsonPrimitive && it.isString && it.content == target } }
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
                "Antigravity rejected credentials. Sign in with Google again or re-import from `agy`.",
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

    internal fun parseQuotaSummary(root: JsonObject) = quotaSummary(root).windows

    internal fun quotaSummary(root: JsonObject): AntigravityQuery {
        val groups = quotaSummaryGroups(root).orEmpty()
        val windows = mutableListOf<UsageWindow>()
        val shared = mutableListOf<QuotaWindow>()
        val poolIds = mutableSetOf<String>()
        var allKnown = true
        var unknownSlot = 0
        for (groupElement in groups) {
            val group = groupElement as? JsonObject ?: run { allKnown = false; continue }
            val groupName = group.stringField("displayName")
                ?: group.stringField("name")
                ?: run { allKnown = false; continue }
            val groupId = quotaSummaryGroupId(groupName) ?: "other"
            var slot: Int? = null
            val buckets = group["buckets"] as? JsonArray ?: run { allKnown = false; continue }
            for (bucketElement in buckets) {
                val bucket = bucketElement as? JsonObject ?: run { allKnown = false; continue }
                if ((bucket["disabled"] as? JsonPrimitive)?.booleanOrNull == true) continue
                val bucketId = bucket.stringField("bucketId") ?: bucket.stringField("id") ?: ""
                val bucketName = bucket.stringField("displayName")
                    ?: bucket.stringField("name")
                    ?: bucketId
                val bucketWindow = bucket.stringField("window") ?: ""
                val period = quotaSummaryPeriod("$bucketId $bucketName $bucketWindow")
                if (period == null) { allKnown = false; continue }
                val resetAt = parseInstant(
                    bucket.stringField("resetTime")
                        ?: bucket.stringField("reset_time")
                        ?: bucket.stringField("resetAt")
                        ?: bucket.stringField("reset_at"),
                )
                // Proto3 JSON omits remainingFraction when its value is 0.0 (exhausted).
                val usedRatio = remainingUsedRatio(bucket, resetAt)
                val known = summaryBucketKinds[bucketId]?.takeIf { (_, id) -> id == "$groupId-${period.id}" }?.first
                if (known == null || !poolIds.add(bucketId)) allKnown = false
                val kind = known ?: if (period.id == "weekly") QuotaWindowKind.OtherGroupWeekly else QuotaWindowKind.OtherGroupFiveHour
                if (known == null && slot == null) slot = ++unknownSlot
                val number = if (known == null) slot else null
                if (shared.size < 16 && shared.none { it.kind == kind && it.slot == number }) {
                    shared += quotaWindow(kind, usedRatio, resetAt, period.durationSeconds, slot = number)
                }
                if (usedRatio == null) continue
                val id = "antigravity-$groupId-${period.id}"
                windows += UsageWindow(
                    id = id,
                    label = "${period.groupLabel(groupId, groupName)} · ${period.displayName}",
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
            "antigravity-gemini-argon-session",
            "antigravity-gemini-argon-weekly",
        )
        val seen = linkedSetOf<String>()
        val ordered = windows
            .filter { seen.add(it.id) }
            .sortedWith(
                compareBy<UsageWindow> { order.indexOf(it.id).let { index -> if (index < 0) order.size else index } }
                    .thenBy { it.resetsAt?.toEpochMilliseconds() ?: Long.MAX_VALUE },
            )
        return AntigravityQuery(ordered, shared.sortedBy { it.kind.ordinal }, poolIds.toList().takeIf { allKnown })
    }

    internal fun parseAvailableModels(root: JsonObject) = availableModels(root).windows

    internal fun availableModels(root: JsonObject): AntigravityQuery {
        val models = root["models"] as? JsonObject
        val windows = mutableListOf<UsageWindow>()
        val shared = mutableListOf<QuotaWindow>()
        var unknownSlot = 0
        // Number unfamiliar models locally in stable machine-key order. Never send their raw keys or display names.
        for ((name, infoElement) in models.orEmpty().entries.sortedBy { it.key }) {
            val info = infoElement as? JsonObject ?: continue
            val quotaInfo = info["quotaInfo"] as? JsonObject ?: continue
            val resetAt = parseInstant(
                quotaInfo.stringField("resetTime") ?: quotaInfo.stringField("reset_time"),
            )
            val usedRatio = remainingUsedRatio(quotaInfo, resetAt)
            val kind = when (name) {
                "gemini-2.5-pro" -> QuotaWindowKind.Gemini25Pro
                "gemini-3-flash" -> QuotaWindowKind.Gemini3Flash
                "claude-sonnet-4" -> QuotaWindowKind.ClaudeSonnet4
                "claude-sonnet-4-5" -> QuotaWindowKind.ClaudeSonnet45
                else -> QuotaWindowKind.OtherModel
            }
            if (shared.size < 12) shared += quotaWindow(kind, usedRatio, resetAt, null,
                slot = if (kind == QuotaWindowKind.OtherModel) ++unknownSlot else null)
            if (usedRatio == null || listOf("gemini", "claude", "gpt").none { name.contains(it, ignoreCase = true) }) continue
            val display = info.stringField("displayName") ?: name
            windows += UsageWindow(
                id = "model-$name",
                label = display,
                usedRatio = usedRatio,
                resetsAt = resetAt,
                durationSeconds = FIVE_HOUR_SECONDS,
            )
        }
        // Model identifiers do not establish which underlying quota pool is being charged.
        return AntigravityQuery(windows.sortedBy { it.resetsAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }.take(12), shared, null)
    }

    private fun quotaSummaryGroups(root: JsonObject): JsonArray? {
        return root["groups"] as? JsonArray ?: (root["response"] as? JsonObject)?.get("groups") as? JsonArray
            ?: (root["summary"] as? JsonObject)?.get("groups") as? JsonArray
    }

    // Machine bucket IDs observed in retrieveUserQuotaSummary, not heuristic matches on human-readable labels.
    private val summaryBucketKinds = mapOf(
        "gemini-5h" to (QuotaWindowKind.GeminiFiveHour to "gemini-session"),
        "gemini-weekly" to (QuotaWindowKind.GeminiWeekly to "gemini-weekly"),
        "3p-5h" to (QuotaWindowKind.ClaudeGptFiveHour to "claude-gpt-session"),
        "3p-weekly" to (QuotaWindowKind.ClaudeGptWeekly to "claude-gpt-weekly"),
        "gemini-argon-wtu-5h" to (QuotaWindowKind.ArgonFiveHour to "gemini-argon-session"),
        "gemini-argon-wtu-weekly" to (QuotaWindowKind.ArgonWeekly to "gemini-argon-weekly"),
    )

    private fun quotaSummaryGroupId(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.contains("argon") -> "gemini-argon"
            lower.contains("gemini") -> "gemini"
            lower.contains("claude") || lower.contains("gpt") || lower.contains("3p") -> "claude-gpt"
            else -> null
        }
    }

    private data class Period(val id: String, val displayName: String, val durationSeconds: Long) {
        fun groupLabel(groupId: String, rawGroupName: String): String = when (groupId) {
            "gemini" -> "Gemini"
            "gemini-argon" -> rawGroupName.trim().ifEmpty { "Gemini Argon" }
            "claude-gpt" -> "Claude/GPT"
            else -> rawGroupName.trim().ifEmpty { groupId }
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

    private fun remainingUsedRatio(bucket: JsonObject, resetAt: Instant?): Double? {
        val remaining = quotaSummaryRemainingFraction(bucket)
            ?: if (resetAt != null && listOf("remainingFraction", "remaining_fraction", "remaining").none { it in bucket }) 0.0 else return null
        return if (remaining in 0.0..1.0) 1.0 - remaining else null
    }

    private fun quotaSummaryRemainingFraction(bucket: JsonObject): Double? {
        (bucket["remainingFraction"] as? JsonPrimitive)?.doubleOrNull?.let { return it }
        (bucket["remaining_fraction"] as? JsonPrimitive)?.doubleOrNull?.let { return it }
        val remaining = bucket["remaining"] as? JsonObject ?: return null
        (remaining["remainingFraction"] as? JsonPrimitive)?.doubleOrNull?.let { return it }
        (remaining["remaining_fraction"] as? JsonPrimitive)?.doubleOrNull?.let { return it }
        if (remaining.stringField("case") == "remainingFraction") {
            return (remaining["value"] as? JsonPrimitive)?.doubleOrNull
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
        return (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    private const val FIVE_HOUR_SECONDS = 5L * 60L * 60L
    private const val SEVEN_DAY_SECONDS = 7L * 24L * 60L * 60L
}
