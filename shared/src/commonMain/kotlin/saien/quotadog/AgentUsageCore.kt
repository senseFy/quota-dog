package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ProviderId(val displayName: String) {
    CODEX("Codex"),
    CLAUDE_CODE("Claude Code")
}

enum class AuthState {
    Unknown,
    NotConfigured,
    LoggedIn,
    TokenExpired,
    Unauthorized,
    RateLimited,
    RequiresRelogin,
    Error
}

enum class UsageSource {
    ServerAuthoritative,
    Cached
}

@Serializable
data class AccountKey(
    val providerId: ProviderId,
    val accountId: String
) {
    val isPending: Boolean get() = accountId.startsWith(PENDING_PREFIX)

    companion object {
        private const val PENDING_PREFIX = "pending:"

        fun pending(providerId: ProviderId, state: String): AccountKey {
            return AccountKey(providerId, "$PENDING_PREFIX$state")
        }
    }
}

@Serializable
data class OAuthTokenBundle(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String? = null,
    val accountId: String? = null,
    val email: String? = null,
    val expiresAtEpochMillis: Long,
    val lastRefreshEpochMillis: Long = Clock.System.now().toEpochMilliseconds()
) {
    fun isExpired(bufferMillis: Long = 60_000): Boolean {
        return Clock.System.now().toEpochMilliseconds() + bufferMillis >= expiresAtEpochMillis
    }
}

@Serializable
data class UsageWindow(
    val id: String,
    val label: String,
    val usedRatio: Double,
    val resetsAt: Instant?,
    val source: UsageSource = UsageSource.ServerAuthoritative,
    val durationSeconds: Long? = null
) {
    val remainingRatio: Double = (1.0 - usedRatio).coerceIn(0.0, 1.0)
}

@Serializable
data class ProviderUsageSnapshot(
    val providerId: ProviderId,
    val authState: AuthState,
    val windows: List<UsageWindow>,
    val collectedAt: Instant,
    val stale: Boolean = false,
    val accountEmail: String? = null,
    val message: String? = null
)

data class StoredToken(
    val accountKey: AccountKey,
    val token: OAuthTokenBundle
)

data class OAuthLoginStart(
    val providerId: ProviderId,
    val authorizationUrl: String,
    val state: String,
    val codeVerifier: String,
    val redirectUri: String
)

data class AccountUiState(
    val accountKey: AccountKey,
    val providerId: ProviderId = accountKey.providerId,
    val added: Boolean = false,
    val authState: AuthState = AuthState.Unknown,
    val loginStart: OAuthLoginStart? = null,
    val snapshot: ProviderUsageSnapshot? = null,
    val busy: Boolean = false,
    val message: String? = null
)

data class DashboardState(
    val accounts: Map<AccountKey, AccountUiState> = emptyMap()
)

class ProviderException(
    val state: AuthState,
    message: String,
    val statusCode: Int? = null
) : Exception(message)

fun safeUserMessage(error: Throwable, fallback: String = "Something went wrong. Please try again."): String {
    return when (error) {
        is ProviderException -> error.message ?: fallback
        else -> fallback
    }
}

private data class OAuthSpec(
    val authUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String
)

private object ProviderSpecs {
    val codex = OAuthSpec(
        authUrl = "https://auth.openai.com/oauth/authorize",
        tokenUrl = "https://auth.openai.com/oauth/token",
        clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
        redirectUri = "http://localhost:1455/auth/callback",
        scope = "openid email profile offline_access"
    )

    val claude = OAuthSpec(
        authUrl = "https://claude.ai/oauth/authorize",
        tokenUrl = "https://api.anthropic.com/v1/oauth/token",
        clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
        redirectUri = "http://localhost:54545/callback",
        scope = "org:create_api_key user:profile user:inference"
    )

    fun forProvider(providerId: ProviderId): OAuthSpec = when (providerId) {
        ProviderId.CODEX -> codex
        ProviderId.CLAUDE_CODE -> claude
    }
}

data class DetectedAccount(
    val accountKey: AccountKey,
    val authState: AuthState
)

