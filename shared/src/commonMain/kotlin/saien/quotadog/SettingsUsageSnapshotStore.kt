package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

class SettingsUsageSnapshotStore(
    private val settings: Settings = createPreferenceSettings()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(accountKey: AccountKey): ProviderUsageSnapshot? {
        val snapshotKey = key(accountKey)
        if (!settings.hasKey(snapshotKey)) return null
        val encoded = settings.getString(snapshotKey, "")
        return runCatching {
            json.decodeFromString(ProviderUsageSnapshot.serializer(), encoded)
        }.getOrNull()
    }

    fun save(accountKey: AccountKey, snapshot: ProviderUsageSnapshot) {
        val encoded = json.encodeToString(ProviderUsageSnapshot.serializer(), snapshot)
        settings.putString(key(accountKey), encoded)
    }

    fun delete(accountKey: AccountKey) {
        settings.remove(key(accountKey))
    }

    private fun key(accountKey: AccountKey): String {
        val digest = base64UrlNoPadding(
            sha256("${accountKey.providerId.name}:${accountKey.accountId}".encodeToByteArray())
        )
        return "usage_snapshot_v1_${accountKey.providerId.name.lowercase()}_$digest"
    }
}
