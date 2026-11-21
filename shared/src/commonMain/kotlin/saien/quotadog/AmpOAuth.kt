package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal data class AmpDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Int,
    val intervalSeconds: Int,
    val serverUrl: String = AmpAuthParser.DEFAULT_API_SERVER_URL,
) {
    val authorizationUrl: String
        get() = verificationUriComplete.ifBlank { verificationUri }
}

internal sealed class AmpTokenPollResult {
    data class Success(val token: OAuthTokenBundle) : AmpTokenPollResult()
    data class Pending(val nextIntervalSeconds: Int) : AmpTokenPollResult()
    data class Failed(val error: ProviderException) : AmpTokenPollResult()
}

/**
 * RFC 8628 device-code OAuth for Amp, matching `amp login --oauth`:
 *
 * - Start: `POST https://ampcode.com/api/auth/cli-device-authorization` (JSON `{}`)
 * - Poll: `POST https://ampcode.com/api/auth/cli-device-token`
 *   with form `grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=...`
 */
internal object AmpOAuth {
    private const val DEVICE_AUTHORIZE_PATH = "/api/auth/cli-device-authorization"
    private const val DEVICE_TOKEN_PATH = "/api/auth/cli-device-token"
    private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    private const val DEFAULT_POLL_INTERVAL_SECONDS = 5
    private const val MIN_EXPIRES_IN_SECONDS = 60
    private const val MAX_POLL_SECONDS = 30 * 60
    private const val NO_EXPIRY_MILLIS = 100L * 365L * 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestDeviceCode(
        httpClient: HttpClient,
        serverUrl: String = AmpAuthParser.DEFAULT_API_SERVER_URL,
    ): AmpDeviceCode {
        val base = AmpAuthParser.cleanServerUrl(serverUrl) ?: AmpAuthParser.DEFAULT_API_SERVER_URL
        val response = httpClient.post("$base$DEVICE_AUTHORIZE_PATH") {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody("{}")
        }
        return parseDeviceCodeResponse(response.status.value, response.bodyAsText(), base)
    }

    suspend fun waitForAuthorization(
        httpClient: HttpClient,
        device: AmpDeviceCode,
    ): OAuthTokenBundle {
        val base = AmpAuthParser.cleanServerUrl(device.serverUrl) ?: AmpAuthParser.DEFAULT_API_SERVER_URL
        var intervalSeconds = device.intervalSeconds.coerceAtLeast(1)
        val deadlineMillis = Clock.System.now().toEpochMilliseconds() +
            device.expiresIn.coerceIn(MIN_EXPIRES_IN_SECONDS, MAX_POLL_SECONDS) * 1_000L
        var firstAttempt = true
        while (true) {
            if (!firstAttempt) {
                delay(intervalSeconds * 1_000L)
            }
            firstAttempt = false
            if (Clock.System.now().toEpochMilliseconds() > deadlineMillis) {
                throw ProviderException(AuthState.Error, "Amp sign-in timed out. Please try again.")
            }
            val (statusCode, body) = postDeviceToken(
                httpClient = httpClient,
                tokenUrl = "$base$DEVICE_TOKEN_PATH",
                parameters = Parameters.build {
                    append("grant_type", DEVICE_GRANT_TYPE)
                    append("device_code", device.deviceCode)
                },
            )
            when (val result = interpretTokenPayload(statusCode, body, intervalSeconds, base)) {
                is AmpTokenPollResult.Success -> return result.token
                is AmpTokenPollResult.Pending -> intervalSeconds = result.nextIntervalSeconds
                is AmpTokenPollResult.Failed -> throw result.error
            }
        }
    }

