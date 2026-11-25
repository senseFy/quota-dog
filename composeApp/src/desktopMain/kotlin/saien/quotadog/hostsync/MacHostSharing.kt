package saien.quotadog.hostsync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import saien.quotadog.*
import saien.quotadog.app.DashboardPeer
import saien.quotadog.app.components.*
import saien.quotadog.app.theme.QdTheme
import java.nio.file.Files

private val macHostTls by lazy {
    val binary = Files.createTempFile("quotadog-host-sync", ".dylib").toFile()
    binary.deleteOnExit()
    checkNotNull(MacSyncKeychain::class.java.getResourceAsStream("/macos/libquotadog_host_tls.dylib")).use { input ->
        binary.outputStream().use(input::copyTo)
    }
    System.load(binary.absolutePath)
}

internal class MacHostSharing(val peers: List<DashboardPeer>, val settings: @Composable () -> Unit)

// Stable when another phone is revoked. Never show an admission verifier in the UI.
private val PhoneGrant.label get() = "Phone ${id.hashCode().toUInt().toString(16).padStart(8, '0').uppercase()}"

internal fun HostState.dashboardPeers(host: DesktopHost): List<DashboardPeer> = phones.map { phone ->
    DashboardPeer(id = phone.id, label = phone.label, refreshLabel = "Refresh on phone", online = enabled && online && phone.online,
        cards = phone.cards, busyRefs = phone.busyRefs, matchFor = { host.matchFor(phone.id, it) },
        refresh = { ref -> host.refreshPhone(phone.id, ref) }, clockConflict = phone.clockConflict)
}

