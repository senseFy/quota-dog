package saien.quotadog

import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the production Java engine and plugins together, without provider credentials. */
class DesktopHttpClientTest {
    @Test
    fun postsAndDecodesJsonUsingTheProductionClient() = runBlocking {
        val requestBody = AtomicReference<String>()
        val requestType = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/usage") { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            requestType.set(exchange.requestHeaders.getFirst("Content-Type"))
            val response = """{"value":"quota","ignored":true}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            createHttpClient().use { client ->
                val response = client.post("http://127.0.0.1:${server.address.port}/usage") {
                    contentType(ContentType.Application.Json)
                    setBody(Message("quota"))
                }.body<Message>()
                assertEquals(Message("quota"), response)
                assertEquals("""{"value":"quota"}""", requestBody.get())
                assertTrue(requestType.get().startsWith("application/json"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun preservesOAuthFormEncodingAndProviderErrorBodies() = runBlocking {
        val requestBody = AtomicReference<String>()
        val requestType = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            requestType.set(exchange.requestHeaders.getFirst("Content-Type"))
            val response = """{"error":"invalid_grant"}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            createHttpClient().use { client ->
                val response = client.post("http://127.0.0.1:${server.address.port}/token") {
                    setBody(FormDataContent(Parameters.build {
                        append("grant_type", "refresh_token")
                        append("refresh_token", "test+token /&=")
                    }))
                }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("""{"error":"invalid_grant"}""", response.bodyAsText())
                val fields = requestBody.get().split('&').associate { field ->
                    val parts = field.split('=', limit = 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
                assertEquals("refresh_token", fields["grant_type"])
                assertEquals("test+token /&=", fields["refresh_token"])
                assertTrue(requestType.get().startsWith("application/x-www-form-urlencoded"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Serializable
    private data class Message(val value: String)
}
