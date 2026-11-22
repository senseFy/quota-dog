package saien.quotadog.hostsync

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeNotNull
import saien.quotadog.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CompletionException
import kotlin.test.*

/** An enrolled peer can have a perfectly valid request in transit when the owner changes its grant. */
class HostAuthorizationTest {
    private fun identity() = DeviceIdentity(KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    })
    private fun client() = HttpClient(Java) { followRedirects = false; install(WebSockets) }

    private class MemoryVault : SyncVault {
        private val entries = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var failBefore: String? = null
        @Volatile var failAfter: String? = null
        override fun read(key: String) = entries[key]
        override fun write(key: String, value: String?) {
            if (key == failBefore) { failBefore = null; error("Locked test vault") }
            if (value == null) entries.remove(key) else entries[key] = value
            if (key == failAfter) { failAfter = null; error("Interrupted after durable write") }
        }
    }

    private class Peer(val tls: SecureChannel, val ws: WebSocketSession) {
        private val inbox = ArrayDeque<Msg>()
        suspend fun read() { inbox.addAll(tls.receive((ws.incoming.receive() as Frame.Binary).data)) }
        suspend fun next(): Msg { while (inbox.isEmpty()) read(); return inbox.removeFirst() }
        suspend fun result(id: String): RefreshStatus {
            var msg = next()
            while (msg is Msg.Manifest) msg = next()
            assertTrue(msg is Msg.Result && msg.id == id)
            return msg.status
        }
    }

    private suspend fun <T> HttpClient.session(invite: Invite, key: DeviceIdentity, ticket: String?, block: suspend Peer.() -> T): T {
        val ws = connect(invite.route, if (ticket == null) "enroll" else "device", ticket)
        try {
            SecureChannel(false, key, invite.pin) { ws.emit(Frame.Binary(true, it)) }.use { tls ->
                val peer = Peer(tls, ws)
                tls.flush(); while (tls.peer == null) peer.read()
                return peer.block()
            }
        } finally { ws.cancel() }
    }

    @Test fun staleAuthorizedRequestsAndOfflineRevocation() = runBlocking {
        val lib = System.getenv("QD_LOCAL_TLS_LIBRARY")
        assumeNotNull(lib); System.load(lib)
        val hostKey = identity(); val phoneKey = identity()
        val vault = MemoryVault()
        val source = AccountKey(ProviderId.DEVIN, randomHex(16))
        val tokens = SettingsTokenStore(MapSettings())
        val provider = "http://127.0.0.1:18788"
        tokens.save(source, OAuthTokenBundle("fixture-personal", "", expiresAtEpochMillis = Long.MAX_VALUE, apiServerUrl = provider))
        fun upstream(path: String, post: Boolean = false): String {
            val c = URI("$provider/$path").toURL().openConnection() as HttpURLConnection
            try { if (post) c.requestMethod = "POST"; return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
        val snapshots = SettingsUsageSnapshotStore(MapSettings())
        fun coldStore() = QuotaDogStore(QuotaDogClient(tokenStore = tokens), snapshots)
        var store = coldStore()
        store.detectAll()
        val before = upstream("count").toInt()
        fun host() = DesktopHost(client(), hostKey, vault, store, source)
        var host = host()
        val transport = client()
        try { withTimeout(30_000) {
            host.enable(); host.state.first { it.online }; host.invite()
            val invite = Invite.parse(host.state.first { it.qr != null }.qr!!)!!
            val ticket = transport.session(invite, phoneKey, null) {
                tls.send(Msg.Enroll(invite.secret)); val comparison = next() as Msg.Compare
                host.state.first { it.digits == comparison.digits }; host.approve(comparison.digits)
                val grant = next() as Msg.Grant
                tls.send(Msg.Stored); assertTrue(next() == Msg.Paired); grant.token
            }
            val phone = host.state.value.phones.single().id
            transport.session(invite, phoneKey, ticket) {
                tls.send(Msg.Sync); assertNull((next() as Msg.Manifest).card)
                host.setAccess(phone, Access.Refresh); val original = next() as Msg.Manifest
                val delayed = Msg.Refresh(original.card!!.ref, "1", original.revision)
                host.setAccess(phone, Access.View); assertEquals(Access.View, (next() as Msg.Manifest).card!!.access)
                host.setAccess(phone, Access.Refresh); assertEquals(original.card.ref, (next() as Msg.Manifest).card!!.ref)
                // Same ref and access again: only the permission revision distinguishes this tap.
                tls.send(delayed); assertEquals(RefreshStatus.Denied, result("1"))
                host.setAccess(phone, Access.Off); assertNull((next() as Msg.Manifest).card)
                host.setAccess(phone, Access.Refresh); val manifest = next() as Msg.Manifest
                val current = manifest.card!!.ref
                assertNotEquals(original.card.ref, current, "Regrant reused a withdrawn reference")
                tls.send(Msg.Refresh(original.card.ref, "2", manifest.revision)); assertEquals(RefreshStatus.Denied, result("2"))
                assertEquals(before, upstream("count").toInt())
                tls.send(Msg.Refresh(current, "3", manifest.revision)); assertEquals(RefreshStatus.Updated, result("3"))
                assertEquals(before + 1, upstream("count").toInt())
                // Once a request is running, changing permissions invalidates its success receipt,
                // even if the same level of access is restored before the response arrives.
                upstream("hold", true); tls.send(Msg.Refresh(current, "4", manifest.revision))
                while (upstream("count").toInt() != before + 2) delay(10)
                host.setAccess(phone, Access.View); assertEquals(Access.View, (next() as Msg.Manifest).card!!.access)
                host.setAccess(phone, Access.Refresh); assertEquals(Access.Refresh, (next() as Msg.Manifest).card!!.access)
                upstream("release", true); assertEquals(RefreshStatus.Denied, result("4"))
            }
            val ref = transport.session(invite, phoneKey, ticket) { tls.send(Msg.Sync); (next() as Msg.Manifest).card!!.ref }
            host.close(); store = coldStore(); host = host(); host.enable(); host.state.first { it.online }
            transport.session(invite, phoneKey, ticket) {
                tls.send(Msg.Sync)
                // A cold dashboard has no locally verified reading yet. It must not send a withdrawal.
                val manifest = next() as Msg.Manifest
                val cold = assertNotNull(manifest.card)
                assertEquals(ref, cold.ref); assertNull(cold.reading)
                assertEquals(before + 2, upstream("count").toInt())
                tls.send(Msg.Refresh(ref, "1", manifest.revision)); assertEquals(RefreshStatus.Updated, result("1"))
                upstream("hold", true); tls.send(Msg.Refresh(ref, "2", manifest.revision))
                while (upstream("count").toInt() != before + 4) delay(10)
                store.delete(source)
                assertNull((next() as Msg.Manifest).card)
                host.state.first { it.phones.single().access == Access.Off }
                upstream("release", true); assertEquals(RefreshStatus.Denied, result("2"))
                assertNull(store.state.value.accounts[source]); assertNull(snapshots.load(source))
            }
            host.pause(); host.state.first { !it.enabled }; host.close(); host = host()
            assertEquals(phone, host.state.value.phones.single().id)
            vault.failBefore = "phones"; host.revoke(phone)
            val failed = host.state.first { it.saveFailed }
            assertFalse(failed.enabled); assertEquals(phone, failed.phones.single().id)
            host.retrySave(); host.state.first { !it.saveFailed && it.phones.isEmpty() && !it.enabled }
            host.close(); host = host(); host.enable(); host.state.first { it.online }
            val failure = assertFailsWith<CompletionException> {
                java.net.http.HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer $ticket")
                    .buildAsync(URI("ws://127.0.0.1:18787/v1/${invite.route}/device"), object : WebSocket.Listener {}).join()
            }
            assertEquals(403, (failure.cause as WebSocketHandshakeException).response.statusCode())
            assertEquals(before + 4, upstream("count").toInt())
        } } finally { upstream("release", true); transport.close(); host.close() }
    }

    @Test fun interruptedEnrollmentCanRescanOrRecoverWithoutAnotherApproval() = runBlocking<Unit> {
        val lib = System.getenv("QD_LOCAL_TLS_LIBRARY")
        assumeNotNull(lib); System.load(lib)
        val store = QuotaDogStore(QuotaDogClient(tokenStore = SettingsTokenStore(MapSettings())), SettingsUsageSnapshotStore(MapSettings()))
        val hostKey = identity(); val hostVault = MemoryVault(); val phoneKey = identity(); val phoneVault = MemoryVault()
        fun newHost() = DesktopHost(client(), hostKey, hostVault, store)
        fun newPhone() = PhoneSync(client(), phoneKey, phoneVault)
        var host = newHost(); var phone = newPhone()
        suspend fun approveNewInvitation() {
            host.invite(); phone.pair(host.state.first { it.qr != null }.qr!!)
            val digits = phone.state.first { it.digits != null }.digits!!
            host.state.first { it.digits == digits }; host.approve(digits)
        }
        try { withTimeout(30_000) {
            host.enable(); host.state.first { it.online }; phone.useNetwork(1)
            // Phone persisted its ticket, but the Mac never received Stored.
            phoneVault.failAfter = "host"; approveNewInvitation()
            phone.state.first { it.note.startsWith("Pairing did not finish") }
            phone.close(); phone = newPhone(); assertFalse(phone.state.value.paired); phone.useNetwork(2)
            phone.state.first { !it.paired && it.note.contains("scan a new invitation") }
            assertTrue(host.state.value.phones.isEmpty())
            // On the new scan the Mac persists trust, but stops before registering the verifier.
            hostVault.failAfter = "phones"; approveNewInvitation()
            host.state.first { it.saveFailed && !it.online }; host.close(); host = newHost()
            host.enable(); phone.state.first { it.paired && it.online } // Fresh pinned session, no second scan.
            host.revoke(host.state.value.phones.single().id); phone.state.first { !it.paired && it.note.contains("revoked") }
        } } finally { phone.close(); host.close() }
    }
}
