package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class CodexResetSummary(
    val availableCount: Int,
    val credits: List<CodexResetCredit>,
    val nearestExpiresAt: Instant?,
    val expiringSoon: Boolean,
) {
    val countLabel: String
        get() = if (availableCount == 1) "1 reset available" else "$availableCount resets available"

    fun compactLabel(now: Instant = Clock.System.now()): String {
        val count = if (availableCount == 1) "1 reset" else "$availableCount resets"
        val expiresAt = nearestExpiresAt ?: return count
        return "$count · ${expiresAt.codexRemainingLabel(now)}"
    }
}

fun ProviderUsageSnapshot.codexResetSummary(
    now: Instant = Clock.System.now(),
): CodexResetSummary? {
    val count = resetCreditsAvailable ?: 0
    if (count <= 0) return null
    val availableCredits = resetCredits
        .filter { it.isAvailable }
        .sortedBy { it.expiresAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }
    val nearestExpiresAt = availableCredits.firstNotNullOfOrNull { it.expiresAt }
    val remainingMillis = nearestExpiresAt?.let { it.toEpochMilliseconds() - now.toEpochMilliseconds() }
    return CodexResetSummary(
        availableCount = count,
        credits = availableCredits,
        nearestExpiresAt = nearestExpiresAt,
        expiringSoon = remainingMillis != null && remainingMillis <= EXPIRING_SOON_MILLIS,
    )
}

fun CodexResetCredit.remainingLabel(now: Instant = Clock.System.now()): String {
    val expiresAt = expiresAt ?: return "—"
    return expiresAt.codexRemainingLabel(now)
}

internal data class CodexUsageParseResult(
    val windows: List<UsageWindow>,
    val planType: String?,
    val resetCreditsAvailable: Int?,
)

internal data class CodexResetCreditsDetails(
    val availableCount: Int,
    val credits: List<CodexResetCredit>,
)

internal object CodexUsageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseUsage(text: String): CodexUsageParseResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Codex usage response was not valid JSON.")
        }
        val rateLimit = root["rate_limit"]?.jsonObject
            ?: throw ProviderException(AuthState.Error, "Codex usage response is missing 'rate_limit'")
        val windows = listOfNotNull(
            parseWindow("primary", rateLimit["primary_window"]),
            parseWindow("secondary", rateLimit["secondary_window"]),
        ).sortedBy { it.resetsAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }
        return CodexUsageParseResult(
            windows = windows,
            planType = root["plan_type"]?.jsonPrimitive?.contentOrNull,
            resetCreditsAvailable = root["rate_limit_reset_credits"]?.jsonObject
                ?.get("available_count")
                .intCountOrNull(),
        )
    }

    fun parseResetCredits(text: String): CodexResetCreditsDetails {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Codex reset credits response was not valid JSON.")
        }
        val credits = root["credits"]
            ?.let { element -> runCatching { element.jsonArray }.getOrNull() }
            ?.mapNotNull(::parseCredit)
            .orEmpty()
        val availableFromCredits = credits.count { it.isAvailable }
        val availableCount = root["available_count"].intCountOrNull() ?: availableFromCredits
        return CodexResetCreditsDetails(
            availableCount = availableCount.coerceAtLeast(0),
            credits = credits,
        )
    }

    fun mergeResetCredits(
        usageAvailableCount: Int?,
        details: CodexResetCreditsDetails?,
    ): Pair<Int?, List<CodexResetCredit>> {
        if (details == null) {
            return usageAvailableCount to emptyList()
        }
        return details.availableCount to details.credits.filter { it.isAvailable }
    }

    private fun parseWindow(id: String, element: JsonElement?): UsageWindow? {
        if (element == null || element is JsonNull) return null
        val obj = element.jsonObject
        val used = obj["used_percent"]?.jsonPrimitive?.doubleOrNull ?: return null
        val seconds = obj["limit_window_seconds"]?.jsonPrimitive?.intOrNull
        val label = when (seconds) {
            18_000 -> "5-hour window"
            604_800 -> "7-day window"
            else -> if ((seconds ?: 0) >= 604_800) "Weekly window" else "Window ${seconds ?: "unknown"}s"
        }
        val resetAt = obj["reset_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?.let { Instant.fromEpochSeconds(it) }
        return UsageWindow(
            id = id,
            label = label,
            // Codex reports used_percent on a 0–100 scale (1 means 1%, not full).
            usedRatio = normalizePercent(used),
            resetsAt = resetAt,
            durationSeconds = seconds?.toLong(),
        )
    }

    private fun parseCredit(element: JsonElement): CodexResetCredit? {
        val obj = element as? JsonObject ?: runCatching { element.jsonObject }.getOrNull() ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val status = obj["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "available"
        return CodexResetCredit(
            id = id,
            status = status,
            title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            description = obj["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            grantedAt = parseInstant(obj["granted_at"]),
            expiresAt = parseInstant(obj["expires_at"]),
        )
    }

    private fun parseInstant(element: JsonElement?): Instant? {
        val primitive = element?.jsonPrimitive ?: return null
        primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()?.let { return it }
            raw.toLongOrNull()?.let { return Instant.fromEpochSeconds(it) }
        }
        primitive.longOrNull?.let { return Instant.fromEpochSeconds(it) }
        return null
    }
}

private const val EXPIRING_SOON_MILLIS = 3L * 24L * 60L * 60L * 1_000L

private fun JsonElement?.intCountOrNull(): Int? {
    if (this == null || this is JsonNull) return null
    val primitive = runCatching { jsonPrimitive }.getOrNull() ?: return null
    val value = primitive.intOrNull
        ?: primitive.longOrNull?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
        ?: primitive.contentOrNull?.toIntOrNull()
        ?: return null
    return value.coerceAtLeast(0)
}

internal fun Instant.codexRemainingLabel(now: Instant = Clock.System.now()): String {
    val remainingMillis = (toEpochMilliseconds() - now.toEpochMilliseconds()).coerceAtLeast(0)
    if (remainingMillis == 0L && toEpochMilliseconds() <= now.toEpochMilliseconds()) return "expired"
    return formatCompactDuration(remainingMillis)
}

internal fun formatCompactDuration(remainingMillis: Long): String {
    val totalMinutes = (remainingMillis / 60_000).coerceAtLeast(0)
    if (totalMinutes < 60) return "${totalMinutes}m"
    val totalHours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (totalHours < 24) {
        return if (minutes == 0L) "${totalHours}h" else "${totalHours}h${minutes}m"
    }
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (hours == 0L) "${days}d" else "${days}d${hours}h"
}