class QuotaDogClient(
    private val tokenStore: TokenStore = PlatformTokenStore(),
    private val browserLauncher: BrowserLauncher = PlatformBrowserLauncher(),
    private val callbackServer: OAuthCallbackServer = PlatformOAuthCallbackServer(),
    private val httpClient: HttpClient = createHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun detectAccounts(): List<DetectedAccount> {
        return tokenStore.list().map { stored ->
            DetectedAccount(
                accountKey = stored.accountKey,
                authState = if (stored.token.isExpired(bufferMillis = 0)) AuthState.TokenExpired else AuthState.LoggedIn
            )
        }
    }

    fun beginLogin(providerId: ProviderId, openBrowser: Boolean = true): OAuthLoginStart {
        val spec = ProviderSpecs.forProvider(providerId)
        val verifier = base64UrlNoPadding(secureRandomBytes(32))
        val challenge = base64UrlNoPadding(sha256(verifier.encodeToByteArray()))
        val state = base64UrlNoPadding(secureRandomBytes(24))
        val url = URLBuilder(spec.authUrl).apply {
            parameters.append("client_id", spec.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", spec.redirectUri)
            parameters.append("scope", spec.scope)
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
            when (providerId) {
                ProviderId.CODEX -> {
                    parameters.append("prompt", "login")
                    parameters.append("id_token_add_organizations", "true")
                    parameters.append("codex_cli_simplified_flow", "true")
                }
                ProviderId.CLAUDE_CODE -> Unit
            }
        }.buildString()
        if (openBrowser) browserLauncher.open(url)
        return OAuthLoginStart(providerId, url, state, verifier, spec.redirectUri)
    }

    fun openAuthorizationUrl(start: OAuthLoginStart): Boolean {
        return browserLauncher.open(start.authorizationUrl)
    }

    suspend fun waitForLocalCallback(providerId: ProviderId, timeoutMillis: Long = 300_000): String? {
        return callbackServer.waitForCallback(providerId, timeoutMillis)
    }

    suspend fun completeLogin(start: OAuthLoginStart, callbackUri: String): AccountKey {
        val callback = OAuthCallback.parse(callbackUri)
        if (callback.error != null) {
            platformDebugLog("completeLogin callback error provider=${start.providerId.name}")
            throw ProviderException(AuthState.Unauthorized, "Authorization was cancelled or denied")
        }
        if (callback.state != start.state) {
            platformDebugLog("completeLogin state mismatch provider=${start.providerId.name}")
            throw ProviderException(AuthState.Error, "OAuth state mismatch, please sign in again")
        }
        val token = exchangeCode(start.providerId, callback.code, start.codeVerifier, callback.state)
        return tokenStore.save(start.providerId, token)
    }

    suspend fun logout(accountKey: AccountKey) {
        tokenStore.delete(accountKey)
    }

    suspend fun refreshUsage(accountKey: AccountKey): ProviderUsageSnapshot {
        val token = ensureFreshToken(accountKey)
        return when (accountKey.providerId) {
            ProviderId.CODEX -> fetchCodexUsage(token)
            ProviderId.CLAUDE_CODE -> fetchClaudeUsage(token)
        }
    }

    private suspend fun ensureFreshToken(accountKey: AccountKey): OAuthTokenBundle {
        val token = tokenStore.load(accountKey)
            ?: throw ProviderException(AuthState.NotConfigured, "Not signed in to ${accountKey.providerId.displayName}")
        if (!token.isExpired()) return token
        val refreshed = refreshToken(accountKey.providerId, token.refreshToken).withIdentityFrom(token)
        tokenStore.save(accountKey, refreshed)
        return refreshed
    }

    private suspend fun exchangeCode(
        providerId: ProviderId,
        code: String,
        codeVerifier: String,
        oauthState: String
    ): OAuthTokenBundle {
        val spec = ProviderSpecs.forProvider(providerId)
        return when (providerId) {
            ProviderId.CODEX -> {
                val responseText = httpClient.post(spec.tokenUrl) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(FormDataContent(Parameters.build {
                        append("grant_type", "authorization_code")
                        append("client_id", spec.clientId)
                        append("code", code)
                        append("redirect_uri", spec.redirectUri)
                        append("code_verifier", codeVerifier)
                    }))
                }.expectSuccessBody("Codex token exchange")
                val decoded = json.decodeFromString(CodexTokenResponse.serializer(), responseText)
                decoded.toBundle()
            }
            ProviderId.CLAUDE_CODE -> {
                val responseText = httpClient.post(spec.tokenUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            ClaudeTokenRequest.serializer(),
                            ClaudeTokenRequest(
                                code = code.substringBefore("#"),
                                state = code.substringAfter("#", oauthState),
                                grantType = "authorization_code",
                                clientId = spec.clientId,
                                redirectUri = spec.redirectUri,
                                codeVerifier = codeVerifier
                            )
                        )
                    )
                }.expectSuccessBody("Claude token exchange")
                val decoded = json.decodeFromString(ClaudeTokenResponse.serializer(), responseText)
                decoded.toBundle()
            }
        }
    }

    private suspend fun refreshToken(providerId: ProviderId, refreshToken: String): OAuthTokenBundle {
        val spec = ProviderSpecs.forProvider(providerId)
        return when (providerId) {
            ProviderId.CODEX -> {
                val responseText = httpClient.post(spec.tokenUrl) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(FormDataContent(Parameters.build {
                        append("client_id", spec.clientId)
                        append("grant_type", "refresh_token")
                        append("refresh_token", refreshToken)
                        append("scope", "openid profile email")
                    }))
                }.expectSuccessBody("Codex token refresh")
                json.decodeFromString(CodexTokenResponse.serializer(), responseText).toBundle()
            }
            ProviderId.CLAUDE_CODE -> {
                val responseText = httpClient.post(spec.tokenUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            ClaudeRefreshRequest.serializer(),
                            ClaudeRefreshRequest(spec.clientId, refreshToken)
                        )
                    )
                }.expectSuccessBody("Claude token refresh")
                json.decodeFromString(ClaudeTokenResponse.serializer(), responseText).toBundle()
            }
        }
    }

    private suspend fun fetchCodexUsage(token: OAuthTokenBundle): ProviderUsageSnapshot {
        val text = httpClient.get("https://chatgpt.com/backend-api/wham/usage") {
            header("Authorization", "Bearer ${token.accessToken}")
            header("Accept", "application/json")
            header("User-Agent", "codex_cli_rs/0.116.0 (QuotaDog; Kotlin)")
            header("Originator", "codex_cli_rs")
            token.accountId?.let { header("Chatgpt-Account-Id", it) }
        }.expectSuccessBody("Codex usage")
        val root = json.parseToJsonElement(text).jsonObject
        val rateLimit = root["rate_limit"]?.jsonObject
            ?: throw ProviderException(AuthState.Error, "Codex usage response is missing 'rate_limit'")
        val windows = listOfNotNull(
            parseCodexWindow("primary", rateLimit["primary_window"]),
            parseCodexWindow("secondary", rateLimit["secondary_window"])
        ).sortedBy { it.resetsAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }
        return ProviderUsageSnapshot(
            providerId = ProviderId.CODEX,
            authState = AuthState.LoggedIn,
            windows = windows,
            collectedAt = Clock.System.now(),
            accountEmail = token.email,
            message = root["plan_type"]?.jsonPrimitive?.contentOrNull?.let { "Plan: $it" }
        )
    }

    private suspend fun fetchClaudeUsage(token: OAuthTokenBundle): ProviderUsageSnapshot {
        val text = httpClient.get("https://api.anthropic.com/api/oauth/usage") {
            header("Authorization", "Bearer ${token.accessToken}")
            header("anthropic-beta", "oauth-2025-04-20")
            header("User-Agent", "claude-code/1.0.0")
            header("Content-Type", "application/json")
            header("Accept", "application/json")
        }.expectSuccessBody("Claude usage")
        val root = json.parseToJsonElement(text).jsonObject
        val windows = listOfNotNull(
            parseClaudeWindow("five_hour", "5-hour window", root["five_hour"]),
            parseClaudeWindow("seven_day", "7-day window", root["seven_day"]),
            parseClaudeWindow("seven_day_sonnet", "Sonnet weekly quota", root["seven_day_sonnet"]),
            parseClaudeWindow("seven_day_opus", "Opus weekly quota", root["seven_day_opus"])
        )
        return ProviderUsageSnapshot(
            providerId = ProviderId.CLAUDE_CODE,
            authState = AuthState.LoggedIn,
            windows = windows,
            collectedAt = Clock.System.now(),
            accountEmail = token.email
        )
    }

    private suspend fun io.ktor.client.statement.HttpResponse.expectSuccessBody(label: String): String {
        val text = bodyAsText()
        if (status == HttpStatusCode.TooManyRequests) {
            throw ProviderException(AuthState.RateLimited, "$label was rate limited", status.value)
        }
        if (status == HttpStatusCode.Unauthorized) {
            throw ProviderException(AuthState.RequiresRelogin, "$label is unauthorized, please sign in again", status.value)
        }
        if (status == HttpStatusCode.Forbidden) {
            throw ProviderException(AuthState.Unauthorized, "$label was forbidden", status.value)
        }
        if (!status.isSuccess()) {
            throw ProviderException(AuthState.Error, "$label failed: HTTP ${status.value}", status.value)
        }
        return text
    }
}

