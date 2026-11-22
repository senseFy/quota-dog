package saien.quotadog.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import saien.quotadog.AccountUiState
import saien.quotadog.AuthState
import saien.quotadog.DashboardLayout
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.UsageDisplayMode
import saien.quotadog.app.components.QdButton
import saien.quotadog.app.components.QdButtonSize
import saien.quotadog.app.components.QdButtonVariant
import saien.quotadog.app.components.QdPlanLabel
import saien.quotadog.app.components.QdProgressBar
import saien.quotadog.app.components.QdProviderAvatar
import saien.quotadog.app.components.QdTimestamp
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.exactLocalTimeLabel
import saien.quotadog.formatDevinDollars
import saien.quotadog.hostsync.Access
import saien.quotadog.hostsync.Hint
import saien.quotadog.hostsync.QuotaDetails
import saien.quotadog.hostsync.QuotaReading
import saien.quotadog.hostsync.QuotaWindow
import saien.quotadog.hostsync.SourceStatus
import saien.quotadog.hostsync.localSourceLabel
import saien.quotadog.hostsync.toQuotaReading
import kotlin.math.roundToInt

/** Only a received reading and its peer's action are in scope here: no store, login or delete. */
@Composable
internal fun RemoteSourceContent(
    source: RemoteSource,
    mode: UsageDisplayMode,
    layout: DashboardLayout,
    showRefresh: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(QdTheme.spacing.sm)) {
        SourceHeader(source.card.provider, source.fullLabel, source.card.access == Access.View, trailing)
        if (source.uncertainOnly) Hint("Unverified source time · No trusted reading yet")
        SharedObservation(source.reading, mode, layout)
        RemoteStatus(source)
        if (source.clockWarning) Hint(CLOCK_WARNING)
        if (showRefresh && source.refreshable) QdButton(if (source.busy) "Refreshing…" else source.peer.refreshLabel, source::refresh,
            enabled = !source.busy, variant = QdButtonVariant.Secondary, size = QdButtonSize.Small)
    }
}

private const val CLOCK_WARNING = "Device clocks differ · Check the time on both devices."
private val RemoteSource.fullLabel get() = "${peer.label} · $label"

@Composable
private fun SourceHeader(provider: ProviderId, label: String, viewOnly: Boolean, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(QdTheme.spacing.sm)) {
        QdProviderAvatar(provider, size = 20.dp)
        Column(Modifier.weight(1f)) {
            Text(provider.displayName, style = QdTheme.typography.titleMedium, color = QdTheme.colors.textPrimary)
            Hint(label)
        }
        if (viewOnly) QdPlanLabel("View only")
        trailing()
    }
}

@Composable
private fun SharedObservation(reading: QuotaReading?, mode: UsageDisplayMode, layout: DashboardLayout) {
    if (reading == null) { Hint("Waiting for first reading"); return }
    reading.plan?.let { QdPlanLabel(it.label) }
    val windows = reading.windows
    var expanded by remember(layout) { mutableStateOf(false) }
    val fold = windows.size > 1 && (layout == DashboardLayout.OneLine ||
        (layout == DashboardLayout.FoldFull && windows.all { (it.used ?: 1.0) <= 1 - QUIET_REMAINING_RATIO }))
    val shown = if (fold && !expanded) listOf(windows.maxBy { it.used ?: -1.0 }) else windows
    for (window in shown) SharedWindowRow(window, mode)
    ReadingAmounts(reading)
    if (fold) QdButton(if (expanded) "Show less" else "Show all ${windows.size} windows", { expanded = !expanded },
        variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
    ReadingTime("Collected · ", Instant.fromEpochMilliseconds(reading.collectedAt))
}

@Composable
private fun RemoteStatus(source: RemoteSource) {
    val notice = when (source.card.status) {
        SourceStatus.Ready -> null
        SourceStatus.LoginRequired -> "Sign-in required on the source device"
        SourceStatus.RateLimited -> "Provider rate limit reached on the source device"
        SourceStatus.Failed -> "Collection failed on the source device"
    }
    if (notice != null) Hint(if (source.reading != null) "$notice · Last reading shown" else notice)
    if (!source.peer.online) Hint(if (source.reading == null) "Source offline" else "Source offline · Last reading kept on this device")
}

@Composable
private fun SharedWindowRow(window: QuotaWindow, mode: UsageDisplayMode) {
    val used = window.used
    val displayed = used?.let { if (mode == UsageDisplayMode.Used) it else 1.0 - it }
    val amount = when {
        window.unlimited -> "Unlimited"
        displayed == null -> "Not reported"
        else -> "${(100 * displayed).roundToInt()}% ${if (mode == UsageDisplayMode.Used) "used" else "left"}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(QdTheme.spacing.xxs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(QdTheme.spacing.sm)) {
            Text(window.label, modifier = Modifier.weight(1f), style = QdTheme.typography.labelLarge, color = QdTheme.colors.textPrimary)
            Text(amount, style = QdTheme.typography.numeric, color = QdTheme.colors.textPrimary)
        }
        if (used != null && displayed != null) QdProgressBar(displayed.toFloat(), fill = when {
            used >= 0.9 -> QdTheme.colors.danger
            used >= 0.7 -> QdTheme.colors.warning
            else -> QdTheme.colors.meter
        })
        window.resetsAt?.let { ReadingTime("Resets · ", Instant.fromEpochMilliseconds(it)) }
    }
}

