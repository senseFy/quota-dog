package saien.quotadog

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the request loop, not just the response parser: a failed {project} followed by a
 * successful {} must not claim the project returned by loadCodeAssist. All requests go to loopback. */
class AntigravityQuotaBindingTest {
    @Test fun matchingRequiresTheSuccessfulProjectSpecificRequestKnownPoolAndSameBearerUserInfo() = runBlocking {
        val scenario = AtomicInteger()
        val payloads = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { r ->
            val mode = scenario.get()
            val body = r.requestBody.bufferedReader().use { it.readText() }
            val path = r.requestURI.path
            var status = 200
            val result = when {
                r.requestHeaders.getFirst("Authorization") != "Bearer fixture" -> { status = 401; "{}" }
                path.endsWith(":loadCodeAssist") -> """{"cloudaicompanionProject":"fixture-project"}"""
                path.endsWith(":retrieveUserQuotaSummary") -> {
                    payloads.add(body)
                    if (mode == 1 && body != "{}") { status = 403; "{}" } else {
                        val pool = if (mode == 3) "unverified-weekly" else "gemini-weekly"
                        val groups = """[{"displayName":"Gemini","buckets":[{"bucketId":"$pool","window":"weekly","remainingFraction":0.4}]}]"""
                        if (mode == 4) """{"response":{"project":"different","groups":$groups}}""" else """{"groups":$groups}"""
                    }
                }
                path.endsWith("/userinfo") -> if (mode == 2) { status = 401; """{"id":"should-not-be-used"}""" }
                    else """{"id":"123456789","email":"fixture@example.invalid"}"""
                else -> { status = 404; "{}" }
            }.encodeToByteArray()
            r.sendResponseHeaders(status, result.size.toLong())
            r.responseBody.use { it.write(result) }
        }
        server.start()
        try {
            HttpClient(Java) { followRedirects = false }.use { http ->
                http.plugin(HttpSend).intercept { request ->
                    request.url.apply { protocol = URLProtocol.HTTP; host = "127.0.0.1"; port = server.address.port }
                    execute(request)
                }
                val bound = AntigravityUsageFetcher.fetch(http, "fixture")
                assertEquals(listOf("""{"project":"fixture-project"}"""), payloads.toList())
                assertTrue(assertNotNull(bound.localQuota.matchParts).containsAll(listOf("123456789", "fixture-project", "gemini-weekly")))
                assertEquals("fixture-project", bound.projectId)
                payloads.clear(); scenario.set(1)
                val fallback = AntigravityUsageFetcher.fetch(http, "fixture")
                assertEquals(listOf("""{"project":"fixture-project"}""", "{}"), payloads.toList())
                assertNull(fallback.projectId)
                assertNull(fallback.localQuota.matchParts)
                assertTrue(fallback.localQuota.hasAccountReading)
                for (mode in 2..4) {
                    scenario.set(mode)
                    val unproven = AntigravityUsageFetcher.fetch(http, "fixture")
                    assertEquals(if (mode == 4) null else "fixture-project", unproven.projectId)
                    assertNull(unproven.localQuota.matchParts)
                    assertTrue(unproven.localQuota.hasAccountReading)
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
