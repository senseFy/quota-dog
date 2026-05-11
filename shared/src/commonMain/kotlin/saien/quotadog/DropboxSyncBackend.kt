package saien.quotadog

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class DropboxRemoteFile(
    val content: String,
    val rev: String?
)

interface CloudSyncRemoteBackend {
    fun hasConnection(): Boolean
    fun storedRev(): String?
    suspend fun connect(): String
    suspend fun pull(): DropboxRemoteFile?
    suspend fun push(content: String, rev: String?): DropboxRemoteFile
    fun disconnect()
}

class DropboxSyncConflictException : Exception("Dropbox sync file changed remotely")

class DropboxSyncBackend(
    private val settings: Settings = createPreferenceSettings(),
    private val browserLauncher: BrowserLauncher = PlatformBrowserLauncher(),
    private val callbackServer: OAuthCallbackServer = PlatformOAuthCallbackServer(),
    private val httpClient: HttpClient = createHttpClient()
) : CloudSyncRemoteBackend {
    private val json = Json { ignoreUnknownKeys = true }

    override fun hasConnection(): Boolean = loadToken() != null

    override fun storedRev(): String? = settings.getStringOrNull(KEY_REV)

    override suspend fun connect(): String {
        val verifier = base64UrlNoPadding(secureRandomBytes(32))
        val challenge = base64UrlNoPadding(sha256(verifier.encodeToByteArray()))
        val state = base64UrlNoPadding(secureRandomBytes(24))
        val authorizationUrl = URLBuilder(DROPBOX_AUTHORIZE_URL).apply {
            parameters.append("client_id", DROPBOX_APP_KEY)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", DROPBOX_REDIRECT_URI)
            parameters.append("token_access_type", "offline")
            parameters.append("scope", DROPBOX_SCOPE)
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

        val callbackUri = coroutineScope {
            val callback = async { callbackServer.waitForCallback(DROPBOX_CALLBACK_PORT, DROPBOX_CALLBACK_PATH) }
            delay(250)
            val opened = browserLauncher.open(authorizationUrl)
            if (!opened) {
                throw ProviderException(AuthState.Error, "Could not open the Dropbox authorization page")
            }
            callback.await()
        } ?: throw ProviderException(AuthState.Error, "No Dropbox authorization callback received")

        val callback = Url(callbackUri)
        if (callback.parameters["state"] != state) {
            throw ProviderException(AuthState.Error, "Dropbox OAuth state mismatch, please connect again")
        }
        callback.parameters["error"]?.let {
            throw ProviderException(AuthState.Unauthorized, "Dropbox authorization was cancelled or denied")
        }
        val code = callback.parameters["code"]
            ?: throw ProviderException(AuthState.Error, "Dropbox callback is missing authorization code")
        val token = exchangeCode(code, verifier)
        saveToken(token)
        return token.accountId
    }

    override suspend fun pull(): DropboxRemoteFile? {
        val token = ensureAccessToken()
        val response = httpClient.post(DROPBOX_DOWNLOAD_URL) {
            header("Authorization", "Bearer ${token.accessToken}")
            header("Dropbox-API-Arg", """{"path":"$SYNC_FILE_PATH"}""")
            setBody(ByteArray(0))
        }
        if (response.status == HttpStatusCode.Conflict || response.status == HttpStatusCode.NotFound) {
            return null
        }
        val text = response.expectDropboxSuccess("Dropbox sync download")
        val rev = response.dropboxRev()
        rev?.let { settings.putString(KEY_REV, it) }
        return DropboxRemoteFile(text, rev)
    }

    override suspend fun push(content: String, rev: String?): DropboxRemoteFile {
        val token = ensureAccessToken()
        val mode = if (rev == null) {
            """"mode":{".tag":"add"}"""
        } else {
            """"mode":{".tag":"update","update":"$rev"}"""
        }
        val arg = """{"path":"$SYNC_FILE_PATH",$mode,"autorename":false,"mute":true,"strict_conflict":true}"""
        val response = httpClient.post(DROPBOX_UPLOAD_URL) {
            header("Authorization", "Bearer ${token.accessToken}")
            header("Dropbox-API-Arg", arg)
            contentType(ContentType.Application.OctetStream)
            setBody(content.encodeToByteArray())
        }
        if (response.status == HttpStatusCode.Conflict) {
            throw DropboxSyncConflictException()
        }
        val responseText = response.expectDropboxSuccess("Dropbox sync upload")
        val uploaded = json.decodeFromString(DropboxFileMetadata.serializer(), responseText)
        uploaded.rev?.let { settings.putString(KEY_REV, it) }
        return DropboxRemoteFile(content, uploaded.rev)
    }

    override fun disconnect() {
        settings.remove(KEY_TOKEN)
        settings.remove(KEY_REV)
    }

    private suspend fun ensureAccessToken(): DropboxTokenBundle {
        val token = loadToken()
            ?: throw ProviderException(AuthState.NotConfigured, "Dropbox sync is not connected")
        if (!token.isExpired()) return token
        val refreshed = refreshToken(token.refreshToken, token.accountId)
        saveToken(refreshed)
        return refreshed
    }

    private suspend fun exchangeCode(code: String, verifier: String): DropboxTokenBundle {
        val responseText = httpClient.post(DROPBOX_TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", DROPBOX_APP_KEY)
                append("code", code)
                append("redirect_uri", DROPBOX_REDIRECT_URI)
                append("code_verifier", verifier)
            }))
        }.expectDropboxSuccess("Dropbox token exchange")
        return json.decodeFromString(DropboxTokenResponse.serializer(), responseText).toBundle()
    }

    private suspend fun refreshToken(refreshToken: String, accountId: String): DropboxTokenBundle {
        val responseText = httpClient.post(DROPBOX_TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", DROPBOX_APP_KEY)
                append("refresh_token", refreshToken)
            }))
        }.expectDropboxSuccess("Dropbox token refresh")
        return json.decodeFromString(DropboxTokenResponse.serializer(), responseText)
            .toBundle(refreshTokenOverride = refreshToken, accountIdOverride = accountId)
    }

    private fun loadToken(): DropboxTokenBundle? {
        if (!settings.hasKey(KEY_TOKEN)) return null
        return runCatching {
            json.decodeFromString(DropboxTokenBundle.serializer(), settings.getString(KEY_TOKEN, ""))
        }.getOrNull()
    }

    private fun saveToken(token: DropboxTokenBundle) {
        settings.putString(KEY_TOKEN, json.encodeToString(DropboxTokenBundle.serializer(), token))
    }

    private suspend fun HttpResponse.expectDropboxSuccess(label: String): String {
        val text = bodyAsText()
        if (status == HttpStatusCode.TooManyRequests) {
            throw ProviderException(AuthState.RateLimited, "$label was rate limited", status.value)
        }
        if (status == HttpStatusCode.Unauthorized) {
            throw ProviderException(AuthState.RequiresRelogin, "$label is unauthorized, please connect Dropbox again", status.value)
        }
        if (!status.isSuccess()) {
            throw ProviderException(AuthState.Error, "$label failed: HTTP ${status.value}", status.value)
        }
        return text
    }

    private fun HttpResponse.dropboxRev(): String? {
        val metadata = headers["Dropbox-API-Result"] ?: return null
        return runCatching {
            json.decodeFromString(DropboxFileMetadata.serializer(), metadata).rev
        }.getOrNull()
    }

    private companion object {
        const val DROPBOX_AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize"
        const val DROPBOX_TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
        const val DROPBOX_DOWNLOAD_URL = "https://content.dropboxapi.com/2/files/download"
        const val DROPBOX_UPLOAD_URL = "https://content.dropboxapi.com/2/files/upload"
        const val DROPBOX_APP_KEY = "j4ey0j3ds6pff0c"
        const val DROPBOX_CALLBACK_PORT = 17553
        const val DROPBOX_CALLBACK_PATH = "/dropbox/callback"
        const val DROPBOX_REDIRECT_URI = "http://localhost:$DROPBOX_CALLBACK_PORT$DROPBOX_CALLBACK_PATH"
        const val DROPBOX_SCOPE = "files.metadata.read files.content.read files.content.write"
        const val SYNC_FILE_PATH = "/sync-v1.json"
        const val KEY_TOKEN = "cloud_sync_dropbox_token_v1"
        const val KEY_REV = "cloud_sync_dropbox_rev_v1"
    }
}

@Serializable
private data class DropboxTokenBundle(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val expiresAtEpochMillis: Long
) {
    fun isExpired(bufferMillis: Long = 60_000): Boolean {
        return Clock.System.now().toEpochMilliseconds() + bufferMillis >= expiresAtEpochMillis
    }
}

@Serializable
private data class DropboxTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 14_400,
    @SerialName("account_id") val accountId: String? = null
) {
    fun toBundle(refreshTokenOverride: String? = null, accountIdOverride: String? = null): DropboxTokenBundle {
        return DropboxTokenBundle(
            accessToken = accessToken,
            refreshToken = refreshToken ?: refreshTokenOverride.orEmpty(),
            accountId = accountId ?: accountIdOverride.orEmpty(),
            expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() + expiresIn * 1_000L
        )
    }
}

@Serializable
private data class DropboxFileMetadata(
    val rev: String? = null
)
