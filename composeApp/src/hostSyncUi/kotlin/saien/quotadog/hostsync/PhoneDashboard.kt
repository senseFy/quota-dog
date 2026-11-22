package saien.quotadog.hostsync

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import saien.quotadog.AppPreferences
import saien.quotadog.ProviderId
import saien.quotadog.UsageDisplayMode
import saien.quotadog.app.App
import saien.quotadog.app.components.*
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.exactLocalTimeLabel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PhoneDashboard(phone: PhoneSync?, onScan: () -> Unit, onOpenImage: () -> Unit, version: String) {
    val prefs = remember { AppPreferences() }
    val mode by prefs.usageDisplayMode.collectAsState()
    val s = phone?.state?.collectAsState()?.value
    val digits = s?.digits
    var confirmForget by remember(s?.paired) { mutableStateOf(false) }
    val pilot = hostSyncLabel != "QuotaDog"
    var pairing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s?.paired) { if (s?.paired == true) pairing = false }
    val showConnection = pilot || pairing || digits != null || s?.paired == true
    App(preferences = prefs, versionLabel = if (pilot) "$hostSyncLabel host-sync build" else version,
        showLocalEmptyState = if (pilot) s?.card == null else !showConnection,
        onConnectComputer = if (!pilot && s?.paired != true) ({ pairing = true }) else null,
        hostSyncContent = panel@{
        if (!showConnection) return@panel
        QdCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("My computer", style = QdTheme.typography.titleLarge, color = QdTheme.colors.textPrimary)
                Text(s?.note ?: "Secure pairing unavailable on this device", style = QdTheme.typography.bodyMedium, color = QdTheme.colors.textSecondary)
                if (phone != null && s != null) when {
                    digits != null -> {
                        Text(digits, style = QdTheme.typography.displayLarge, color = QdTheme.colors.primary)
                        Hint("If the numbers match, allow this phone on your computer.")
                    }
                    !s.paired -> {
                        Hint("On your Mac, open Settings → Share from this computer → Pair a phone.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QdButton("Scan QR", onScan)
                            QdButton("Open QR image", onOpenImage, variant = QdButtonVariant.Secondary)
                        }
                    }
                    s.online && s.card == null -> Hint("No accounts shared. Choose a workspace on your computer.")
                    else -> Unit
                }
                if (s?.paired == true) {
                    if (confirmForget) {
                        Hint("Remove this pairing and its saved readings from this phone? Your local accounts stay.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QdButton("Remove from phone", { phone.forget() }, variant = QdButtonVariant.Danger)
                            QdButton("Cancel", { confirmForget = false }, variant = QdButtonVariant.Ghost)
                        }
                    } else QdButton("Forget computer…", { confirmForget = true }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
                }
                if (!pilot && s?.paired != true) QdButton("Cancel", {
                    phone?.cancelPairing(); pairing = false
                }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
            }
        }
        s?.card?.let { card ->
            QdCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QdProviderAvatar(ProviderId.DEVIN)
                        Column { Text("Devin", style = QdTheme.typography.titleLarge, color = QdTheme.colors.textPrimary); Hint("My computer · Desktop source") }
                    }
                    val value = card.reading
                    if (value == null) Hint("Waiting for first reading") else {
                        for ((label, period) in listOf("Daily quota" to value.daily, "Weekly quota" to value.weekly)) {
                            period ?: continue
                            val used = mode == UsageDisplayMode.Used
                            val ratio = if (used) period.used else 1.0 - period.used
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = QdTheme.typography.labelLarge, color = QdTheme.colors.textPrimary)
                                Text("${(ratio * 100).roundToInt()}% ${if (used) "used" else "left"}", style = QdTheme.typography.numeric, color = QdTheme.colors.textPrimary)
                            }
                            QdProgressBar(ratio.toFloat())
                            period.resetsAt?.let { Hint("Resets ${Instant.fromEpochMilliseconds(it).exactLocalTimeLabel().substringBefore('\n')}") }
                        }
                        value.extraUsd?.let { Hint("Extra balance · USD ${String.format(Locale.ROOT, "%.2f", it)}") }
                        val timestamp = Instant.fromEpochMilliseconds(value.collectedAt).exactLocalTimeLabel().substringBefore('\n')
                        Hint("Collected · $timestamp")
                    }
                    when {
                        !s.online -> Hint("Not connected · Last reading kept on this phone")
                        card.access == Access.View -> QdPlanLabel("View only")
                        else -> QdButton(if (s.busy) "Refreshing…" else "Refresh on computer", phone::refresh, enabled = !s.busy)
                    }
                }
            }
        }
    })
}

@Composable internal fun Hint(text: String) {
    Text(text, style = QdTheme.typography.caption, color = QdTheme.colors.textSecondary)
}