class QuotaDogStore(
    private val client: QuotaDogClient = QuotaDogClient(),
    private val usageSnapshotStore: SettingsUsageSnapshotStore = SettingsUsageSnapshotStore(),
    private val onLocalDataChanged: (() -> Unit)? = null,
    private val onLocalAccountDeleted: ((AccountKey) -> Unit)? = null
) {
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private val activeRefreshes = mutableSetOf<AccountKey>()
    private var detectStarted = false
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state

    fun startDetectAll() {
        if (detectStarted) return
        detectStarted = true
        launchSafely("detectAll") { detectAll() }
    }

    fun startReloadAccounts() {
        launchSafely("reloadAccounts") { detectAll() }
    }

    fun startLogin(providerId: ProviderId) {
        launchSafely("beginLogin:${providerId.name}") { beginLoginAndWait(providerId) }
    }

    fun startCompleteLogin(accountKey: AccountKey, callbackUri: String) {
        launchSafely("completeLogin:${accountKey.providerId.name}") {
            completeLogin(accountKey, callbackUri)
        }
    }

    fun startReopenLogin(accountKey: AccountKey) {
        launchSafely("reopenLogin:${accountKey.providerId.name}") {
            val start = state.value.accounts[accountKey]?.loginStart ?: return@launchSafely
            val opened = client.openAuthorizationUrl(start)
            update(accountKey) {
                it.copy(
                    message = if (opened) {
                        "Browser opened. Waiting for local callback; you can still paste it manually after timeout."
                    } else {
                        "Could not open the browser automatically; please visit the authorization URL manually."
                    }
                )
            }
        }
    }

    fun startRefresh(accountKey: AccountKey) {
        launchSafely("refresh:${accountKey.providerId.name}") { refresh(accountKey) }
    }

    /** Refresh every account currently signed in. Used by manual "Refresh all" + auto-refresh. */
    fun startRefreshAll() {
        launchSafely("refreshAll") {
            val accounts = state.value.accounts.values
                .filter { it.added && (it.authState == AuthState.LoggedIn || it.authState == AuthState.TokenExpired) }
                .map { it.accountKey }
            coroutineScope {
                accounts.forEach { accountKey ->
                    launch { refresh(accountKey) }
                }
            }
        }
    }

    fun startDelete(accountKey: AccountKey) {
        launchSafely("delete:${accountKey.providerId.name}") { delete(accountKey) }
    }

    suspend fun detectAll() {
        val accounts = client.detectAccounts()
        accounts.forEach { account ->
            val shouldRefresh = account.authState == AuthState.LoggedIn || account.authState == AuthState.TokenExpired
            val cachedSnapshot = usageSnapshotStore.load(account.accountKey)
                ?.asCachedSnapshot(account.authState)
            update(account.accountKey) {
                it.copy(
                    added = true,
                    authState = account.authState,
                    snapshot = cachedSnapshot ?: it.snapshot,
                    busy = shouldRefresh,
                    message = null
                )
            }
        }
        coroutineScope {
            accounts.forEach { account ->
                if (account.authState == AuthState.LoggedIn || account.authState == AuthState.TokenExpired) {
                    launch { refresh(account.accountKey) }
                }
            }
        }
    }

    suspend fun beginLoginAndWait(providerId: ProviderId) {
        var start: OAuthLoginStart? = null
        var pendingKey: AccountKey? = null
        var callbackReceived = false
        try {
            val loginStart = client.beginLogin(providerId, openBrowser = false)
            start = loginStart
            val activePendingKey = AccountKey.pending(providerId, loginStart.state)
            pendingKey = activePendingKey
            update(activePendingKey) {
                it.copy(
                    added = true,
                    authState = AuthState.Unknown,
                    busy = true,
                    loginStart = loginStart,
                    message = "Starting local callback listener..."
                )
            }
            val callbackUri = coroutineScope {
                val callback = async { client.waitForLocalCallback(providerId) }
                delay(250)
                if (callback.isCompleted) {
                    callback.await()
                        ?: throw ProviderException(AuthState.Error, "Failed to start local callback listener, please retry")
                }
                val opened = client.openAuthorizationUrl(loginStart)
                update(activePendingKey) {
                    it.copy(
                        busy = true,
                        loginStart = loginStart,
                        message = if (opened) {
                            "Browser opened. Waiting for local callback; you can still paste it manually after timeout."
                        } else {
                            "Could not open the browser automatically; please visit the authorization URL manually."
                        }
                    )
                }
                callback.await()
            }
            if (callbackUri == null) {
                update(activePendingKey) {
                    it.copy(
                        added = true,
                        busy = false,
                        loginStart = loginStart,
                        message = "No local callback received. Please paste the final callback URL from the browser."
                    )
                }
                return
            }
            callbackReceived = true
            update(activePendingKey) {
                it.copy(
                    added = true,
                    busy = true,
                    loginStart = loginStart,
                    message = "Received authorization callback, exchanging token..."
                )
            }
            val accountKey = client.completeLogin(loginStart, callbackUri)
            replacePendingWithAccount(
                pendingKey = activePendingKey,
                accountKey = accountKey,
                message = "Sign-in successful, refreshing usage..."
            )
            onLocalDataChanged?.invoke()
            refresh(accountKey)
        } catch (error: ProviderException) {
            platformDebugLog("store beginLoginAndWait provider error provider=${providerId.name} state=${error.state} status=${error.statusCode}")
            pendingKey?.let { key ->
                update(key) {
                    it.copy(
                        added = true,
                        authState = error.state,
                        busy = false,
                        loginStart = if (callbackReceived) null else start ?: it.loginStart,
                        message = safeUserMessage(error, "Sign-in failed. Please try again.")
                    )
                }
            }
        } catch (error: Throwable) {
            platformDebugLog("store beginLoginAndWait unexpected error provider=${providerId.name}")
            pendingKey?.let { key ->
                update(key) {
                    it.copy(
                        added = true,
                        authState = AuthState.Error,
                        busy = false,
                        loginStart = if (callbackReceived) null else start ?: it.loginStart,
                        message = safeUserMessage(error, "Sign-in failed. Please try again.")
                    )
                }
            }
        }
    }

    suspend fun completeLogin(accountKey: AccountKey, callbackUri: String) {
        val start = state.value.accounts[accountKey]?.loginStart
            ?: throw ProviderException(AuthState.Error, "No pending sign-in flow to complete")
        val savedKey = client.completeLogin(start, callbackUri)
        replacePendingWithAccount(
            pendingKey = accountKey,
            accountKey = savedKey,
            message = "Sign-in successful, refreshing usage..."
        )
        onLocalDataChanged?.invoke()
        refresh(savedKey)
    }

    suspend fun refresh(accountKey: AccountKey) {
        if (!beginRefresh(accountKey)) return
        update(accountKey) { it.copy(added = true, busy = true, message = null) }
        try {
            val snapshot = client.refreshUsage(accountKey)
            usageSnapshotStore.save(accountKey, snapshot)
            onLocalDataChanged?.invoke()
            update(accountKey) {
                it.copy(
                    added = true,
                    authState = snapshot.authState,
                    snapshot = snapshot,
                    busy = false,
                    message = snapshot.message
                )
            }
        } catch (error: ProviderException) {
            platformDebugLog("store refresh provider error provider=${accountKey.providerId.name} state=${error.state} status=${error.statusCode}")
            update(accountKey) {
                it.copy(
                    added = true,
                    authState = error.state,
                    busy = false,
                    message = safeUserMessage(error, "Refresh failed. Please try again.")
                )
            }
        } catch (error: Throwable) {
            platformDebugLog("store refresh unexpected error provider=${accountKey.providerId.name}")
            update(accountKey) {
                it.copy(
                    added = true,
                    authState = AuthState.Error,
                    busy = false,
                    message = safeUserMessage(error, "Refresh failed. Please try again.")
                )
            }
        } finally {
            endRefresh(accountKey)
        }
    }

    suspend fun delete(accountKey: AccountKey) {
        client.logout(accountKey)
        usageSnapshotStore.delete(accountKey)
        onLocalAccountDeleted?.invoke(accountKey) ?: onLocalDataChanged?.invoke()
        _state.update { current ->
            current.copy(accounts = current.accounts - accountKey)
        }
    }

    private fun launchSafely(label: String, block: suspend () -> Unit) {
        storeScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                platformDebugLog("store action failed label=$label")
            }
        }
    }

    private fun replacePendingWithAccount(pendingKey: AccountKey, accountKey: AccountKey, message: String) {
        _state.update { current ->
            val pending = current.accounts[pendingKey]
            val existing = current.accounts[accountKey]
            val merged = (existing ?: AccountUiState(accountKey)).copy(
                added = true,
                authState = AuthState.LoggedIn,
                loginStart = null,
                busy = true,
                snapshot = existing?.snapshot ?: pending?.snapshot,
                message = message
            )
            current.copy(accounts = (current.accounts - pendingKey) + (accountKey to merged))
        }
    }

    private fun update(accountKey: AccountKey, transform: (AccountUiState) -> AccountUiState) {
        _state.update { current ->
            val existing = current.accounts[accountKey] ?: AccountUiState(accountKey)
            current.copy(accounts = current.accounts + (accountKey to transform(existing)))
        }
    }

    private suspend fun beginRefresh(accountKey: AccountKey): Boolean {
        return refreshMutex.withLock {
            if (accountKey in activeRefreshes) false else activeRefreshes.add(accountKey)
        }
    }

    private suspend fun endRefresh(accountKey: AccountKey) {
        refreshMutex.withLock {
            activeRefreshes.remove(accountKey)
        }
    }
}