    internal fun parseDeviceCodeResponse(
        statusCode: Int,
        body: String,
        serverUrl: String = AmpAuthParser.DEFAULT_API_SERVER_URL,
    ): AmpDeviceCode {
        if (statusCode !in 200..299) {
            throw ProviderException(
                AuthState.Error,
                "Amp device-code request failed: HTTP $statusCode: ${body.trim().take(200)}",
                statusCode,
            )
        }
        val decoded = runCatching {
            json.decodeFromString(AmpDeviceCodeResponse.serializer(), body)
        }.getOrElse {
            throw ProviderException(AuthState.Error, "Amp device-code response was not valid JSON.")
        }
        val deviceCode = decoded.deviceCode?.trim().orEmpty()
        val userCode = decoded.userCode?.trim().orEmpty()
        val verificationUri = decoded.verificationUri?.trim().orEmpty()
        val verificationUriComplete = decoded.verificationUriComplete?.trim().orEmpty()
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            throw ProviderException(AuthState.Error, "Amp device-code response is missing device or user code.")
        }
        if (verificationUri.isEmpty() && verificationUriComplete.isEmpty()) {
            throw ProviderException(AuthState.Error, "Amp device-code response is missing a verification URL.")
        }
        val safeVerificationUri = if (verificationUri.isNotEmpty()) {
            validateVerificationUri(verificationUri, serverUrl)
        } else {
            validateVerificationUri(verificationUriComplete, serverUrl)
        }
        val safeVerificationUriComplete = if (verificationUriComplete.isNotEmpty()) {
            validateVerificationUri(verificationUriComplete, serverUrl)
        } else {
            safeVerificationUri
        }
        return AmpDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = safeVerificationUri,
            verificationUriComplete = safeVerificationUriComplete,
            expiresIn = decoded.expiresIn?.takeIf { it > 0 } ?: (5 * 60),
            intervalSeconds = decoded.interval?.takeIf { it > 0 } ?: DEFAULT_POLL_INTERVAL_SECONDS,
            serverUrl = serverUrl,
        )
    }

    internal fun interpretTokenPayload(
        statusCode: Int,
        body: String,
        intervalSeconds: Int,
        serverUrl: String = AmpAuthParser.DEFAULT_API_SERVER_URL,
    ): AmpTokenPollResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.stringField("error").orEmpty()
        when (error) {
            "authorization_pending" -> return AmpTokenPollResult.Pending(intervalSeconds)
            "slow_down" -> return AmpTokenPollResult.Pending(intervalSeconds + DEFAULT_POLL_INTERVAL_SECONDS)
            "expired_token" -> return AmpTokenPollResult.Failed(
                ProviderException(AuthState.Error, "Amp sign-in timed out. Please try again.")
            )
            "access_denied" -> return AmpTokenPollResult.Failed(
                ProviderException(AuthState.Unauthorized, "Amp authorization was denied.")
            )
            "invalid_grant" -> return AmpTokenPollResult.Failed(
                ProviderException(AuthState.RequiresRelogin, "Amp credentials expired. Sign in again.")
            )
        }
        val accessToken = root?.stringField("access_token")
            ?: root?.stringField("api_key")
            ?: root?.stringField("apiKey")
        if (root != null && !accessToken.isNullOrBlank()) {
            val expiresInSeconds = (root["expires_in"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            val expiresAt = if (expiresInSeconds != null) {
                Clock.System.now().toEpochMilliseconds() + expiresInSeconds * 1_000L
            } else {
                Clock.System.now().toEpochMilliseconds() + NO_EXPIRY_MILLIS
            }
            val customServer = AmpAuthParser.cleanServerUrl(serverUrl)
                ?.takeUnless { it.equals(AmpAuthParser.DEFAULT_API_SERVER_URL, ignoreCase = true) }
            return AmpTokenPollResult.Success(
                OAuthTokenBundle(
                    accessToken = accessToken,
                    refreshToken = root.stringField("refresh_token") ?: "",
                    email = root.stringField("email")
                        ?: (root["user"] as? JsonObject)?.stringField("email"),
                    apiServerUrl = customServer,
                    expiresAtEpochMillis = expiresAt,
                )
            )
        }
        if (statusCode == 401 || statusCode == 403) {
            return AmpTokenPollResult.Failed(
                ProviderException(
                    AuthState.RequiresRelogin,
                    "Amp credentials were rejected. Sign in again.",
                    statusCode,
                )
            )
        }
        val preview = root?.stringField("error_description")
            ?: error.ifEmpty { null }
            ?: body.trim().take(200).ifEmpty { "HTTP $statusCode" }
        return AmpTokenPollResult.Failed(
            ProviderException(AuthState.Error, "Amp token request failed: $preview", statusCode)
        )
    }

    internal fun validateVerificationUri(
        rawUrl: String?,
        serverUrl: String = AmpAuthParser.DEFAULT_API_SERVER_URL,
    ): String {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) {
            throw ProviderException(AuthState.Error, "Amp verification URL is empty.")
        }
        val parsed = runCatching { Url(value) }.getOrElse {
            throw ProviderException(AuthState.Error, "Amp verification URL is not valid.")
        }
        if (!parsed.protocol.name.equals("https", ignoreCase = true)) {
            throw ProviderException(AuthState.Error, "Amp verification URL must use HTTPS.")
        }
        val host = parsed.host.lowercase()
        val expectedHost = runCatching { Url(serverUrl).host.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }
        val allowed = host == "ampcode.com" || host.endsWith(".ampcode.com") ||
            (expectedHost != null && (host == expectedHost || host.endsWith(".$expectedHost")))
        if (!allowed) {
            throw ProviderException(AuthState.Error, "Amp verification URL host is not trusted.")
        }
        return value
    }

    private suspend fun postDeviceToken(
        httpClient: HttpClient,
        tokenUrl: String,
        parameters: Parameters,
    ): Pair<Int, String> {
        val response = httpClient.post(tokenUrl) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            setBody(FormDataContent(parameters))
        }
        return response.status.value to response.bodyAsText()
    }

    private fun JsonObject.stringField(name: String): String? {
        return (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    @Serializable
    private data class AmpDeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String? = null,
        @SerialName("user_code") val userCode: String? = null,
        @SerialName("verification_uri") val verificationUri: String? = null,
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        val interval: Int? = null,
    )
}
