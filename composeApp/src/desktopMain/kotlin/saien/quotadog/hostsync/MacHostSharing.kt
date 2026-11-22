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
import saien.quotadog.app.accountTitle
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

/** The window can close while the host stays online in the menu bar. Only an opted-in Mac opens the keychain. */
@Composable
internal fun rememberHostSettings(store: QuotaDogStore, preferences: AppPreferences): @Composable () -> Unit {
    val settings = remember { createPreferenceSettings() }
    val optIn = "host_sync_enabled_v1" // Device-local, never included in Dropbox preferences.
    var enabled by remember { mutableStateOf(settings.getBoolean(optIn, false)) }
    var load by remember { mutableStateOf(enabled) }
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
    return {
        // A paused Mac only reads its saved devices when settings are opened, without connecting.
        LaunchedEffect(Unit) { if (issue == null && settings.hasKey(optIn)) load = true }
        val state = host?.state?.collectAsState()?.value ?: HostState(enabled = enabled)
        val local by store.state.collectAsState()
        val privacy by preferences.emailPrivacyMode.collectAsState()
        val sources = local.accounts.values.filter { it.added && !it.accountKey.isPending && it.providerId == ProviderId.DEVIN && it.snapshot?.devinLoginTag != null }
            .associate { it.accountKey to it.accountTitle(privacy) }
        HostSharingCard(state, sources, host, issue) {
            if (issue == null) { enabled = !enabled; settings.putBoolean(optIn, enabled) }
            issue = null; load = true
        }
    }
}

@Composable
internal fun HostSharingCard(s: HostState, sources: Map<AccountKey, String>, host: DesktopHost?, issue: String?, toggle: () -> Unit) {
    QdCard(Modifier.fillMaxWidth().testTag("host-sharing")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Share from this computer", style = QdTheme.typography.titleLarge, color = QdTheme.colors.textPrimary)
            Hint(issue ?: when {
                s.saveFailed -> "Sharing is paused. Unlock your Mac keychain and retry to save the change."
                !s.enabled && s.phones.isNotEmpty() -> "Host paused. You can change access or revoke a phone without reconnecting."
                !s.enabled -> "Pair an Android phone to see your Devin quota. Nothing is shared until you choose an account."
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
                Hint("On Android, open Add account → Connect my computer. Scan within 5 minutes.")
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
            s.phones.forEachIndexed { index, phone ->
                Text("Phone ${index + 1}", style = QdTheme.typography.titleMedium, color = QdTheme.colors.textPrimary)
                Hint(if (sources.isEmpty()) "Import a Devin account and refresh it on this Mac to share it." else "Choose one Devin account for this phone.")
                for ((source, label) in sources) {
                    Text(label, style = QdTheme.typography.labelLarge, color = QdTheme.colors.textPrimary)
                    val access = if (phone.sourceId == source.accountId) phone.access else Access.Off
                    QdSegmentedControl(options = listOf("Not shared" to Access.Off, "View only" to Access.View, "Allow refresh" to Access.Refresh), selected = access,
                        onSelect = { next -> if (next != access) host?.setAccess(phone.id, next, source) })
                }
                if (phone.sourceId != null && sources.keys.none { it.accountId == phone.sourceId })
                    QdButton("Stop sharing unavailable account", { host?.setAccess(phone.id, Access.Off) }, variant = QdButtonVariant.Secondary)
                QdButton("Revoke phone", { host?.revoke(phone.id) }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
            }
        }
    }
}
