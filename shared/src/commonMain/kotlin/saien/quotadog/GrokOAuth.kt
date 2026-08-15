package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class GrokOAuthDiscovery(
    val deviceAuthorizationEndpoint: String,
    val tokenEndpoint: String,
)

internal data class GrokDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Int,
    val intervalSeconds: Int,
    val tokenEndpoint: String,
) {
    val authorizationUrl: String
        get() = verificationUriComplete.ifBlank { verificationUri }
}

internal sealed class GrokTokenPollResult {
    data class Success(val token: OAuthTokenBundle) : GrokTokenPollResult()
    data class Pending(val nextIntervalSeconds: Int) : GrokTokenPollResult()
    data class Failed(val error: ProviderException) : GrokTokenPollResult()
}

internal object GrokOAuth {
    const val ISSUER = "https://auth.x.ai"
    const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    const val FALLBACK_DEVICE_ENDPOINT = "$ISSUER/oauth2/device/code"
    const val FALLBACK_TOKEN_ENDPOINT = "$ISSUER/oauth2/token"
    const val DEFAULT_POLL_INTERVAL_SECONDS = 5
    const val MIN_EXPIRES_IN_SECONDS = 60
    const val MAX_POLL_SECONDS = 30 * 60
    const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 6L * 60L * 60L
    const val REFERRER = "grok-build"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestDeviceCode(httpClient: HttpClient): GrokDeviceCode {
        val discovery = runCatching { discover(httpClient) }.getOrDefault(
            GrokOAuthDiscovery(
                deviceAuthorizationEndpoint = FALLBACK_DEVICE_ENDPOINT,
                tokenEndpoint = FALLBACK_TOKEN_ENDPOINT,
            )
        )
        val response = httpClient.post(discovery.deviceAuthorizationEndpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("client_id", CLIENT_ID)
                        append("scope", SCOPE)
                        append("referrer", REFERRER)
                    }
                )
            )
        }
        return parseDeviceCodeResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            tokenEndpoint = discovery.tokenEndpoint,
        )
    }

    suspend fun waitForAuthorization(httpClient: HttpClient, device: GrokDeviceCode): OAuthTokenBundle {
        var intervalSeconds = device.intervalSeconds.coerceAtLeast(DEFAULT_POLL_INTERVAL_SECONDS)
        val deadlineMillis = Clock.System.now().toEpochMilliseconds() +
            device.expiresIn.coerceIn(MIN_EXPIRES_IN_SECONDS, MAX_POLL_SECONDS) * 1_000L
        var firstAttempt = true
        while (true) {
            if (!firstAttempt) {
                delay(intervalSeconds * 1_000L)
            }
            firstAttempt = false
            if (Clock.System.now().toEpochMilliseconds() > deadlineMillis) {
                throw ProviderException(AuthState.Error, "xAI sign-in timed out. Please try again.")
            }
            val (statusCode, body) = postTokenForm(
                httpClient = httpClient,
                tokenEndpoint = device.tokenEndpoint,
                parameters = Parameters.build {
                    append("grant_type", DEVICE_GRANT_TYPE)
                    append("device_code", device.deviceCode)
                    append("client_id", CLIENT_ID)
                },
            )
            when (val result = interpretTokenPayload(statusCode, body, intervalSeconds)) {
                is GrokTokenPollResult.Success -> return result.token
                is GrokTokenPollResult.Pending -> intervalSeconds = result.nextIntervalSeconds
                is GrokTokenPollResult.Failed -> throw result.error
            }
        }
    }

    suspend fun refresh(
        httpClient: HttpClient,
        refreshToken: String,
        tokenEndpoint: String = FALLBACK_TOKEN_ENDPOINT,
    ): OAuthTokenBundle {
        if (refreshToken.isBlank()) {
            throw ProviderException(AuthState.RequiresRelogin, "Grok credentials expired. Sign in with xAI again.")
        }
        val (statusCode, body) = postTokenForm(
            httpClient = httpClient,
            tokenEndpoint = tokenEndpoint.ifBlank { FALLBACK_TOKEN_ENDPOINT },
            parameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", CLIENT_ID)
            },
        )
        return when (val result = interpretTokenPayload(statusCode, body, DEFAULT_POLL_INTERVAL_SECONDS)) {
            is GrokTokenPollResult.Success -> result.token
            is GrokTokenPollResult.Pending -> throw ProviderException(
                AuthState.Error,
                "Grok token refresh returned a pending device-code response.",
            )
            is GrokTokenPollResult.Failed -> throw result.error
        }
    }

    internal suspend fun discover(httpClient: HttpClient): GrokOAuthDiscovery {
        val response = httpClient.get(DISCOVERY_URL) {
            header("Accept", "application/json")
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw ProviderException(AuthState.Error, "xAI OAuth discovery failed: HTTP ${response.status.value}")
        }
        return parseDiscoveryJson(body)
    }

    internal fun parseDiscoveryJson(body: String): GrokOAuthDiscovery {
        val root = json.parseToJsonElement(body).jsonObject
        val deviceEndpoint = validateOAuthEndpoint(
            root["device_authorization_endpoint"]?.jsonPrimitive?.contentOrNull,
            "device_authorization_endpoint",
        )
        val tokenEndpoint = validateOAuthEndpoint(
            root["token_endpoint"]?.jsonPrimitive?.contentOrNull,
            "token_endpoint",
        )
        return GrokOAuthDiscovery(
            deviceAuthorizationEndpoint = deviceEndpoint,
            tokenEndpoint = tokenEndpoint,
        )
    }

    internal fun parseDeviceCodeResponse(
        statusCode: Int,
        body: String,
        tokenEndpoint: String,
    ): GrokDeviceCode {
        if (statusCode !in 200..299) {
            throw ProviderException(
                AuthState.Error,
                "xAI device-code request failed: HTTP $statusCode: ${body.trim().take(200)}",
                statusCode,
            )
        }
        val decoded = runCatching {
            json.decodeFromString(GrokDeviceCodeResponse.serializer(), body)
        }.getOrElse {
            throw ProviderException(AuthState.Error, "xAI device-code response was not valid JSON.")
        }
        val deviceCode = decoded.deviceCode?.trim().orEmpty()
        val userCode = decoded.userCode?.trim().orEmpty()
        val verificationUri = decoded.verificationUri?.trim().orEmpty()
        val verificationUriComplete = decoded.verificationUriComplete?.trim().orEmpty()
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            throw ProviderException(AuthState.Error, "xAI device-code response is missing device or user code.")
        }
        if (verificationUri.isEmpty() && verificationUriComplete.isEmpty()) {
            throw ProviderException(AuthState.Error, "xAI device-code response is missing a verification URL.")
        }
        val safeVerificationUri = if (verificationUri.isNotEmpty()) {
            validateOAuthEndpoint(verificationUri, "verification_uri")
        } else {
            validateOAuthEndpoint(verificationUriComplete, "verification_uri_complete")
        }
        val safeVerificationUriComplete = if (verificationUriComplete.isNotEmpty()) {
            validateOAuthEndpoint(verificationUriComplete, "verification_uri_complete")
        } else {
            safeVerificationUri
        }
        return GrokDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = safeVerificationUri,
            verificationUriComplete = safeVerificationUriComplete,
            expiresIn = decoded.expiresIn?.takeIf { it > 0 } ?: (15 * 60),
            intervalSeconds = decoded.interval?.takeIf { it > 0 } ?: DEFAULT_POLL_INTERVAL_SECONDS,
            tokenEndpoint = tokenEndpoint.ifBlank { FALLBACK_TOKEN_ENDPOINT },
        )
    }

    internal fun interpretTokenPayload(
        statusCode: Int,
        body: String,
        intervalSeconds: Int,
    ): GrokTokenPollResult {
        val payload = runCatching {
            json.decodeFromString(GrokTokenResponse.serializer(), body)
        }.getOrNull()
        val error = payload?.error?.trim().orEmpty()
        when (error) {
            "authorization_pending" -> return GrokTokenPollResult.Pending(intervalSeconds)
            "slow_down" -> return GrokTokenPollResult.Pending(intervalSeconds + DEFAULT_POLL_INTERVAL_SECONDS)
            "expired_token" -> return GrokTokenPollResult.Failed(
                ProviderException(AuthState.Error, "xAI sign-in timed out. Please try again.")
            )
            "access_denied" -> return GrokTokenPollResult.Failed(
                ProviderException(AuthState.Unauthorized, "xAI authorization was denied.")
            )
            "invalid_grant" -> return GrokTokenPollResult.Failed(
                ProviderException(AuthState.RequiresRelogin, "Grok credentials expired. Sign in with xAI again.")
            )
        }
        val accessToken = payload?.accessToken?.trim().orEmpty()
        if (payload != null && accessToken.isNotEmpty()) {
            val identity = parseJwtIdentity(payload.idToken)
            val expiresIn = payload.expiresIn?.takeIf { it > 0 } ?: DEFAULT_ACCESS_TOKEN_TTL_SECONDS.toInt()
            return GrokTokenPollResult.Success(
                OAuthTokenBundle(
                    accessToken = accessToken,
                    refreshToken = payload.refreshToken?.trim().orEmpty(),
                    idToken = payload.idToken?.trim()?.takeIf { it.isNotEmpty() },
                    accountId = identity.subject,
                    email = identity.email,
                    expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() + expiresIn * 1_000L,
                )
            )
        }
        if (statusCode == 401 || statusCode == 403) {
            return GrokTokenPollResult.Failed(
                ProviderException(
                    AuthState.RequiresRelogin,
                    "Grok credentials were rejected. Sign in with xAI again.",
                    statusCode,
                )
            )
        }
        val preview = payload?.errorDescription?.trim()?.ifEmpty { null }
            ?: payload?.error?.trim()?.ifEmpty { null }
            ?: body.trim().take(200).ifEmpty { "HTTP $statusCode" }
        return GrokTokenPollResult.Failed(
            ProviderException(AuthState.Error, "xAI token request failed: $preview", statusCode)
        )
    }

    internal fun validateOAuthEndpoint(rawUrl: String?, field: String): String {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) {
            throw ProviderException(AuthState.Error, "xAI OAuth $field is empty.")
        }
        val parsed = runCatching { Url(value) }.getOrElse {
            throw ProviderException(AuthState.Error, "xAI OAuth $field is not a valid URL.")
        }
        if (!parsed.protocol.name.equals("https", ignoreCase = true)) {
            throw ProviderException(AuthState.Error, "xAI OAuth $field must use HTTPS.")
        }
        val host = parsed.host.lowercase()
        if (host != "x.ai" && !host.endsWith(".x.ai")) {
            throw ProviderException(AuthState.Error, "xAI OAuth $field host is not on x.ai.")
        }
        return value
    }

    internal fun parseJwtIdentity(token: String?): JwtIdentity {
        val payload = token?.split(".")?.getOrNull(1) ?: return JwtIdentity()
        val decoded = runCatching { decodeBase64Url(payload).decodeToString() }.getOrNull() ?: return JwtIdentity()
        val claims = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return JwtIdentity()
        return JwtIdentity(
            email = claims["email"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            subject = claims["sub"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun postTokenForm(
        httpClient: HttpClient,
        tokenEndpoint: String,
        parameters: Parameters,
    ): Pair<Int, String> {
        val response = httpClient.post(tokenEndpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            setBody(FormDataContent(parameters))
        }
        return response.status.value to response.bodyAsText()
    }

    internal data class JwtIdentity(
        val email: String? = null,
        val subject: String? = null,
    )

    @Serializable
    private data class GrokDeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String? = null,
        @SerialName("user_code") val userCode: String? = null,
        @SerialName("verification_uri") val verificationUri: String? = null,
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        val interval: Int? = null,
    )

    @Serializable
    private data class GrokTokenResponse(
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
    )
}

private fun decodeBase64Url(input: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val clean = input.trimEnd('=')
    val output = mutableListOf<Byte>()
    var buffer = 0
    var bits = 0
    for (char in clean) {
        val value = alphabet.indexOf(char)
        if (value < 0) continue
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output.add(((buffer shr bits) and 0xff).toByte())
        }
    }
    return output.toByteArray()
}
