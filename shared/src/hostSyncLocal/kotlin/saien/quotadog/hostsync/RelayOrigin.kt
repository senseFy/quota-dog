package saien.quotadog.hostsync

import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import saien.quotadog.createHttpClient

internal const val RELAY_ORIGIN = "http://127.0.0.1:18787"
const val hostSyncLabel = "Local"

/** Only the disposable local build substitutes provider HTTP. Relay connections use their own client. */
internal fun providerClient() = createHttpClient().also { client ->
    client.plugin(HttpSend).intercept { request ->
        if (request.url.host !in setOf("127.0.0.1", "localhost", "::1")) {
            request.url.apply { protocol = URLProtocol.HTTP; host = "127.0.0.1"; port = 18788 }
        }
        execute(request)
    }
}
