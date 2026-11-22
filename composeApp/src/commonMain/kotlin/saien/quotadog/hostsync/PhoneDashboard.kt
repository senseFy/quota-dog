package saien.quotadog.hostsync

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import saien.quotadog.AppPreferences
import saien.quotadog.QuotaDogStore
import saien.quotadog.SettingsUsageSnapshotStore
import saien.quotadog.app.App
import saien.quotadog.app.DashboardPeer
import saien.quotadog.app.components.*
import saien.quotadog.app.theme.QdTheme

@Composable
fun PhoneDashboard(phone: PhoneSync?, onScan: () -> Unit, onOpenImage: () -> Unit, version: String, store: QuotaDogStore? = null) {
    val prefs = remember { AppPreferences() }
    val s = phone?.state?.collectAsState()?.value
    val pilot = hostSyncLabel != "QuotaDog"
    var pairing by rememberSaveable { mutableStateOf(false) }
    var wasPaired by remember { mutableStateOf(false) }
    LaunchedEffect(s?.paired) {
        if (s != null) {
            if (s.paired) pairing = false else if (wasPaired) pairing = true // Show the reason for disconnection.
            wasPaired = s.paired
        }
    }
    val showConnection = pilot || pairing || s?.digits != null || s?.paired == true
    val peers = remember(phone, s) {
        if (phone == null || s?.paired != true) emptyList() else listOf(DashboardPeer(
            id = s.pairId ?: return@remember emptyList(), label = "My computer", refreshLabel = "Refresh on computer", online = s.online,
            cards = s.cards, busyRefs = s.busyRefs, matchFor = phone::matchFor, refresh = phone::refresh, clockConflict = s.clockConflict,
        ))
    }
    App(
        store = store, preferences = prefs, versionLabel = if (pilot) "$hostSyncLabel host-sync build" else version,
        usageSnapshotStore = remember { SettingsUsageSnapshotStore(phoneDisplay = true) },
        showLocalEmptyState = pilot || !showConnection,
        phoneDashboard = true,
        syncPeers = peers,
        onConnectComputer = if (!pilot && s?.paired != true) ({ pairing = true }) else null,
        hostSyncContent = { activeStore ->
            // Android creates the default store in App; iOS supplies its existing store. Bind to
            // the actual collector even when the pairing panel isn't visible.
            LaunchedEffect(phone, activeStore) { phone?.bindStore(activeStore) }
            if (showConnection) PhoneConnectionCard(s, phone, activeStore, prefs, pilot, onScan, onOpenImage) {
                phone?.cancelPairing()
                pairing = false
            }
        },
    )
}

@Composable
private fun PhoneConnectionCard(
    state: PhoneState?, phone: PhoneSync?, store: QuotaDogStore, preferences: AppPreferences, pilot: Boolean,
    onScan: () -> Unit, onOpenImage: () -> Unit, onCancel: () -> Unit,
) {
    var confirmForget by remember(state?.paired) { mutableStateOf(false) }
    var showGrants by remember(state?.paired) { mutableStateOf(false) }
    val spacing = QdTheme.spacing
    QdCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text("My computer", style = QdTheme.typography.titleLarge, color = QdTheme.colors.textPrimary)
            Text(state?.note ?: "Secure pairing unavailable on this device", style = QdTheme.typography.bodyMedium, color = QdTheme.colors.textSecondary)
            if (phone == null || state?.paired != true) {
                state?.digits?.let { digits ->
                    Text(digits, style = QdTheme.typography.displayLarge, color = QdTheme.colors.primary)
                    Hint("If the numbers match, allow this phone on your computer.")
                } ?: if (phone != null) {
                    Hint("On your Mac, open Settings → Share from this computer → Pair a phone.")
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        QdButton("Scan QR", onScan)
                        QdButton("Open QR image", onOpenImage, variant = QdButtonVariant.Secondary)
                    }
                } else Unit
                if (!pilot) QdButton("Cancel", onCancel, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
                return@Column
            }
            if (state.online && state.cards.isEmpty()) Hint(if (pilot) "No accounts shared. Choose a workspace on your computer."
                else "No accounts shared. Choose accounts for this phone in your computer’s sharing settings.")
            if (state.legacy) Hint("Update QuotaDog on your computer to share multiple accounts in both directions.")
            if (!state.legacy || state.grants.isNotEmpty() || state.workspaceGrants.isNotEmpty()) {
                QdButton(if (showGrants) "Hide phone sharing" else "Share from this phone", { showGrants = !showGrants },
                    variant = QdButtonVariant.Secondary, size = QdButtonSize.Small)
                if (showGrants) {
                    val local by store.state.collectAsState()
                    val privacy by preferences.emailPrivacyMode.collectAsState()
                    val sources = remember(local, privacy, state.legacy) {
                        if (state.legacy) GrantSources(emptyMap(), emptyMap()) else shareableSources(local.accounts.values, privacy)
                    }
                    Text("This phone → My computer", style = QdTheme.typography.titleMedium, color = QdTheme.colors.textPrimary)
                    Hint("Choose what your computer can view or refresh on this phone. This does not change access to accounts on your computer.")
                    SourceGrants(sources, state.grants, state.workspaceGrants) { phone.setAccess(state.pairId, it) }
                }
            }
            if (confirmForget) {
                Hint("Remove this pairing and its saved readings from this phone? Your local accounts stay.")
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    QdButton("Remove from phone", phone::forget, variant = QdButtonVariant.Danger)
                    QdButton("Cancel", { confirmForget = false }, variant = QdButtonVariant.Ghost)
                }
            } else QdButton("Forget computer…", { confirmForget = true }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
        }
    }
}

@Composable internal fun Hint(text: String) {
    Text(text, style = QdTheme.typography.caption, color = QdTheme.colors.textSecondary)
}
