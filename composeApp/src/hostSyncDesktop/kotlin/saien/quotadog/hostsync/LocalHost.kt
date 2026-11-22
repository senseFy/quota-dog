package saien.quotadog.hostsync

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import saien.quotadog.*
import saien.quotadog.app.App
import java.io.File
import java.util.UUID

/** Test-only entry point. Neither the normal token store nor the updater/tray is initialized. */
internal class LocalHost(val directory: File) : AutoCloseable {
    private val vault = PilotKeychain(directory)
    val tokens = SettingsTokenStore(MapSettings())
    val snapshots = SettingsUsageSnapshotStore(MapSettings())
    val preferences = AppPreferences(MapSettings())
    val dropbox = CloudSyncCoordinator(CloudSyncLocalRepository(tokens, snapshots, preferences, MapSettings()), DropboxSyncBackend(MapSettings()))
    val store = QuotaDogStore(QuotaDogClient(tokenStore = tokens), snapshots)
    val source = AccountKey(ProviderId.DEVIN, vault.read("source") ?: UUID.randomUUID().toString().also { vault.write("source", it) })
    val host: DesktopHost
    init {
        System.load(requireNotNull(System.getenv("QD_LOCAL_TLS_LIBRARY")))
        val identity = vault.identity // Never create an identity to reclaim an existing route.
        if (hostSyncStaging) directory.resolve("relay-bindings.json").writeText(stagingRelayBindings(vault))
        runBlocking {
            for ((key, tag) in listOf(source to "personal", AccountKey(ProviderId.DEVIN, UUID.randomUUID().toString()) to "unshared")) {
                tokens.save(key, OAuthTokenBundle("fixture-$tag", "", email = "owner@example.invalid", expiresAtEpochMillis = Long.MAX_VALUE, apiServerUrl = "http://127.0.0.1:18788"))
            }
        }
        host = DesktopHost(HttpClient(Java) { followRedirects = false; install(WebSockets) { maxFrameSize = 65552 } }, identity, vault, store, source)
    }
    override fun close() { host.close(); vault.close() }
    fun deleteFixture() { host.close(); try { vault.deleteFixture() } finally { vault.close() } }
}

fun main() = application {
    val fixture = remember { LocalHost(File(requireNotNull(System.getenv("QD_LOCAL_PROFILE")))) }
    DisposableEffect(fixture) { onDispose { fixture.close() } }
    Window(onCloseRequest = ::exitApplication, title = "QuotaDog · $hostSyncLabel host sync", state = rememberWindowState(width = 1000.dp, height = 800.dp)) {
        LocalHostDashboard(fixture)
    }
}

@Composable internal fun LocalHostDashboard(f: LocalHost) {
    App(f.tokens, f.snapshots, f.preferences, f.dropbox, f.store, versionLabel = "$hostSyncLabel host-sync build", hostSyncContent = {
        val s by f.host.state.collectAsState()
        HostSharingCard(s, mapOf(f.source to "Devin personal"), f.host, null) {
            if (s.enabled) f.host.pause() else f.host.enable()
        }
    })
}