private fun parseCodexWindow(id: String, element: JsonElement?): UsageWindow? {
    if (element == null || element is JsonNull) return null
    val obj = element.jsonObject
    val used = obj["used_percent"]?.jsonPrimitive?.doubleOrNull ?: return null
    val seconds = obj["limit_window_seconds"]?.jsonPrimitive?.intOrNull
    val label = when (seconds) {
        18_000 -> "5-hour window"
        604_800 -> "7-day window"
        else -> if ((seconds ?: 0) >= 604_800) "Weekly window" else "Window ${seconds ?: "unknown"}s"
    }
    val resetAt = obj["reset_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?.let { Instant.fromEpochSeconds(it) }
    return UsageWindow(
        id = id,
        label = label,
        usedRatio = normalizeUtilization(used),
        resetsAt = resetAt,
        durationSeconds = seconds?.toLong()
    )
}

private fun parseClaudeWindow(id: String, label: String, element: JsonElement?): UsageWindow? {
    if (element == null || element is JsonNull) return null
    val obj = element.jsonObject
    val used = obj["utilization"]?.jsonPrimitive?.doubleOrNull ?: return null
    val resetAt = obj["resets_at"]?.jsonPrimitive?.contentOrNull
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return UsageWindow(
        id = id,
        label = label,
        usedRatio = normalizeUtilization(used),
        resetsAt = resetAt,
        durationSeconds = inferWindowDurationSeconds(id, label)
    )
}

private fun ProviderUsageSnapshot.asCachedSnapshot(authState: AuthState): ProviderUsageSnapshot {
    return copy(
        authState = authState,
        stale = true,
        windows = windows.map { it.copy(source = UsageSource.Cached) }
    )
}

fun normalizeUtilization(value: Double): Double {
    return (if (value > 1.0) value / 100.0 else value).coerceIn(0.0, 1.0)
}

fun UsageWindow.projectedUsedRatio(at: Instant = Clock.System.now()): Double? {
    val resetAtMillis = resetsAt?.toEpochMilliseconds() ?: return null
    val durationMillis = (durationSeconds ?: inferWindowDurationSeconds(id, label))
        ?.takeIf { it > 0 }
        ?.let { it * 1_000L }
        ?: return null
    val remainingMillis = resetAtMillis - at.toEpochMilliseconds()
    if (remainingMillis <= 0 || remainingMillis >= durationMillis) return null
    val elapsedRatio = (durationMillis - remainingMillis).toDouble() / durationMillis.toDouble()
    if (elapsedRatio <= 0.0) return null
    return (usedRatio / elapsedRatio).coerceIn(usedRatio, 1.0)
}

private fun inferWindowDurationSeconds(id: String, label: String): Long? {
    return when (id) {
        "primary", "five_hour" -> FIVE_HOUR_SECONDS
        "secondary", "seven_day", "seven_day_sonnet", "seven_day_opus" -> SEVEN_DAY_SECONDS
        else -> when {
            label.contains("5-hour", ignoreCase = true) -> FIVE_HOUR_SECONDS
            label.contains("7-day", ignoreCase = true) -> SEVEN_DAY_SECONDS
            label.contains("weekly", ignoreCase = true) -> SEVEN_DAY_SECONDS
            else -> null
        }
    }
}

private const val FIVE_HOUR_SECONDS = 5L * 60L * 60L
private const val SEVEN_DAY_SECONDS = 7L * 24L * 60L * 60L

private object OAuthCallback {
    fun parse(raw: String): ParsedOAuthCallback {
        val url = Url(raw)
        val error = url.parameters["error"]
        val code = url.parameters["code"]
            ?: throw ProviderException(AuthState.Error, "Callback URL is missing 'code'")
        val state = url.parameters["state"]
            ?: throw ProviderException(AuthState.Error, "Callback URL is missing 'state'")
        return ParsedOAuthCallback(code = code, state = state, error = error)
    }
}

private data class ParsedOAuthCallback(
    val code: String,
    val state: String,
    val error: String?
)

@Serializable
private data class CodexTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 3600
) {
    fun toBundle(): OAuthTokenBundle {
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            email = idToken?.jwtStringClaim("email"),
            expiresAtEpochMillis = Clock.System.now().plusSeconds(expiresIn.toLong()).toEpochMilliseconds()
        )
    }
}

