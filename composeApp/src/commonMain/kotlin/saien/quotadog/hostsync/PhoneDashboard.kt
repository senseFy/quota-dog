package saien.quotadog.hostsync

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import quotadog.composeapp.generated.resources.*
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
    var showComputer by rememberSaveable { mutableStateOf(false) }
    val computer = stringResource(Res.string.my_computer)
    val refresh = stringResource(Res.string.refresh_on_computer)
    val peers = remember(phone, s, computer, refresh) {
        if (phone == null || s?.paired != true) emptyList() else listOf(
            DashboardPeer(
                id = s.pairId ?: return@remember emptyList(), label = computer, refreshLabel = refresh, online = s.online,
                cards = s.cards, busyRefs = s.busyRefs, unconfirmedRefs = s.unconfirmed.values.toSet(),
                matchFor = phone::matchFor, refresh = phone::refresh, clockConflict = s.clockConflict,
            ),
        )
    }
    App(
        store = store, preferences = prefs, versionLabel = if (pilot) "$hostSyncLabel host-sync build" else version,
        usageSnapshotStore = remember { SettingsUsageSnapshotStore(phoneDisplay = true) },
        phoneDashboard = true,
        syncPeers = peers,
        onOpenComputer = { showComputer = true },
        hostSyncContent = { activeStore ->
            // Android creates the default store in App; iOS supplies its existing store. Bind to
            // the actual collector even when the pairing panel isn't visible.
            LaunchedEffect(phone, activeStore) { phone?.bindStore(activeStore) }
            if (showComputer) PhoneComputerSheet(s, phone, activeStore, prefs, onScan, onOpenImage) { showComputer = false }
        },
    )
}

@Composable
private fun PhoneComputerSheet(
    state: PhoneState?, phone: PhoneSync?, store: QuotaDogStore, preferences: AppPreferences,
    onScan: () -> Unit, onOpenImage: () -> Unit, onClose: () -> Unit,
) {
    var confirmForget by remember(state?.paired) { mutableStateOf(false) }
    var showGrants by remember(state?.paired) { mutableStateOf(false) }
    var startedPairing by rememberSaveable(state?.paired) { mutableStateOf(false) }
    val spacing = QdTheme.spacing
    QdPhoneSheet(stringResource(Res.string.close_computer_settings), onClose = {
        if (startedPairing && state?.paired != true) phone?.cancelPairing()
        onClose()
    }, header = {
        Text(stringResource(Res.string.my_computer), Modifier.weight(1f), style = QdTheme.typography.titleLarge, color = QdTheme.colors.textPrimary)
    }) {
        Text(stringResource(state?.note?.resource() ?: Res.string.pairing_unavailable),
            style = QdTheme.typography.bodyMedium, color = QdTheme.colors.textSecondary)
        if (phone == null) return@QdPhoneSheet
        if (state?.paired != true) {
            val digits = state?.digits
            if (digits != null) {
                Text(digits, style = QdTheme.typography.displayLarge, color = QdTheme.colors.primary)
                Hint(stringResource(Res.string.pairing_compare_hint))
            } else {
                Hint(stringResource(Res.string.pairing_instructions))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    QdButton(stringResource(Res.string.scan_qr), { startedPairing = true; onScan() })
                    QdButton(stringResource(Res.string.open_qr_image), { startedPairing = true; onOpenImage() }, variant = QdButtonVariant.Secondary)
                }
            }
            return@QdPhoneSheet
        }
        if (state.online && state.cards.isEmpty()) Hint(stringResource(Res.string.no_shared_accounts))
        if (state.legacy) Hint(stringResource(Res.string.update_for_sharing))
        if (!state.legacy || state.grants.isNotEmpty() || state.workspaceGrants.isNotEmpty()) {
            QdButton(stringResource(if (showGrants) Res.string.hide_phone_sharing else Res.string.share_from_phone), { showGrants = !showGrants },
                variant = QdButtonVariant.Secondary, size = QdButtonSize.Small)
            if (showGrants) {
                val local by store.state.collectAsState()
                val privacy by preferences.emailPrivacyMode.collectAsState()
                val sources = remember(local, privacy, state.legacy) {
                    if (state.legacy) GrantSources(emptyMap(), emptyMap()) else shareableSources(local.accounts.values, privacy)
                }
                Text(stringResource(Res.string.phone_to_computer), style = QdTheme.typography.titleMedium, color = QdTheme.colors.textPrimary)
                Hint(stringResource(Res.string.phone_grants_hint))
                SourceGrants(sources, state.grants, state.workspaceGrants) { phone.setAccess(state.pairId, it) }
            }
        }
        if (confirmForget) {
            Hint(stringResource(Res.string.forget_computer_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                QdButton(stringResource(Res.string.remove_from_phone), phone::forget, variant = QdButtonVariant.Danger)
                QdButton(stringResource(Res.string.cancel), { confirmForget = false }, variant = QdButtonVariant.Ghost)
            }
        } else QdButton(stringResource(Res.string.forget_computer), { confirmForget = true }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
    }
}

private fun PhoneNotice.resource() = when (this) {
    PhoneNotice.Connect -> Res.string.pairing_connect
    PhoneNotice.Paused -> Res.string.sharing_paused
    PhoneNotice.ForgetFailed -> Res.string.forget_failed
    PhoneNotice.Disconnected -> Res.string.computer_disconnected
    PhoneNotice.NetworkRequired -> Res.string.pairing_network_required
    PhoneNotice.InvalidQr -> Res.string.pairing_invalid_qr
    PhoneNotice.Verifying -> Res.string.pairing_verifying
    PhoneNotice.PairingFailed -> Res.string.pairing_failed
    PhoneNotice.TicketRejected -> Res.string.pairing_rejected
    PhoneNotice.UpdateRequired -> Res.string.update_to_resume
    PhoneNotice.LegacyConnected -> Res.string.update_for_more_accounts
    PhoneNotice.Compare -> Res.string.pairing_compare
    PhoneNotice.Connected -> Res.string.computer_connected
    PhoneNotice.Updated -> Res.string.computer_updated
    PhoneNotice.Denied -> Res.string.refresh_denied
    PhoneNotice.Busy -> Res.string.computer_busy
    PhoneNotice.Failed -> Res.string.computer_refresh_failed
    PhoneNotice.Revoked -> Res.string.pairing_revoked
    PhoneNotice.Unavailable -> Res.string.computer_unavailable
    PhoneNotice.PairingUnconfirmed -> Res.string.pairing_unconfirmed
    PhoneNotice.Refreshing -> Res.string.computer_refreshing
    PhoneNotice.RefreshUnconfirmed -> Res.string.phone_refresh_unconfirmed
}

@Composable internal fun Hint(text: String) {
    Text(text, style = QdTheme.typography.caption, color = QdTheme.colors.textSecondary)
}
