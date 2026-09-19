package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DroidDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Int,
    val intervalSeconds: Int,
) {
    val authorizationUrl: String
        get() = verificationUriComplete.ifBlank { verificationUri }
}

internal sealed class DroidTokenPollResult {
    data class Success(val token: OAuthTokenBundle) : DroidTokenPollResult()
    data class Pending(val nextIntervalSeconds: Int) : DroidTokenPollResult()
    data class Failed(val error: ProviderException) : DroidTokenPollResult()
}

/**
 * WorkOS auth for Factory Droid, matching the droid CLI's own flows:
 *
 * - Device code: `POST .../authorize/device` then poll `.../authenticate`
 *   with `grant_type=urn:ietf:params:oauth:grant-type:device_code`.
 * - Session refresh: `POST .../authenticate` with `grant_type=refresh_token`.
 *
 * Both run against the droid public client.
 */
internal object DroidOAuth {
    const val CLIENT_ID = "client_01HNM792M5G5G1A2THWPXKFMXB"
    private const val AUTHENTICATE_URL = "https://api.workos.com/user_management/authenticate"
    private const val DEVICE_AUTHORIZE_URL = "https://api.workos.com/user_management/authorize/device"
    private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    private const val DEFAULT_POLL_INTERVAL_SECONDS = 5
    private const val MIN_EXPIRES_IN_SECONDS = 60
    private const val MAX_POLL_SECONDS = 30 * 60
    private const val FALLBACK_EXPIRY_MILLIS = 7L * 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestDeviceCode(httpClient: HttpClient): DroidDeviceCode {
        val response = httpClient.post(DEVICE_AUTHORIZE_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            setBody(FormDataContent(Parameters.build { append("client_id", CLIENT_ID) }))
        }
        return parseDeviceCodeResponse(response.status.value, response.bodyAsText())
    }

