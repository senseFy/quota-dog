package saien.quotadog

import com.russhwolf.settings.MapSettings
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DevinHostSharingTest {
    @Test fun reusingAnEmailDoesNotLetTheOldGrantQueryTheNewLogin() = runBlocking {
        val calls = AtomicInteger()
        val api = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        api.createContext("/exa.seat_management_pb.SeatManagementService/GetUserStatus") { exchange ->
            calls.incrementAndGet()
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val remaining = if ("first-fixture" in request) 75 else 3
            val body = """{"userStatus":{"planStatus":{"dailyQuotaRemainingPercent":$remaining}}}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        api.start()
        try {
            createHttpClient().use { http ->
                val tokens = SettingsTokenStore(MapSettings())
                val snapshots = SettingsUsageSnapshotStore(MapSettings())
                val store = QuotaDogStore(QuotaDogClient(tokenStore = tokens, httpClient = http), snapshots)
                val first = OAuthTokenBundle("first-fixture", "", email = "owner@example.invalid",
                    expiresAtEpochMillis = Long.MAX_VALUE, apiServerUrl = "http://127.0.0.1:${api.address.port}")
                val key = tokens.save(ProviderId.DEVIN, first)
                store.detectAll()
                fun reading() = assertNotNull(store.state.value.accounts[key]?.snapshot)
                val grant = assertNotNull(reading().loginTag)
                assertNull(assertNotNull(snapshots.load(key)).loginTag)
                store.refresh(key, grant)
                assertEquals(2, calls.get())

                val second = first.copy(accessToken = "second-fixture")
                tokens.save(key, second)
                store.refresh(key, grant) // The on-screen snapshot still belongs to the first login.
                assertEquals(2, calls.get())
                assertEquals(0.25, reading().windows.single().usedRatio)
                store.refresh(key) // An explicit local refresh may read the replacement.
                assertNotEquals(grant, assertNotNull(reading().loginTag))
                assertEquals(0.97, reading().windows.single().usedRatio)
                store.refresh(key, grant)
                assertEquals(3, calls.get())

                // Re-saving the same login is harmless; recreating a deleted row is a new grant boundary.
                val replacement = assertNotNull(reading().loginTag)
                tokens.save(key, second)
                assertEquals(replacement, store.currentLogin(key))
                store.delete(key)
                tokens.save(key, second)
                assertNotEquals(replacement, assertNotNull(store.currentLogin(key)))
                assertFalse(store.refresh(key, replacement))
                assertNull(store.state.value.accounts[key]); assertEquals(3, calls.get())
            }
        } finally { api.stop(0) }
    }
}
