package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SettingsUsageSnapshotStore(
    private val settings: Settings = createPreferenceSettings(),
    private val phoneDisplay: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(accountKey: AccountKey): ProviderUsageSnapshot? {
        val snapshotKey = key(accountKey)
        if (!settings.hasKey(snapshotKey)) return null
        val encoded = settings.getString(snapshotKey, "")
        return runCatching {
            if (phoneDisplay) json.decodePhoneSnapshot(encoded) else json.decodeFromString(ProviderUsageSnapshot.serializer(), encoded)
        }.getOrNull()
    }

    fun save(accountKey: AccountKey, snapshot: ProviderUsageSnapshot) {
        val encoded = if (phoneDisplay) json.encodePhoneSnapshot(snapshot) else json.encodeToString(ProviderUsageSnapshot.serializer(), snapshot)
        settings.putString(key(accountKey), encoded)
    }

    fun delete(accountKey: AccountKey) {
        val key = key(accountKey)
        settings.remove(key)
        settings.remove("${key}_ts")
        settings.remove("${key}_updated_at")

        // Older versions also indexed snapshots by account ID. Forget the deleted ID there
        // without enumerating readings or creating an index on a fresh install.
        val encoded = settings.getStringOrNull(LEGACY_INDEX) ?: return
        val index = runCatching { json.decodeFromString<LegacySnapshotIndex>(encoded) }.getOrNull() ?: return
        if (accountKey in index.accounts) {
            settings.putString(LEGACY_INDEX, json.encodeToString(index.copy(accounts = index.accounts.filterNot { it == accountKey })))
        }
    }

    private fun key(accountKey: AccountKey): String {
        val digest = base64UrlNoPadding(
            sha256("${accountKey.providerId.name}:${accountKey.accountId}".encodeToByteArray())
        )
        return "usage_snapshot_v1_${accountKey.providerId.name.lowercase()}_$digest"
    }

    private companion object {
        const val LEGACY_INDEX = "usage_snapshot_accounts_v1"
    }
}

@Serializable
private data class LegacySnapshotIndex(val accounts: List<AccountKey> = emptyList())