    suspend fun waitForAuthorization(httpClient: HttpClient, device: DroidDeviceCode): OAuthTokenBundle {
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
                throw ProviderException(AuthState.Error, "Droid sign-in timed out. Please try again.")
            }
            val (statusCode, body) = postAuthenticate(
                httpClient,
                Parameters.build {
                    append("grant_type", DEVICE_GRANT_TYPE)
                    append("device_code", device.deviceCode)
                    append("client_id", CLIENT_ID)
                },
            )
            when (val result = interpretTokenPayload(statusCode, body, intervalSeconds)) {
                is DroidTokenPollResult.Success -> return result.token
                is DroidTokenPollResult.Pending -> intervalSeconds = result.nextIntervalSeconds
                is DroidTokenPollResult.Failed -> throw result.error
            }
        }
    }

    suspend fun refresh(httpClient: HttpClient, refreshToken: String): OAuthTokenBundle {
        val response = httpClient.post(AUTHENTICATE_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", CLIENT_ID)
            }))
        }
        val statusCode = response.status.value
        val body = response.bodyAsText()
        if (statusCode == 401 || statusCode == 403 ||
            (statusCode == HttpStatusCode.BadRequest.value && body.contains("invalid_grant"))
        ) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Droid session expired. Run `droid`, sign in, then re-import.",
                statusCode,
            )
        }
        if (!response.status.isSuccess()) {
            val preview = body.take(160).ifBlank { "(empty body)" }
            throw ProviderException(
                AuthState.Error,
                "Droid token refresh failed: HTTP $statusCode: $preview",
                statusCode,
            )
        }
        return parseTokenResponse(body)
    }

    internal fun parseDeviceCodeResponse(statusCode: Int, body: String): DroidDeviceCode {
        if (statusCode !in 200..299) {
            throw ProviderException(
                AuthState.Error,
                "Factory device-code request failed: HTTP $statusCode: ${body.trim().take(200)}",
                statusCode,
            )
        }
        val decoded = runCatching {
            json.decodeFromString(DroidDeviceCodeResponse.serializer(), body)
        }.getOrElse {
            throw ProviderException(AuthState.Error, "Factory device-code response was not valid JSON.")
        }
        val deviceCode = decoded.deviceCode?.trim().orEmpty()
        val userCode = decoded.userCode?.trim().orEmpty()
        val verificationUri = decoded.verificationUri?.trim().orEmpty()
        val verificationUriComplete = decoded.verificationUriComplete?.trim().orEmpty()
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            throw ProviderException(AuthState.Error, "Factory device-code response is missing device or user code.")
        }
        if (verificationUri.isEmpty() && verificationUriComplete.isEmpty()) {
            throw ProviderException(AuthState.Error, "Factory device-code response is missing a verification URL.")
        }
        val safeVerificationUri = if (verificationUri.isNotEmpty()) {
            validateVerificationUri(verificationUri)
        } else {
            validateVerificationUri(verificationUriComplete)
        }
        val safeVerificationUriComplete = if (verificationUriComplete.isNotEmpty()) {
            validateVerificationUri(verificationUriComplete)
        } else {
            safeVerificationUri
        }
        return DroidDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = safeVerificationUri,
            verificationUriComplete = safeVerificationUriComplete,
            expiresIn = decoded.expiresIn?.takeIf { it > 0 } ?: (5 * 60),
            intervalSeconds = decoded.interval?.takeIf { it > 0 } ?: DEFAULT_POLL_INTERVAL_SECONDS,
        )
    }

    internal fun interpretTokenPayload(
        statusCode: Int,
        body: String,
        intervalSeconds: Int,
    ): DroidTokenPollResult {
        val payload = runCatching {
            json.decodeFromString(DroidTokenResponse.serializer(), body)
        }.getOrNull()
        when (payload?.error?.trim().orEmpty()) {
            "authorization_pending" -> return DroidTokenPollResult.Pending(intervalSeconds)
            "slow_down" -> return DroidTokenPollResult.Pending(intervalSeconds + 1)
            "expired_token" -> return DroidTokenPollResult.Failed(
                ProviderException(AuthState.Error, "Droid sign-in timed out. Please try again.")
            )
            "access_denied" -> return DroidTokenPollResult.Failed(
                ProviderException(AuthState.Unauthorized, "Droid authorization was denied.")
            )
            "invalid_grant" -> return DroidTokenPollResult.Failed(
                ProviderException(AuthState.RequiresRelogin, "Droid credentials expired. Sign in again.")
            )
        }
        if (payload != null && !payload.accessToken.isNullOrBlank()) {
            val token = runCatching { parseTokenResponse(body) }.getOrNull()
                ?: return DroidTokenPollResult.Failed(
                    ProviderException(AuthState.Error, "Droid token response was not valid JSON.")
                )
            return DroidTokenPollResult.Success(token)
        }
        if (statusCode == 401 || statusCode == 403) {
            return DroidTokenPollResult.Failed(
                ProviderException(
                    AuthState.RequiresRelogin,
                    "Droid credentials were rejected. Sign in again.",
                    statusCode,
                )
            )
        }
        val preview = payload?.errorDescription?.trim()?.ifEmpty { null }
            ?: payload?.error?.trim()?.ifEmpty { null }
            ?: body.trim().take(200).ifEmpty { "HTTP $statusCode" }
        return DroidTokenPollResult.Failed(
            ProviderException(AuthState.Error, "Factory token request failed: $preview", statusCode)
        )
    }

    private suspend fun postAuthenticate(
        httpClient: HttpClient,
        parameters: Parameters,
    ): Pair<Int, String> {
        val response = httpClient.post(AUTHENTICATE_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            setBody(FormDataContent(parameters))
        }
        return response.status.value to response.bodyAsText()
    }

    internal fun validateVerificationUri(rawUrl: String?): String {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) {
            throw ProviderException(AuthState.Error, "Factory verification URL is empty.")
        }
        val parsed = runCatching { Url(value) }.getOrElse {
            throw ProviderException(AuthState.Error, "Factory verification URL is not valid.")
        }
        if (!parsed.protocol.name.equals("https", ignoreCase = true)) {
            throw ProviderException(AuthState.Error, "Factory verification URL must use HTTPS.")
        }
        val host = parsed.host.lowercase()
        val allowed = host == "factory.ai" || host.endsWith(".factory.ai") ||
            host == "workos.com" || host.endsWith(".workos.com")
        if (!allowed) {
            throw ProviderException(AuthState.Error, "Factory verification URL host is not trusted.")
        }
        return value
    }

    internal fun parseTokenResponse(text: String): OAuthTokenBundle {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw ProviderException(AuthState.Error, "Droid token response was not valid JSON.")
        }
        val accessToken = root.stringField("access_token")
            ?: throw ProviderException(AuthState.Error, "Droid token response had no access_token.")
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = root.stringField("refresh_token") ?: "",
            accountId = root.stringField("organization_id"),
            email = (root["user"] as? JsonObject)?.stringField("email")
                ?: DroidAuthParser.jwtStringClaim(accessToken, "email"),
            expiresAtEpochMillis = DroidAuthParser.jwtExpiryMillis(accessToken)
                ?: kotlinx.datetime.Clock.System.now().toEpochMilliseconds() + FALLBACK_EXPIRY_MILLIS,
        )
    }

    private fun JsonObject.stringField(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    @Serializable
    private data class DroidDeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String? = null,
        @SerialName("user_code") val userCode: String? = null,
        @SerialName("verification_uri") val verificationUri: String? = null,
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        val interval: Int? = null,
    )

    @Serializable
    private data class DroidTokenResponse(
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
        @SerialName("access_token") val accessToken: String? = null,
    )
}
