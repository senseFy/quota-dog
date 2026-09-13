package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
    val applicableCount: Int?,
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

    /** "2 available", or "3 available · 2 usable now" when the applicable count differs. */
    fun availabilityLabel(): String {
        val base = "$availableCount available"
        val applicable = applicableCount
        return if (applicable != null && applicable != availableCount) {
            "$base · $applicable usable now"
        } else {
            base
        }
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
        applicableCount = resetCreditsApplicable,
        credits = availableCredits,
        nearestExpiresAt = nearestExpiresAt,
        expiringSoon = remainingMillis != null && remainingMillis <= EXPIRING_SOON_MILLIS,
    )
}

/** "Jul 12, 17:30 · 12d18h" — absolute local expiry plus relative countdown. */
fun CodexResetCredit.expiryLabel(now: Instant = Clock.System.now()): String {
    val expiresAt = expiresAt ?: return "No expiry"
    if (expiresAt.toEpochMilliseconds() <= now.toEpochMilliseconds()) return "Expired"
    return "${expiresAt.localDateTimeLabel()} · ${expiresAt.codexRemainingLabel(now)}"
}

fun CodexResetCredit.isExpiringSoon(now: Instant = Clock.System.now()): Boolean {
    val expiresAt = expiresAt ?: return false
    return expiresAt.toEpochMilliseconds() - now.toEpochMilliseconds() <= EXPIRING_SOON_MILLIS
}

internal fun Instant.localDateTimeLabel(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    val month = SHORT_MONTHS[local.month.ordinal]
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$month ${local.dayOfMonth}, $hour:$minute"
}

internal data class CodexUsageParseResult(
    val windows: List<UsageWindow>,
    val planType: String?,
    val resetCreditsAvailable: Int?,
    val resetCreditsApplicable: Int?,
)

internal data class CodexResetCreditsDetails(
    val availableCount: Int,
    val applicableCount: Int?,
    val credits: List<CodexResetCredit>,
)

internal data class CodexResetCreditsMerge(
    val availableCount: Int?,
    val applicableCount: Int?,
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
        val resetCredits = root["rate_limit_reset_credits"]?.jsonObject
        return CodexUsageParseResult(
            windows = windows,
            planType = root["plan_type"]?.jsonPrimitive?.contentOrNull,
            resetCreditsAvailable = resetCredits?.get("available_count").intCountOrNull(),
            resetCreditsApplicable = resetCredits?.get("applicable_available_count").intCountOrNull(),
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
            applicableCount = root["applicable_available_count"].intCountOrNull(),
            credits = credits,
        )
    }

    fun mergeResetCredits(
        usageAvailableCount: Int?,
        usageApplicableCount: Int?,
        details: CodexResetCreditsDetails?,
    ): CodexResetCreditsMerge {
        if (details == null) {
            return CodexResetCreditsMerge(usageAvailableCount, usageApplicableCount, emptyList())
        }
        return CodexResetCreditsMerge(
            availableCount = details.availableCount,
            applicableCount = details.applicableCount ?: usageApplicableCount,
            credits = details.credits.filter { it.isAvailable },
        )
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
            resetType = obj["reset_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            source = obj["source"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            grantedAt = parseInstant(obj["granted_at"]),
            expiresAt = parseInstant(obj["expires_at"]),
            redeemedAt = parseInstant(obj["redeemed_at"]),
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

private val SHORT_MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

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
