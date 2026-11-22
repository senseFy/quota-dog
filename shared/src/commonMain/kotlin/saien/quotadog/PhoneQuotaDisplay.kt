package saien.quotadog

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import saien.quotadog.hostsync.*

/** Local display only. Neither a cached window nor a workspace name proves account ownership. */
@Serializable
data class PhoneWindow(val label: String, val used: Double?, val reset: Instant?, val duration: Long?, val unlimited: Boolean = false)

@Serializable
data class PhoneQuotaDisplay(val windows: List<PhoneWindow>, val amounts: List<String>, val workspaceOnlyName: String? = null)

fun QuotaWindow.phoneWindow(): PhoneWindow {
    val name = when (kind) {
        QuotaWindowKind.CodexPrimary, QuotaWindowKind.CodexSecondary -> when (durationSeconds) {
            18_000L -> "5-hour"
            604_800L -> "Weekly"
            else -> label
        }
        else -> label.removeSuffix(" window")
    }
    return PhoneWindow(name, used, resetsAt?.let(Instant::fromEpochMilliseconds), durationSeconds, unlimited)
}

fun ProviderUsageSnapshot.phoneQuotaDisplay(): PhoneQuotaDisplay? {
    cachedPhoneDisplay?.let { return it }
    val quota = localQuota?.takeIf { !stale && authState == AuthState.LoggedIn && it.providerId == providerId } ?: return null
    val account = toQuotaReading()
    val workspace = toQuotaReading(workspace = true)
    return PhoneQuotaDisplay(quota.phoneWindows ?: account?.windows.orEmpty().map { it.phoneWindow() }, buildList {
        // The local Codex snapshot already stores individual credits, including exact expiries.
        if (providerId != ProviderId.CODEX) addAll(account?.detailLines().orEmpty())
        addAll(workspace?.detailLines().orEmpty())
        for (amount in quota.unscopedCreditsUsd) add("Credits (scope unverified): ${formatDevinDollars(amount)}")
    }, ampWorkspaceName?.takeIf { workspace != null && account == null && quota.unscopedCreditsUsd.isEmpty() })
}

// One atomic snapshot write. Older readers ignore the extra field; the regular snapshot
// serializer never includes it in a backup. Do not serialize LocalQuota or restore login proof.
internal fun Json.encodePhoneSnapshot(snapshot: ProviderUsageSnapshot): String {
    val display = snapshot.phoneQuotaDisplay() ?: return encodeToString(snapshot)
    return JsonObject(encodeToJsonElement(snapshot).jsonObject + ("phoneDisplay" to encodeToJsonElement(display))).toString()
}

internal fun Json.decodePhoneSnapshot(encoded: String): ProviderUsageSnapshot {
    val fields = parseToJsonElement(encoded).jsonObject
    val snapshot = decodeFromJsonElement<ProviderUsageSnapshot>(fields)
    val display = fields["phoneDisplay"] ?: return snapshot
    return snapshot.copy(cachedPhoneDisplay = runCatching { decodeFromJsonElement<PhoneQuotaDisplay>(display) }.getOrNull())
}