@Composable
private fun ReadingAmounts(reading: QuotaReading) {
    val details = reading.details
    if (details is QuotaDetails.Devin) Hint("Extra balance · ${formatDevinDollars(details.extraUsd, "USD ")}")
    else reading.detailLines().forEach { Hint(it) }
}

@Composable
private fun ReadingTime(prefix: String, time: Instant) {
    QdTimestamp(time) { Hint(prefix + time.exactLocalTimeLabel().substringBefore('\n')) }
}

/** The face is one observation, never a splice of the latest windows and balances. Source details
 * expose other observations without introducing secondary refresh buttons that bypass routing. */
@Composable
internal fun MergedAccountContent(
    row: DashboardAccount.Local, mode: UsageDisplayMode, layout: DashboardLayout, projected: Boolean,
    onLocalRefresh: (AccountUiState) -> Unit, trailing: @Composable () -> Unit = {}, localManagement: @Composable () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = row.displayedRemote
    val localLabel = "This device · ${row.account.accountKey.localSourceLabel()}"
    val target = row.refreshTarget()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(QdTheme.spacing.sm)) {
        SourceHeader(row.provider, selected?.fullLabel ?: localLabel, selected?.card?.access == Access.View, trailing)
        if (selected != null) {
            SharedObservation(selected.trustedReading, mode, layout)
            RemoteStatus(selected)
        } else LocalObservation(row.localObservation, mode, projected)
        if (row.localClockWarning || row.copies.any { it.clockWarning }) {
            Hint(if (row.collectedAt == null) "$CLOCK_WARNING No trusted collection time yet." else "$CLOCK_WARNING Showing the last trusted reading.")
        }
        if (target is DashboardRefresh.There && target.source.key != selected?.key) Hint("Refresh source · ${target.source.fullLabel}")
        if (target != null) QdButton(if (row.busy) "Refreshing…" else target.label, { target.run(onLocalRefresh) },
            enabled = !row.busy, variant = QdButtonVariant.Secondary, size = QdButtonSize.Small)
        else Hint(if (row.account.authState == AuthState.RateLimited) "Rate limited on this device · No request sent to another source"
            else "No source available to refresh")
        QdButton(if (expanded) "Hide source details" else "Source details (${row.copies.size + 1})", { expanded = !expanded },
            variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
        if (expanded) {
            SourceHeader(row.provider, localLabel, false) {}
            if (selected != null) LocalObservation(row.localObservation, mode, projected)
            localManagement()
            for (source in row.copies) key(source.key) {
                if (source.key != selected?.key) RemoteSourceContent(source, mode, DashboardLayout.Cards, showRefresh = false)
            }
        }
    }
}

@Composable
private fun LocalObservation(snapshot: ProviderUsageSnapshot?, mode: UsageDisplayMode, projected: Boolean) {
    if (snapshot == null) { Hint("Waiting for first reading"); return }
    snapshot.windows.forEach { UsageWindowRow(it, mode, projected) }
    snapshot.toQuotaReading()?.let { ReadingAmounts(it) }
    ReadingTime("Collected · ", snapshot.collectedAt)
}

@Composable
internal fun LocalSourceDetails(account: AccountUiState) {
    var expanded by remember { mutableStateOf(false) }
    Hint("This device · ${account.accountKey.localSourceLabel()}")
    QdButton(if (expanded) "Hide source details" else "Source details", { expanded = !expanded },
        variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
    if (expanded) {
        val snapshot = account.snapshot
        if (snapshot == null) Hint("Waiting for first reading") else {
            ReadingTime(if (snapshot.stale) "Cached · " else "Collected on this device · ", snapshot.collectedAt)
            snapshot.toQuotaReading()?.let { ReadingAmounts(it) }
        }
    }
}
