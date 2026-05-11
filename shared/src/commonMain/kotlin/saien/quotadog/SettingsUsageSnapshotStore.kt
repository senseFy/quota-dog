package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
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
        save(accountKey, snapshot, Clock.System.now().toEpochMilliseconds())
    }

    fun save(accountKey: AccountKey, snapshot: ProviderUsageSnapshot, updatedAtEpochMillis: Long) {
        val encoded = json.encodeToString(ProviderUsageSnapshot.serializer(), snapshot)
        settings.putString(key(accountKey), encoded)
        settings.putLong(updatedAtKey(accountKey), updatedAtEpochMillis)
        upsertIndex(accountKey)
    }

    fun delete(accountKey: AccountKey) {
        settings.remove(key(accountKey))
        settings.remove(updatedAtKey(accountKey))
        saveIndex(loadIndex().filterNot { it.providerId == accountKey.providerId && it.accountId == accountKey.accountId })
    }

    fun exportSnapshotsForSync(): List<CloudSyncAccountRecord> {
        return loadIndex().mapNotNull { ref ->
            val accountKey = AccountKey(ref.providerId, ref.accountId)
            val snapshot = load(accountKey) ?: return@mapNotNull null
            CloudSyncAccountRecord(
                providerId = accountKey.providerId,
                accountId = accountKey.accountId,
                snapshot = CloudSyncSnapshotValue(
                    value = snapshot,
                    updatedAtEpochMillis = snapshotUpdatedAt(accountKey, snapshot)
                )
            )
        }
    }

    fun importSnapshotForSync(
        accountKey: AccountKey,
        snapshot: ProviderUsageSnapshot,
        updatedAtEpochMillis: Long
    ) {
        save(accountKey, snapshot, updatedAtEpochMillis)
    }

    fun deleteForSync(accountKey: AccountKey) {
        delete(accountKey)
    }

    private fun key(accountKey: AccountKey): String {
        val digest = base64UrlNoPadding(
            sha256("${accountKey.providerId.name}:${accountKey.accountId}".encodeToByteArray())
        )
        return "usage_snapshot_v1_${accountKey.providerId.name.lowercase()}_$digest"
    }

    private fun updatedAtKey(accountKey: AccountKey): String {
        return "${key(accountKey)}_updated_at"
    }

    private fun snapshotUpdatedAt(accountKey: AccountKey, snapshot: ProviderUsageSnapshot): Long {
        return settings.getLongOrNull(updatedAtKey(accountKey))
            ?: snapshot.collectedAt.toEpochMilliseconds()
    }

    private fun upsertIndex(accountKey: AccountKey) {
        val current = loadIndex()
        if (current.any { it.providerId == accountKey.providerId && it.accountId == accountKey.accountId }) return
        saveIndex(current + StoredSnapshotRef(accountKey.providerId, accountKey.accountId))
    }

    private fun loadIndex(): List<StoredSnapshotRef> {
        if (!settings.hasKey(KEY_INDEX)) return emptyList()
        val encoded = settings.getString(KEY_INDEX, "")
        return runCatching {
            json.decodeFromString(StoredSnapshotIndex.serializer(), encoded).accounts
        }.getOrDefault(emptyList())
    }

    private fun saveIndex(accounts: List<StoredSnapshotRef>) {
        settings.putString(
            KEY_INDEX,
            json.encodeToString(StoredSnapshotIndex.serializer(), StoredSnapshotIndex(accounts.distinct()))
        )
    }

    private companion object {
        const val KEY_INDEX = "usage_snapshot_accounts_v1"
    }
}

@Serializable
private data class StoredSnapshotIndex(
    val accounts: List<StoredSnapshotRef> = emptyList()
)

@Serializable
private data class StoredSnapshotRef(
    val providerId: ProviderId,
    val accountId: String
)