/** The process keeps one host even when the main window closes. A Mac which never opted in does not open the keychain. */
@Composable
internal fun rememberMacHostSharing(store: QuotaDogStore, preferences: AppPreferences): MacHostSharing {
    val settings = remember { createPreferenceSettings() }
    val optIn = "host_sync_enabled_v1"
    var enabled by remember { mutableStateOf(settings.getBoolean(optIn, false)) }
    // A previously paired, paused Mac can show its last received readings without connecting.
    var load by remember { mutableStateOf(settings.hasKey(optIn)) }
    var issue by remember { mutableStateOf<String?>(null) }
    val host by produceState<DesktopHost?>(null, load, store) {
        value = null
        if (!load) return@produceState
        try {
            withContext(Dispatchers.IO) {
                macHostTls
                MacSyncKeychain.open().use { vault ->
                    val identity = vault.identity
                    HttpClient(Java) { followRedirects = false; install(WebSockets) { maxFrameSize = 65552 } }.use { client ->
                        DesktopHost(client, identity, vault, store, onStoragePause = {
                            settings.putBoolean(optIn, false); enabled = false
                        }).use { active ->
                            value = active
                            awaitCancellation()
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { issue = "Could not open secure pairing. Check that your Mac keychain is unlocked." }
        catch (_: LinkageError) { issue = "The secure pairing library is unavailable on this Mac." }
        settings.putBoolean(optIn, false); enabled = false; load = false
    }
    LaunchedEffect(host, enabled) { if (enabled) host?.enable() else host?.pause() }
    val state = host?.state?.collectAsState()?.value ?: HostState(enabled = enabled)
    val peers = remember(host, state) { host?.let { state.dashboardPeers(it) }.orEmpty() }
    return MacHostSharing(peers) {
        HostSharingCard(state, store, preferences, host, issue) {
            if (issue == null) { enabled = !enabled; settings.putBoolean(optIn, enabled) }
            issue = null; load = true
        }
    }
}

@Composable
internal fun HostSharingCard(s: HostState, store: QuotaDogStore, preferences: AppPreferences, host: DesktopHost?, issue: String?, toggle: () -> Unit) {
    val local by store.state.collectAsState()
    val privacy by preferences.emailPrivacyMode.collectAsState()
    val sources = remember(local, privacy) { shareableSources(local.accounts.values, privacy) }
    QdCard(Modifier.fillMaxWidth().testTag("host-sharing")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Share from this computer", style = QdTheme.typography.titleLarge, color = QdTheme.colors.textPrimary)
            Hint(issue ?: when {
                s.saveFailed -> "Sharing is paused. Unlock your Mac keychain and retry to save the change."
                !s.enabled && s.phones.isNotEmpty() -> "Host paused. You can change access or revoke a phone without reconnecting."
                !s.enabled -> "Pair your phone to share quota readings in either direction. Nothing is shared until you choose an account."
                s.online -> "$hostSyncLabel relay connected · Devices verify each other directly."
                else -> "Connecting to the relay…"
            })
            if (s.saveFailed) {
                QdButton("Retry save", { host?.retrySave() }, variant = QdButtonVariant.Secondary)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QdButton(if (issue != null) "Retry" else if (s.enabled) "Pause host" else "Enable host", toggle, variant = QdButtonVariant.Secondary)
                if (host != null && s.online && s.qr == null && s.digits == null && s.phones.size < MAX_PAIRED_PHONES) QdButton("Pair a phone", host::invite)
            }
            s.qr?.let { qr ->
                val matrix = remember(qr) { QRCodeWriter().encode(qr, BarcodeFormat.QR_CODE, 0, 0) }
                Canvas(Modifier.size(200.dp).background(Color.White).testTag("pairing-qr")) {
                    val unit = (size.width / matrix.width).toInt().coerceAtLeast(1)
                    val inset = ((size.width - unit * matrix.width) / 2).toInt()
                    for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x,y])
                        drawRect(Color.Black, Offset((inset + x * unit).toFloat(), (inset + y * unit).toFloat()), Size(unit.toFloat(), unit.toFloat()))
                }
                Hint("On your phone, open Add account → Connect my computer. Scan within 5 minutes.")
                QdButton("Cancel invitation", { host?.cancelInvite() }, variant = QdButtonVariant.Ghost)
            }
            s.digits?.let { digits ->
                Text(digits, style = QdTheme.typography.displayLarge, color = QdTheme.colors.primary)
                Hint("Allow only if your phone shows the same number.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QdButton("Allow this phone", { host?.approve(digits) })
                    QdButton("Cancel", { host?.cancelInvite() }, variant = QdButtonVariant.Ghost)
                }
            }
            for (phone in s.phones) key(phone.id) {
                Text(phone.label, style = QdTheme.typography.titleLarge, color = QdTheme.colors.textPrimary)
                Hint(when {
                    phone.legacy -> "Older QuotaDog version. Update the phone for multiple sources and sharing in both directions."
                    phone.online -> "Connected"
                    else -> "Offline · Changes take effect when the phone reconnects."
                })
                Text("This Mac → ${phone.label}", style = QdTheme.typography.titleMedium, color = QdTheme.colors.textPrimary)
                Hint("Choose which accounts this phone can view or refresh on this Mac.")
                val candidates = if (phone.legacy) {
                    val existing = phone.grants.keys.firstOrNull { it.providerId == ProviderId.DEVIN }
                    GrantSources(sources.accounts.filterKeys { it.providerId == ProviderId.DEVIN && (existing == null || it == existing) }, emptyMap())
                } else sources
                SourceGrants(candidates, phone.grants, phone.workspaceGrants) { edit ->
                    host?.setAccess(phone.id, edit)
                }
                Text("${phone.label} → This Mac", style = QdTheme.typography.titleMedium, color = QdTheme.colors.textPrimary)
                Hint(if (phone.cards.isEmpty()) "No accounts shared from this phone. Choose Share from this phone on the phone to grant access."
                    else "${phone.cards.size} shared ${if (phone.cards.size == 1) "source" else "sources"} on your dashboard. Change these permissions on the phone.")
                QdButton("Revoke phone", { host?.revoke(phone.id) }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
            }
        }
    }
}
