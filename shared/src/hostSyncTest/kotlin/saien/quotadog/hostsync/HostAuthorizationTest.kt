package saien.quotadog.hostsync

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun DesktopHost.choose(store: QuotaDogStore, phone: String, access: Access, key: AccountKey, workspace: Boolean = false) {
        val device = state.value.phones.single { it.id == phone }
        val previous = (if (workspace) device.workspaceGrants else device.grants)[key]
        setAccess(phone, GrantEdit(key, workspace, previous, access, store.state.value.accounts[key]?.snapshot))
    }

    private fun PhoneSync.choose(store: QuotaDogStore, key: AccountKey, access: Access) {
        val phone = state.value
        setAccess(phone.pairId, GrantEdit(key, false, phone.grants[key], access, store.state.value.accounts[key]?.snapshot))
    }

    private fun upstream(path: String, post: Boolean = false): String {
        val c = URI("http://127.0.0.1:18788/$path").toURL().openConnection() as HttpURLConnection
        try { if (post) c.requestMethod = "POST"; return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

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

    @Test fun eightCollectorsTwoPhonesAndAnIndependentWorkspace() = runBlocking<Unit> {
        val lib = System.getenv("QD_LOCAL_TLS_LIBRARY")
        assumeNotNull(lib); System.load(lib)
        val provider = "http://127.0.0.1:18788"
        fun counts() = Json.parseToJsonElement(URI("$provider/provider-counts").toURL().readText()).jsonObject
            .mapValues { it.value.jsonPrimitive.content.toInt() }
        val http = providerClient()
        fun store(tokens: SettingsTokenStore) = QuotaDogStore(QuotaDogClient(tokenStore = tokens, httpClient = http), SettingsUsageSnapshotStore(MapSettings()))
        suspend fun add(tokens: SettingsTokenStore, id: ProviderId, origin: String): AccountKey {
            val token = if (id == ProviderId.DEVIN) "fixture-personal" else "fixture-${id.name.lowercase()}-$origin"
            return tokens.save(id, OAuthTokenBundle(token, "", email = "owner@example.invalid", expiresAtEpochMillis = Long.MAX_VALUE,
                apiServerUrl = provider.takeIf { id == ProviderId.DEVIN }))
        }
        val macTokens = SettingsTokenStore(MapSettings()); val phoneTokens = SettingsTokenStore(MapSettings())
        val keys = ProviderId.entries.associateWith { add(macTokens, it, "mac") }
        val other = add(macTokens, ProviderId.CODEX, "other")
        val reverse = add(phoneTokens, ProviderId.CODEX, "phone")
        val mac = store(macTokens); val mobile = store(phoneTokens)
        val host = DesktopHost(client(), identity(), MemoryVault(), mac)
        val av = MemoryVault(); val bv = MemoryVault()
        val phoneKey = identity()
        var a = PhoneSync(client(), phoneKey, av); val b = PhoneSync(client(), identity(), bv)
        suspend fun pair(p: PhoneSync): String {
            val before = host.state.value.phones.map { it.id }
            p.useNetwork(1); host.invite(); p.pair(host.state.first { it.qr != null }.qr!!)
            val digits = p.state.first { it.digits != null }.digits!!
            host.state.first { it.digits == digits }; host.approve(digits)
            p.state.first { it.online && !it.legacy }
            return host.state.first { it.phones.any { p -> p.id !in before && p.online } }.phones.single { it.id !in before }.id
        }
        try { withTimeout(60_000) {
            mac.detectAll(); mobile.detectAll()
            for ((id, key) in keys) assertNotNull(mac.state.value.accounts[key]?.snapshot?.toQuotaReading(), "No local reading: $id")
            assertNotNull(mobile.state.value.accounts[reverse]?.snapshot?.toQuotaReading())
            a.bindStore(mobile); host.enable(); host.state.first { it.online }
            val one = pair(a); val two = pair(b)
            assertTrue(a.state.value.cards.isEmpty()); assertTrue(b.state.value.cards.isEmpty())
            val baseline = counts()
            for ((id, key) in keys) {
                host.choose(mac, one, if (id == ProviderId.CURSOR) Access.Refresh else Access.View, key)
                a.state.first { s -> s.cards.any { it.provider == id && it.reading != null } }
            }
            val cards = a.state.value.cards.associateBy { it.provider }
            assertEquals(ProviderId.entries.toSet(), cards.keys)
            fun reading(id: ProviderId) = assertNotNull(cards.getValue(id).reading)
            fun ratio(id: ProviderId, kind: QuotaWindowKind, expected: Double) =
                assertEquals(expected, assertNotNull(reading(id).windows.single { it.kind == kind }.used), 0.000001, id.name)
            ratio(ProviderId.CODEX, QuotaWindowKind.CodexSecondary, .47)
            assertEquals(2, (reading(ProviderId.CODEX).details as QuotaDetails.Codex).available)
            ratio(ProviderId.CLAUDE_CODE, QuotaWindowKind.ClaudeOpusWeekly, .81)
            ratio(ProviderId.DROID, QuotaWindowKind.DroidCoreWeekly, .79)
            assertEquals(12345L, (reading(ProviderId.DROID).details as QuotaDetails.Droid).extraUsdCents)
            assertEquals(.56, assertNotNull(reading(ProviderId.ANTIGRAVITY).windows.single().used), .000001)
            assertEquals(.45, assertNotNull(reading(ProviderId.GROK).windows.single().used), .000001)
            assertEquals(14.41, (reading(ProviderId.AMP).details as QuotaDetails.AmpIndividual).creditsUsd)
            assertEquals(39273, (reading(ProviderId.CURSOR).details as QuotaDetails.Cursor).includedUsedUsdCents)
            ratio(ProviderId.CURSOR, QuotaWindowKind.CursorApi, .77862)
            ratio(ProviderId.DEVIN, QuotaWindowKind.DevinWeekly, .75)
            assertEquals(2.5, (reading(ProviderId.DEVIN).details as QuotaDetails.Devin).extraUsd)
            for (id in listOf(ProviderId.AMP, ProviderId.CURSOR, ProviderId.GROK, ProviderId.DEVIN, ProviderId.DROID)) assertNull(cards.getValue(id).match)
            val phoneCache = checkNotNull(av.read("host"))
            for (privateValue in listOf("91.72", "private-user-id", "private-personal", "PRIVATE-REDEEM-ID", "Private Workspace", "owner@example", "fixture-", "127.0.0.1"))
                assertFalse(privateValue in phoneCache, "Non-shareable field reached phone")
            assertTrue(b.state.value.cards.isEmpty()); assertTrue(host.state.value.phones.all { it.cards.isEmpty() })
            assertEquals(baseline, counts(), "Sharing queried a provider")

            val devin = keys.getValue(ProviderId.DEVIN)
            host.choose(mac, two, Access.Refresh, devin)
            val allowed = b.state.first { it.cards.singleOrNull()?.reading != null }.cards.single()
            val before = upstream("count").toInt()
            upstream("hold", true)
            try {
                b.refresh(allowed.ref)
                while (upstream("count").toInt() == before) delay(10)
                val local = async(start = CoroutineStart.UNDISPATCHED) { mac.refresh(devin) }
                assertFalse(local.isCompleted)
                a.refresh(cards.getValue(ProviderId.DEVIN).ref) // View-only: must not start a query.
                upstream("release", true); assertTrue(local.await())
                b.state.first { it.note == "Updated by computer" }
                a.state.first { s -> s.cards.any { it.provider == ProviderId.DEVIN && it.reading!!.collectedAt > allowed.reading!!.collectedAt } }
                assertEquals(before + 1, upstream("count").toInt())
            } finally { upstream("release", true) }
            host.choose(mac, two, Access.Off, devin); b.state.first { it.cards.isEmpty() }

            val amp = keys.getValue(ProviderId.AMP)
            host.choose(mac, one, Access.View, amp, true)
            val ws = a.state.first { s -> s.cards.any { it.workspace && it.reading != null } }.cards.single { it.workspace }
            assertEquals(QuotaDetails.AmpWorkspace(91.72), ws.reading!!.details); assertTrue(ws.reading.windows.isEmpty())
            host.choose(mac, one, Access.Off, amp, true); a.state.first { s -> s.cards.none { it.workspace } }
            assertEquals(8, a.state.value.cards.size); assertFalse("91.72" in checkNotNull(av.read("host")))

            host.choose(mac, two, Access.View, other)
            val company = b.state.first { s -> s.cards.singleOrNull()?.reading != null }.cards.single()
            assertNotEquals(cards.getValue(ProviderId.CODEX).match, company.match)
            assertEquals(.99, company.reading!!.windows.first().used)
            val personal = keys.getValue(ProviderId.CODEX)
            host.choose(mac, two, Access.View, personal)
            val second = b.state.first { s -> s.cards.size == 2 && s.cards.all { it.reading != null } }.cards.single { it.ref != company.ref }
            assertNotEquals(second.ref, cards.getValue(ProviderId.CODEX).ref)
            assertNotEquals(second.match, cards.getValue(ProviderId.CODEX).match, "Same account linkable across pairings")
            assertEquals(a.matchFor(mobile.state.value.accounts[reverse]!!.snapshot!!), assertNotNull(cards.getValue(ProviderId.CODEX).match))
            assertNotEquals(host.matchFor(two, mac.state.value.accounts[personal]!!.snapshot!!), company.match, "Workspace ignored")

            a.choose(mobile, reverse, Access.Refresh)
            val fromPhone = host.state.first { s -> s.phones.single { it.id == one }.cards.singleOrNull()?.reading != null }
                .phones.single { it.id == one }.cards.single()
            assertEquals(cards.getValue(ProviderId.CODEX).match, fromPhone.match)
            val initial = fromPhone.reading!!.collectedAt
            host.refreshPhone(one, fromPhone.ref)
            host.state.first { s -> s.phones.single { it.id == one }.cards.single().reading!!.collectedAt > initial }
            assertEquals(baseline.getValue("codex-phone") + 1, counts()["codex-phone"])
            a.refresh(cards.getValue(ProviderId.CURSOR).ref); a.state.first { it.note == "Updated by computer" }
            val after = counts()
            assertEquals(baseline + ("cursor-mac" to baseline.getValue("cursor-mac") + 1) + ("codex-phone" to baseline.getValue("codex-phone") + 1), after)
            a.useNetwork(null); a.state.first { !it.online }; a.useNetwork(2)
            a.state.first { it.online && it.cards.size == 8 }; assertEquals(after, counts())
            assertEquals(setOf(company.ref, second.ref), b.state.value.cards.map { it.ref }.toSet(), "Relayed a phone-owned source to another phone")

            val phoneDevin = add(phoneTokens, ProviderId.DEVIN, "phone"); assertTrue(mobile.refresh(phoneDevin))
            suspend fun phoneCards(predicate: (List<SharedCard>) -> Boolean) = host.state.first { s ->
                s.phones.single { it.id == one }.let { it.online && predicate(it.cards) }
            }.phones.single { it.id == one }.cards
            av.failBefore = "host"; a.choose(mobile, reverse, Access.Off)
            a.state.first { !it.online && it.note.startsWith("Sharing paused") }
            a.choose(mobile, phoneDevin, Access.Refresh)
            val devinRef = phoneCards { it.singleOrNull()?.provider == ProviderId.DEVIN }.single().ref

            // The accepted query can finish at its collector, but its receipt must die with the old session.
            val beforeDisconnect = upstream("count").toInt(); upstream("hold", true)
            try {
                host.refreshPhone(one, devinRef)
                while (upstream("count").toInt() == beforeDisconnect) delay(10)
                a.useNetwork(null); a.state.first { !it.online }
                host.state.first { s -> !s.phones.single { it.id == one }.online }
            } finally { upstream("release", true) }
            mobile.state.first { it.accounts[phoneDevin]?.busy == false }; a.useNetwork(3)
            phoneCards { it.singleOrNull()?.ref == devinRef }; assertEquals(beforeDisconnect + 1, upstream("count").toInt())

            a.choose(mobile, reverse, Access.Refresh); phoneCards { it.size == 2 }
            av.failBefore = "host"; a.choose(mobile, reverse, Access.View)
            a.state.first { !it.online && it.note.startsWith("Sharing paused") }
            a.close(); a = PhoneSync(client(), phoneKey, av)
            assertEquals(Access.View, a.state.value.grants[reverse]?.access, "A locked vault restored a withdrawn permission")
            a.bindStore(mobile); a.useNetwork(4)
            phoneCards { cards -> cards.size == 2 && cards.any { it.provider == ProviderId.CODEX && it.access == Access.View } &&
                cards.any { it.ref == devinRef && it.access == Access.Refresh } }
            host.revoke(one); a.state.first { !it.paired }; assertEquals(2, b.state.value.cards.size)
            assertTrue(host.state.value.phones.single().cards.isEmpty()); assertNotNull(phoneTokens.load(reverse))
        } } finally { a.close(); b.close(); host.close(); http.close() }
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
        val snapshots = SettingsUsageSnapshotStore(MapSettings())
        fun coldStore() = QuotaDogStore(QuotaDogClient(tokenStore = tokens), snapshots)
        var store = coldStore()
        store.detectAll()
        val before = upstream("count").toInt()
        fun host() = DesktopHost(client(), hostKey, vault, store)
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
            fun setAccess(access: Access) = host.choose(store, phone, access, source)
            transport.session(invite, phoneKey, ticket) {
                tls.send(Msg.Sync); assertNull((next() as Msg.Manifest).card)
                setAccess(Access.Refresh); val original = next() as Msg.Manifest
                val delayed = Msg.Refresh(original.card!!.ref, "1", original.revision)
                setAccess(Access.View); assertEquals(Access.View, (next() as Msg.Manifest).card!!.access)
                setAccess(Access.Refresh); assertEquals(original.card.ref, (next() as Msg.Manifest).card!!.ref)
                // Same ref and access again: only the permission revision distinguishes this tap.
                tls.send(delayed); assertEquals(RefreshStatus.Denied, result("1"))
                setAccess(Access.Off); assertNull((next() as Msg.Manifest).card)
                setAccess(Access.Refresh); val manifest = next() as Msg.Manifest
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
                setAccess(Access.View); assertEquals(Access.View, (next() as Msg.Manifest).card!!.access)
                setAccess(Access.Refresh); assertEquals(Access.Refresh, (next() as Msg.Manifest).card!!.access)
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
                host.state.first { source !in it.phones.single().grants }
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
