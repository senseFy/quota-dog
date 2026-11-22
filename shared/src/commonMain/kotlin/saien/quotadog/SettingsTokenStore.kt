package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SettingsTokenStore(
    private val settings: Settings
) : TokenStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun list(): List<StoredToken> = mutex.withLock {
        migrateLegacyProviderKeys()
        listIndexed()
    }

    private fun listIndexed(): List<StoredToken> {
        val accounts = loadIndex()
        val stored = accounts.mapNotNull { account ->
            val key = AccountKey(account.providerId, account.accountId)
            read(key)?.let { StoredToken(key, it) }
        }
        if (stored.size != accounts.size) {
            saveIndex(stored.map { StoredAccountRef(it.accountKey.providerId, it.accountKey.accountId) })
        }
        return stored
    }

    override suspend fun load(accountKey: AccountKey): OAuthTokenBundle? = mutex.withLock { read(accountKey) }

    private fun read(accountKey: AccountKey): OAuthTokenBundle? {
        val encoded = settings.getStringOrNull(key(accountKey)) ?: return null
        val data = runCatching { json.parseToJsonElement(encoded).jsonObject }.getOrNull() ?: return null
        val token = runCatching { json.decodeFromJsonElement(OAuthTokenBundle.serializer(), data) }.getOrNull() ?: return null
        if (token.loginGeneration != null && "lastRefreshEpochMillis" in data) return token
        // Only an existing local row can inherit the pre-generation Devin tag used by 1.0.17.
        // A newly imported/synced row always gets a random generation, even for identical bytes.
        val generation = token.loginGeneration ?: if (accountKey.providerId == ProviderId.DEVIN) {
            base64UrlNoPadding(sha256("${token.apiServerUrl.orEmpty()}\u0000${token.expiresAtEpochMillis}\u0000${token.accessToken}".encodeToByteArray()))
        } else newId()
        // A formerly omitted, default-valued timestamp mustn't make this row newer than a deletion.
        val refreshedAt = if ("lastRefreshEpochMillis" in data) token.lastRefreshEpochMillis else tokenTimestamp(accountKey) ?: 0L
        return token.copy(loginGeneration = generation, lastRefreshEpochMillis = refreshedAt).also { writeValue(accountKey, it) }
    }

    override suspend fun save(providerId: ProviderId, token: OAuthTokenBundle): AccountKey = mutex.withLock {
        migrateLegacyProviderKeys()
        val rows = listIndexed().filter { it.accountKey.providerId == providerId }
        val exact = rows.singleOrNull { it.token.sameLoginAs(token) }
            ?: rows.singleOrNull { it.token.sameCredentialAndTarget(token) }
        // Only the subscription OAuth exchange identifies BOTH a Claude subscriber and org.
        // A relogin to that same target may keep its sort slot, but not the old login generation.
        val subscriber = if (exact == null && providerId == ProviderId.CLAUDE_CODE &&
            token.principalId?.matches(uuid) == true && token.accountId?.matches(uuid) == true) {
            rows.singleOrNull { it.token.principalId == token.principalId &&
                it.token.accountId == token.accountId && it.token.apiServerUrl == token.apiServerUrl }
        } else null
        val key = (exact ?: subscriber)?.accountKey ?: AccountKey(providerId, newId())
        saveLogin(key, token, Clock.System.now().toEpochMilliseconds())
        key
    }

    override suspend fun save(accountKey: AccountKey, token: OAuthTokenBundle) = mutex.withLock {
        saveLogin(accountKey, token, Clock.System.now().toEpochMilliseconds())
    }

    private fun saveLogin(accountKey: AccountKey, token: OAuthTokenBundle, updatedAtEpochMillis: Long) {
        val sameLogin = read(accountKey)?.takeIf { it.sameLoginAs(token) }
        // Never trust local-only fields on externally supplied credentials (including Dropbox).
        val local = token.copy(loginGeneration = sameLogin?.loginGeneration ?: newId(), quotaIdentityHash = sameLogin?.quotaIdentityHash)
        writeValue(accountKey, local)
        settings.putLong(updatedAtKey(accountKey), updatedAtEpochMillis)
        upsertIndex(accountKey)
    }

    override suspend fun commitIfCurrent(
        accountKey: AccountKey, expected: OAuthTokenBundle, replacement: OAuthTokenBundle, commit: () -> Unit,
    ): Boolean = mutex.withLock {
        require(expected.loginGeneration != null && replacement.loginGeneration != null)
        if (read(accountKey) != expected) return@withLock false
        if (replacement != expected) {
            writeValue(accountKey, replacement)
            if (replacement.withoutLocalBinding() != expected.withoutLocalBinding()) {
                settings.putLong(updatedAtKey(accountKey), Clock.System.now().toEpochMilliseconds())
            }
        }
        commit()
        true
    }

    private fun writeValue(accountKey: AccountKey, token: OAuthTokenBundle) {
        settings.putString(key(accountKey), json.encodeToString(OAuthTokenBundle.serializer(), token))
    }

    override suspend fun delete(accountKey: AccountKey) = deleteForSync(accountKey)

    override suspend fun deleteForSync(accountKey: AccountKey, throughEpochMillis: Long?) = mutex.withLock {
        migrateLegacyProviderKeys()
        if (throughEpochMillis != null) {
            val lastEdit = tokenTimestamp(accountKey) ?: read(accountKey)?.lastRefreshEpochMillis
            if (lastEdit != null && lastEdit > throughEpochMillis) return@withLock
        }
        settings.remove(key(accountKey))
        settings.remove(updatedAtKey(accountKey))
        // Legacy suffix used before key-length tightening.
        settings.remove("${key(accountKey)}_updated_at")
        saveIndex(loadIndex().filterNot { it.providerId == accountKey.providerId && it.accountId == accountKey.accountId })
    }

    override suspend fun exportTokensForSync(): List<CloudSyncAccountRecord> = mutex.withLock {
        migrateLegacyProviderKeys()
        listIndexed().map { stored ->
            CloudSyncAccountRecord(
                providerId = stored.accountKey.providerId,
                accountId = stored.accountKey.accountId,
                token = CloudSyncTokenValue(
                    value = stored.token.withoutLocalBinding(),
                    updatedAtEpochMillis = tokenTimestamp(stored.accountKey) ?: stored.token.lastRefreshEpochMillis
                )
            )
        }
    }

    override suspend fun importTokenForSync(
        accountKey: AccountKey,
        token: OAuthTokenBundle,
        updatedAtEpochMillis: Long
    ) = mutex.withLock {
        // Claude usage doesn't repeat its subscriber UUID. Only a local OAuth response is
        // evidence for that identity. An unchanged sync echo can keep this device's own evidence.
        val current = read(accountKey)
        val candidate = token.copy(principalId = current?.principalId)
        val local = if (current != null && current.sameLoginAs(candidate)) candidate else token.copy(principalId = null)
        saveLogin(accountKey, local, updatedAtEpochMillis)
    }

    internal suspend fun importLegacyFile(providerId: ProviderId, token: OAuthTokenBundle) = mutex.withLock {
        migrateLegacyProviderKeys()
        importUnindexed(providerId, token)
    }

    private fun importUnindexed(providerId: ProviderId, token: OAuthTokenBundle) {
        // No row ID, hence no evidence of ownership of an email/default cache, sort slot or grant.
        // Preserve both credentials if the file could carry a different (e.g. newer) refresh grant.
        val portable = token.withoutLocalBinding()
        if (listIndexed().none { it.accountKey.providerId == providerId && it.token.withoutLocalBinding().sameLoginAs(portable) }) {
            saveLogin(AccountKey(providerId, newId()), portable, portable.lastRefreshEpochMillis)
        }
    }

    private fun migrateLegacyProviderKeys() {
        ProviderId.entries.forEach { providerId ->
            val legacyKey = legacyKey(providerId)
            if (!settings.hasKey(legacyKey)) return@forEach
            val encoded = settings.getString(legacyKey, "")
            val token = runCatching {
                json.decodeFromString(OAuthTokenBundle.serializer(), encoded)
            }.getOrNull()
            if (token != null) {
                importUnindexed(providerId, token)
                settings.remove(legacyKey)
            }
        }
    }

    private fun upsertIndex(accountKey: AccountKey) {
        val current = loadIndex()
        if (current.any { it.providerId == accountKey.providerId && it.accountId == accountKey.accountId }) return
        saveIndex(current + StoredAccountRef(accountKey.providerId, accountKey.accountId))
    }

    private fun loadIndex(): List<StoredAccountRef> {
        if (!settings.hasKey(KEY_INDEX)) return emptyList()
        val encoded = settings.getString(KEY_INDEX, "")
        return runCatching {
            json.decodeFromString(StoredAccountIndex.serializer(), encoded).accounts
        }.getOrDefault(emptyList())
    }

    private fun saveIndex(accounts: List<StoredAccountRef>) {
        val index = StoredAccountIndex(accounts.distinct())
        settings.putString(KEY_INDEX, json.encodeToString(StoredAccountIndex.serializer(), index))
    }

    private fun key(accountKey: AccountKey): String {
        val digest = base64UrlNoPadding(
            sha256("${accountKey.providerId.name}:${accountKey.accountId}".encodeToByteArray())
        )
        return "oauth_token_v1_${accountKey.providerId.name.lowercase()}_$digest"
    }

    private fun updatedAtKey(accountKey: AccountKey): String {
        // Keep under java.util.prefs MAX_KEY_LENGTH (80). Long provider names like
        // "antigravity" + full digest + "_updated_at" used to overflow.
        return "${key(accountKey)}_ts"
    }

    private fun tokenTimestamp(accountKey: AccountKey): Long? {
        return settings.getLongOrNull(updatedAtKey(accountKey))
            ?: settings.getLongOrNull("${key(accountKey)}_updated_at")
    }

    private fun legacyKey(providerId: ProviderId): String {
        return "oauth_token_${providerId.name.lowercase()}"
    }

    private companion object {
        const val KEY_INDEX = "oauth_token_accounts_v1"
        // Platform wrappers can refer to the same Settings via different Kotlin instances.
        val mutex = Mutex()
        val uuid = Regex("(?!0{8}-0{4}-0{4}-0{4}-0{12}$)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        fun newId() = base64UrlNoPadding(secureRandomBytes(16))
    }
}

private fun OAuthTokenBundle.sameCredentialAndTarget(other: OAuthTokenBundle) = accessToken.isNotBlank() &&
    accessToken == other.accessToken && accountId == other.accountId && principalId == other.principalId && apiServerUrl == other.apiServerUrl

private fun OAuthTokenBundle.sameLoginAs(other: OAuthTokenBundle) = sameCredentialAndTarget(other) &&
    refreshToken == other.refreshToken && idToken == other.idToken && expiresAtEpochMillis == other.expiresAtEpochMillis

@Serializable
private data class StoredAccountIndex(
    val accounts: List<StoredAccountRef> = emptyList()
)

@Serializable
private data class StoredAccountRef(
    val providerId: ProviderId,
    val accountId: String
)
