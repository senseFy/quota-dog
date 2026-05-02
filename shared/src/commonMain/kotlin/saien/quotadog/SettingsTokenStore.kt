package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SettingsTokenStore(
    private val settings: Settings
) : TokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun list(): List<StoredToken> {
        migrateLegacyProviderKeys()
        val accounts = loadIndex()
        val stored = accounts.mapNotNull { account ->
            val key = AccountKey(account.providerId, account.accountId)
            load(key)?.let { StoredToken(key, it) }
        }
        if (stored.size != accounts.size) {
            saveIndex(stored.map { StoredAccountRef(it.accountKey.providerId, it.accountKey.accountId) })
        }
        return stored
    }

    override suspend fun load(accountKey: AccountKey): OAuthTokenBundle? {
        val tokenKey = key(accountKey)
        if (!settings.hasKey(tokenKey)) return null
        val encoded = settings.getString(tokenKey, "")
        return runCatching {
            json.decodeFromString(OAuthTokenBundle.serializer(), encoded)
        }.getOrNull()
    }

    override suspend fun save(providerId: ProviderId, token: OAuthTokenBundle): AccountKey {
        val accountKey = accountKeyForToken(providerId, token)
        save(accountKey, token)
        return accountKey
    }

    override suspend fun save(accountKey: AccountKey, token: OAuthTokenBundle) {
        val encoded = json.encodeToString(OAuthTokenBundle.serializer(), token)
        settings.putString(key(accountKey), encoded)
        upsertIndex(accountKey)
    }

    override suspend fun delete(accountKey: AccountKey) {
        settings.remove(key(accountKey))
        saveIndex(loadIndex().filterNot { it.providerId == accountKey.providerId && it.accountId == accountKey.accountId })
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
                val accountKey = accountKeyForToken(providerId, token)
                settings.putString(key(accountKey), encoded)
                upsertIndex(accountKey)
            }
            settings.remove(legacyKey)
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

    private fun legacyKey(providerId: ProviderId): String {
        return "oauth_token_${providerId.name.lowercase()}"
    }

    private companion object {
        const val KEY_INDEX = "oauth_token_accounts_v1"
    }
}

fun accountKeyForToken(providerId: ProviderId, token: OAuthTokenBundle): AccountKey {
    val accountId = token.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: token.accountId?.trim()?.takeIf { it.isNotEmpty() }
        ?: "default"
    return AccountKey(providerId, accountId)
}

@Serializable
private data class StoredAccountIndex(
    val accounts: List<StoredAccountRef> = emptyList()
)

@Serializable
private data class StoredAccountRef(
    val providerId: ProviderId,
    val accountId: String
)
