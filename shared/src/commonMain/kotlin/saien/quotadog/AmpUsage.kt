package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

internal data class AmpUsageSnapshot(
    val planName: String?,
    val email: String?,
    val windows: List<UsageWindow>,
    val individualCreditsDollars: Double? = null,
    val workspaceCreditsDollars: Double? = null,
    val workspaceName: String? = null,
)

/**
 * Fetches Amp account quota through the internal RPC used by `amp usage`:
 * `POST {serverUrl}/api/internal?userDisplayBalanceInfo` with `Authorization: Bearer <apiKey>`.
 */
internal object AmpUsageFetcher {
    private const val BALANCE_METHOD = "userDisplayBalanceInfo"
    private const val USER_INFO_METHOD = "getUserInfo"
    private const val DAY_SECONDS = 24L * 60L * 60L
    private const val THIRTY_DAY_SECONDS = 30L * 24L * 60L * 60L

    private val json = Json { ignoreUnknownKeys = true }

    private val signedInRegex = Regex("""^Signed in as\s+([^\s(]+)""", RegexOption.IGNORE_CASE)
    private val freeDollarsRegex = Regex(
        """\$(\d+(?:,\d{3})*(?:\.\d+)?)\s*/\s*\$(\d+(?:,\d{3})*(?:\.\d+)?)\s+remaining""",
        RegexOption.IGNORE_CASE,
    )
    private val replenishRegex = Regex(
        """replenishes\s*\+\$(\d+(?:,\d{3})*(?:\.\d+)?)\s*/\s*h(?:our|r)?""",
        RegexOption.IGNORE_CASE,
    )
    private val freePercentRegex = Regex("""(\d+(?:\.\d+)?)%\s+remaining""", RegexOption.IGNORE_CASE)
    private val tierLineRegex = Regex("""^Amp\s+(.+?)\s+Tier\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val periodDatesRegex = Regex(
        """period\s+(\d{4}-\d{2}-\d{2})\s+to\s+(\d{4}-\d{2}-\d{2})""",
        RegexOption.IGNORE_CASE,
    )
    private val renewalDaysRegex = Regex("""resets\s+upon\s+renewal\s+in\s+(\d+)\s+days?""", RegexOption.IGNORE_CASE)
    private val agentDollarsRegex = Regex(
        """agent usage\s+\$(\d+(?:,\d{3})*(?:\.\d+)?)\s+of\s+\$(\d+(?:,\d{3})*(?:\.\d+)?)\s+remaining""",
        RegexOption.IGNORE_CASE,
    )
    private val agentPercentRegex = Regex(
        """agent usage[^,]*?(?:\((\d+(?:\.\d+)?)%\)|(\d+(?:\.\d+)?)%\s+remaining)""",
        RegexOption.IGNORE_CASE,
    )
    private val orbHoursRegex = Regex(
        """orb usage\s+(\d+(?:,\d{3})*(?:\.\d+)?)h\s+of\s+(\d+(?:,\d{3})*(?:\.\d+)?)h""",
        RegexOption.IGNORE_CASE,
    )
    private val orbPercentRegex = Regex(
        """orb usage[^,]*?(?:\((\d+(?:\.\d+)?)%\)|(\d+(?:\.\d+)?)%\s+remaining)""",
        RegexOption.IGNORE_CASE,
    )
    private val individualCreditsRegex = Regex(
        """^Individual credits:\s*\$(\d+(?:,\d{3})*(?:\.\d+)?)\s+remaining""",
        RegexOption.IGNORE_CASE,
    )
    private val workspaceCreditsRegex = Regex(
        """^(.+?)\s+credits:\s*\$(\d+(?:,\d{3})*(?:\.\d+)?)\s+remaining""",
        RegexOption.IGNORE_CASE,
    )

    suspend fun fetch(
        httpClient: HttpClient,
        apiKey: String,
        apiServerUrl: String? = null,
    ): AmpUsageSnapshot {
        val base = AmpAuthParser.cleanServerUrl(apiServerUrl) ?: AmpAuthParser.DEFAULT_API_SERVER_URL
        val response = postInternal(httpClient, base, BALANCE_METHOD, apiKey)
        val parsed = parseBalanceResponse(response)
        if (!parsed.email.isNullOrBlank()) return parsed
        val fallbackEmail = runCatching { fetchUserEmail(httpClient, apiKey, base) }.getOrNull()
        return if (!fallbackEmail.isNullOrBlank()) parsed.copy(email = fallbackEmail) else parsed
    }

    suspend fun fetchUserEmail(
        httpClient: HttpClient,
        apiKey: String,
        apiServerUrl: String? = null,
    ): String? {
        val base = AmpAuthParser.cleanServerUrl(apiServerUrl) ?: AmpAuthParser.DEFAULT_API_SERVER_URL
        val response = postInternal(httpClient, base, USER_INFO_METHOD, apiKey)
        if (!response.status.isSuccess()) return null
        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: return null
        val result = root["result"] as? JsonObject ?: root
        return result.stringField("email") ?: result.stringField("username")
    }

    private suspend fun postInternal(
        httpClient: HttpClient,
        base: String,
        method: String,
        apiKey: String,
    ): HttpResponse {
        return httpClient.post("$base/api/internal?$method") {
            header("Authorization", "Bearer $apiKey")
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody("""{"method":"$method","params":{}}""")
        }
    }

    internal suspend fun parseBalanceResponse(
        response: HttpResponse,
        now: Instant = Clock.System.now(),
    ): AmpUsageSnapshot {
        val statusCode = response.status.value
        val body = response.bodyAsText()
        if (statusCode == HttpStatusCode.TooManyRequests.value) {
            throw ProviderException(
                AuthState.RateLimited,
                "Amp usage lookup was rate limited. Please try again shortly.",
                statusCode,
            )
        }
        if (statusCode == 401 || statusCode == 403) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Amp rejected credentials. Sign in with Amp again or re-import from `amp login`.",
                statusCode,
            )
        }
        if (!response.status.isSuccess()) {
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(
                AuthState.Error,
                "Amp usage request failed: HTTP $statusCode: $preview",
                statusCode,
            )
        }
        return parseBalanceJson(body, now)
    }

    internal fun parseBalanceJson(
        text: String,
        now: Instant = Clock.System.now(),
    ): AmpUsageSnapshot {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Amp usage response was not valid JSON.")
        }
        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull == false) {
            val errorMsg = (root["error"] as? JsonObject)?.stringField("message")
                ?: root.stringField("message")
                ?: "Amp returned an error while reading usage."
            throw ProviderException(AuthState.Error, errorMsg)
        }
        val result = root["result"] as? JsonObject ?: root
        val displayText = result.stringField("displayText")
            ?: result.stringField("display_text")
            ?: throw ProviderException(AuthState.Error, "Amp usage response had no displayText.")
        return parseDisplayText(displayText, now)
    }

    internal fun parseDisplayText(
        displayText: String,
        now: Instant = Clock.System.now(),
    ): AmpUsageSnapshot {
        var email: String? = null
        var planName: String? = null
        var freeWindow: UsageWindow? = null
        val tierWindows = mutableListOf<UsageWindow>()
        var individualCredits: Double? = null
        var workspaceCredits: Double? = null
        var workspaceName: String? = null

        for (rawLine in displayText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (email == null) {
                signedInRegex.find(line)?.let { match ->
                    email = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    continue
                }
            }

            if (line.startsWith("Amp Free:", ignoreCase = true)) {
                parseFreeLine(line, now)?.let { freeWindow = it }
                continue
            }

            tierLineRegex.find(line)?.let { match ->
                planName = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                tierWindows += parseTierLine(match.groupValues[2], now)
                continue
            }

            individualCreditsRegex.find(line)?.let { match ->
                individualCredits = parseAmount(match.groupValues[1])
                continue
            }

            workspaceCreditsRegex.find(line)?.let { match ->
                val label = match.groupValues[1].trim()
                val amount = parseAmount(match.groupValues[2])
                if (amount != null) {
                    workspaceCredits = amount
                    if (!label.equals("Workspace", ignoreCase = true)) {
                        workspaceName = label
                    }
                }
                continue
            }
        }

        val windows = buildList {
            addAll(tierWindows)
            freeWindow?.let { add(it) }
        }.toMutableList()

        if (windows.isEmpty()) {
            val anyCredits = individualCredits ?: workspaceCredits
            if (anyCredits != null) {
                windows += UsageWindow(
                    id = "amp-credits",
                    label = "Credits",
                    usedRatio = if (anyCredits > 0.0) 0.0 else 1.0,
                    resetsAt = null,
                )
            } else {
                throw ProviderException(
                    AuthState.Error,
                    "Amp did not report quota or credit data for this account.",
                )
            }
        }

        val resolvedPlan = planName ?: if (freeWindow != null) "Free" else null
        return AmpUsageSnapshot(
            planName = resolvedPlan,
            email = email,
            windows = windows,
            individualCreditsDollars = individualCredits,
            workspaceCreditsDollars = workspaceCredits,
            workspaceName = workspaceName,
        )
    }

    private fun parseFreeLine(line: String, now: Instant): UsageWindow? {
        freeDollarsRegex.find(line)?.let { match ->
            val remaining = parseAmount(match.groupValues[1]) ?: return null
            val total = parseAmount(match.groupValues[2]) ?: return null
            if (total <= 0.0) return null
            val usedRatio = ((total - remaining) / total).coerceIn(0.0, 1.0)
            val replenishPerHour = replenishRegex.find(line)?.groupValues?.get(1)?.let(::parseAmount)
            val resetsAt = if (replenishPerHour != null && replenishPerHour > 0.0 && remaining < total) {
                val hoursUntilFull = (total - remaining) / replenishPerHour
                Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + (hoursUntilFull * 3_600_000.0).toLong())
            } else {
                null
            }
            val durationSeconds = if (replenishPerHour != null && replenishPerHour > 0.0) {
                ((total / replenishPerHour) * 3_600.0).toLong().takeIf { it > 0L }
            } else {
                DAY_SECONDS
            }
            return UsageWindow(
                id = "amp-free",
                label = "Amp Free",
                usedRatio = usedRatio,
                resetsAt = resetsAt,
                durationSeconds = durationSeconds,
            )
        }
        freePercentRegex.find(line)?.let { match ->
            val pct = match.groupValues[1].toDoubleOrNull() ?: return null
            return UsageWindow(
                id = "amp-free",
                label = "Amp Free",
                usedRatio = 1.0 - normalizePercent(pct),
                resetsAt = null,
                durationSeconds = DAY_SECONDS,
            )
        }
        return null
    }

    private fun parseTierLine(detail: String, now: Instant): List<UsageWindow> {
        var resetsAt: Instant? = null
        var durationSeconds: Long = THIRTY_DAY_SECONDS

        periodDatesRegex.find(detail)?.let { match ->
            val start = runCatching { Instant.parse("${match.groupValues[1]}T00:00:00Z") }.getOrNull()
            val end = runCatching { Instant.parse("${match.groupValues[2]}T00:00:00Z") }.getOrNull()
            if (end != null) {
                resetsAt = end
                if (start != null && end > start) {
                    durationSeconds = end.epochSeconds - start.epochSeconds
                }
            }
        }
        if (resetsAt == null) {
            renewalDaysRegex.find(detail)?.groupValues?.get(1)?.toLongOrNull()?.let { days ->
                if (days > 0) {
                    resetsAt = Instant.fromEpochSeconds(now.epochSeconds + days * DAY_SECONDS)
                }
            }
        }

        val windows = mutableListOf<UsageWindow>()
        parseAgentUsedRatio(detail)?.let { usedRatio ->
            windows += UsageWindow(
                id = "amp-agent",
                label = "Agent usage",
                usedRatio = usedRatio,
                resetsAt = resetsAt,
                durationSeconds = durationSeconds,
            )
        }
        parseOrbUsedRatio(detail)?.let { usedRatio ->
            windows += UsageWindow(
                id = "amp-orb",
                label = "Orb usage",
                usedRatio = usedRatio,
                resetsAt = resetsAt,
                durationSeconds = durationSeconds,
            )
        }
        return windows
    }

    private fun parseAgentUsedRatio(detail: String): Double? {
        agentDollarsRegex.find(detail)?.let { match ->
            val remaining = parseAmount(match.groupValues[1])
            val total = parseAmount(match.groupValues[2])
            if (remaining != null && total != null && total > 0.0) {
                return ((total - remaining) / total).coerceIn(0.0, 1.0)
            }
        }
        agentPercentRegex.find(detail)?.let { match ->
            val pct = match.groupValues[1].ifEmpty { match.groupValues[2] }.toDoubleOrNull()
            if (pct != null) return 1.0 - normalizePercent(pct)
        }
        return null
    }

    private fun parseOrbUsedRatio(detail: String): Double? {
        orbHoursRegex.find(detail)?.let { match ->
            val remaining = parseAmount(match.groupValues[1])
            val total = parseAmount(match.groupValues[2])
            if (remaining != null && total != null && total > 0.0) {
                return ((total - remaining) / total).coerceIn(0.0, 1.0)
            }
        }
        orbPercentRegex.find(detail)?.let { match ->
            val pct = match.groupValues[1].ifEmpty { match.groupValues[2] }.toDoubleOrNull()
            if (pct != null) return 1.0 - normalizePercent(pct)
        }
        return null
    }

    private fun parseAmount(raw: String): Double? {
        return raw.replace(",", "").trim().toDoubleOrNull()
    }

    private fun JsonObject.stringField(name: String): String? {
        return (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
