package saien.quotadog.app

import kotlinx.datetime.Instant
import saien.quotadog.*
import saien.quotadog.hostsync.*

internal fun DashboardKey.aliasScope() = when (this) {
    is DashboardKey.Local -> "local:${key.providerId.name}:${key.accountId}"
    is DashboardKey.Remote -> "grant:$peer:$ref"
}

internal data class PhoneReading(
    val windows: List<PhoneWindow>, val amounts: List<String>, val collected: Instant, val plan: String?,
) {
    // Rank by the actual used ratio, not the rounded percentage or the user's display unit.
    private val tightest = windows.minWithOrNull(compareByDescending<PhoneWindow> { it.used }.thenBy { it.reset ?: Instant.DISTANT_FUTURE })
    private val otherCritical = windows.filter { it !== tightest && (it.used ?: 0.0) >= .9 }
    val summary = listOfNotNull(tightest, otherCritical.maxWithOrNull(compareBy<PhoneWindow> { it.reset }.thenBy { it.used }))
    val moreCritical = (otherCritical.size - 1).coerceAtLeast(0)
    val commonReset = windows.firstOrNull()?.reset?.takeIf { reset -> windows.size > 1 && windows.all { it.reset == reset } }

    fun estimatedAtReset(window: PhoneWindow, now: Instant): Double? {
        val reset = window.reset ?: return null
        if (reset <= now) return null
        return UsageWindow("", window.label, window.used ?: return null, reset, durationSeconds = window.duration ?: return null)
            .projectedUsedRatio(collected)
    }
}

private fun codexCredits(available: Int?, eligible: Int?) = listOfNotNull(
    available?.let { "Resets available: $it" }, eligible?.let { "Resets eligible: $it" },
)

private fun QuotaReading.phoneReading(): PhoneReading {
    val credits = details as? QuotaDetails.Codex
    val amounts = if (credits == null) detailLines() else codexCredits(credits.available, credits.eligible) + credits.expiresAt.sorted().map {
        "Reset credit expires: ${Instant.fromEpochMilliseconds(it).exactLocalTimeLabel().substringBefore('\n')}"
    }
    return PhoneReading(windows.map { it.phoneWindow() }, amounts, Instant.fromEpochMilliseconds(collectedAt), plan?.label)
}

private fun ProviderUsageSnapshot.phoneReading(): PhoneReading {
    val display = phoneQuotaDisplay()
    val metrics = display?.windows ?: windows.filterNot { it.id == "amp-credits" }.map { w ->
        val unlimited = providerId == ProviderId.CURSOR && w.label == "Unlimited"
        // These legacy collectors encoded missing data as zero. An old zero without typed
        // evidence isn't enough to tell the user that the entire allowance is still available.
        val ambiguous = w.usedRatio == 0.0 && (providerId == ProviderId.GROK ||
            (providerId == ProviderId.CURSOR && (w.id == "plan-usage" || w.id == "on-demand")))
        PhoneWindow(w.label.removeSuffix(" window"), w.usedRatio.takeIf { it in 0.0..1.0 && !ambiguous && !unlimited },
            w.resetsAt.takeUnless { providerId == ProviderId.GROK }, w.durationSeconds, unlimited)
    }

    val amounts = if (providerId == ProviderId.CODEX) {
        codexCredits(resetCreditsAvailable, resetCreditsApplicable) + resetCredits.filter { it.isAvailable }.sortedBy {
            it.expiresAt ?: Instant.DISTANT_FUTURE
        }.map { "${it.title ?: "Banked reset"}: ${it.expiresAt?.exactLocalTimeLabel()?.substringBefore('\n') ?: "No expiry"}" }
    } else display?.amounts ?: when (providerId) {
        ProviderId.DEVIN -> listOfNotNull(devinExtraBalanceUsd?.let { "Extra balance: ${formatDevinDollars(it)}" })
        // A legacy workspace could be named "Individual". The stored text isn't scope evidence.
        ProviderId.AMP -> message.orEmpty().split(" · ").mapNotNull { line ->
            line.substringAfterLast(": ", "").takeIf { it.startsWith('$') }?.let { "Credits (scope unverified): $it" }
        }
        ProviderId.CURSOR, ProviderId.DROID -> message.orEmpty().split(" · ").filter { '$' in it && !it.startsWith("Source:") }
        else -> emptyList()
    }
    return PhoneReading(metrics, amounts, collectedAt, displayPlanLabel())
}

internal val DashboardAccount.readingSource get() = when (this) {
    is DashboardAccount.Local -> displayedRemote
    is DashboardAccount.Remote -> source
}

/** Select the whole winning observation *before* projecting windows, plan and balances. */
internal fun DashboardAccount.phoneReading(): PhoneReading? = when (this) {
    is DashboardAccount.Remote -> source.reading?.phoneReading()
    is DashboardAccount.Local -> if (displayedRemote == null) localObservation?.phoneReading()
        else displayedRemote.comparableReading?.phoneReading()
}