@Serializable
private data class ClaudeTokenRequest(
    val code: String,
    val state: String,
    @SerialName("grant_type") val grantType: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("code_verifier") val codeVerifier: String
)

@Serializable
private data class ClaudeRefreshRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("grant_type") val grantType: String = "refresh_token"
)

@Serializable
private data class ClaudeTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int = 3600,
    val account: ClaudeAccount? = null
) {
    fun toBundle(): OAuthTokenBundle {
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = refreshToken,
            email = account?.emailAddress,
            expiresAtEpochMillis = Clock.System.now().plusSeconds(expiresIn.toLong()).toEpochMilliseconds()
        )
    }
}

@Serializable
private data class ClaudeAccount(
    @SerialName("email_address") val emailAddress: String? = null
)

private fun Instant.plusSeconds(seconds: Long): Instant {
    return Instant.fromEpochMilliseconds(toEpochMilliseconds() + seconds * 1000)
}

private fun OAuthTokenBundle.withIdentityFrom(previous: OAuthTokenBundle): OAuthTokenBundle {
    return copy(
        idToken = idToken ?: previous.idToken,
        accountId = accountId ?: previous.accountId,
        email = email ?: previous.email
    )
}

private fun String.jwtStringClaim(name: String): String? {
    val payload = split(".").getOrNull(1) ?: return null
    val decoded = runCatching { base64UrlDecode(payload).decodeToString() }.getOrNull() ?: return null
    val claims = runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
    return claims[name]?.jsonPrimitive?.contentOrNull
}

private fun base64UrlDecode(input: String): ByteArray {
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
