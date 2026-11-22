package saien.quotadog

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import saien.quotadog.hostsync.LocalQuota
import saien.quotadog.hostsync.QuotaWindowKind
import saien.quotadog.hostsync.quotaWindow

private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** IDs, if any, must be from the authenticated subscription OAuth response for this bearer.
 * /api/oauth/usage does not identify the subscriber. Never substitute an email or account-row ID. */
internal fun claudeLocalQuota(root: JsonObject, accountUuid: String?, organizationUuid: String?): LocalQuota {
    fun uuid(raw: String?) = raw?.takeIf { it.matches(uuidPattern) && it.any { c -> c != '0' && c != '-' } }?.lowercase()
    val account = uuid(accountUuid)
    val org = uuid(organizationUuid)
    val windows = listOf(
        "five_hour" to QuotaWindowKind.ClaudeFiveHour, "seven_day" to QuotaWindowKind.ClaudeWeekly,
        "seven_day_sonnet" to QuotaWindowKind.ClaudeSonnetWeekly, "seven_day_opus" to QuotaWindowKind.ClaudeOpusWeekly,
    ).mapNotNull { (key, kind) ->
        val obj = root[key] as? JsonObject ?: return@mapNotNull null
        val used = (obj["utilization"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
        val reset = (obj["resets_at"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
        quotaWindow(kind, used?.let(::normalizePercent), reset, if (key == "five_hour") 18_000 else 604_800)
    }
    return LocalQuota(ProviderId.CLAUDE_CODE, windows, matchParts = if (account != null && org != null) listOf(account, org) else null)
}
