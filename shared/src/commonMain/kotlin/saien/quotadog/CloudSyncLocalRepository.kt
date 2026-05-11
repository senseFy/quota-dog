package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class CloudSyncLocalRepository(
    private val tokenStore: TokenStore,
    private val usageSnapshotStore: SettingsUsageSnapshotStore,
    private val preferences: AppPreferences,
    private val settings: Settings = createPreferenceSettings()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exportDocument(): CloudSyncDocumentV1 {
        val tokenRecords = tokenStore.exportTokensForSync()
        val snapshotRecords = snapshotRecordsForSync(tokenRecords)
        val tombstones = loadTombstones()
        val accounts = mergeLocalRecords(tokenRecords + snapshotRecords, tombstones)
        return CloudSyncDocumentV1(
            deviceId = deviceId(),
            updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            accounts = accounts,
            preferences = preferences.exportForSync()
        )
    }

    suspend fun applyDocument(document: CloudSyncDocumentV1) {
        document.accounts.forEach { record ->
            val accountKey = record.accountKey
            if (record.token == null && record.snapshot == null && record.deletedAtEpochMillis != null) {
                tokenStore.deleteForSync(accountKey)
                usageSnapshotStore.deleteForSync(accountKey)
                saveTombstone(accountKey, record.deletedAtEpochMillis)
                return@forEach
            }
            record.token?.let {
                tokenStore.importTokenForSync(accountKey, it.value, it.updatedAtEpochMillis)
            }
            record.snapshot?.let {
                usageSnapshotStore.importSnapshotForSync(accountKey, it.value, it.updatedAtEpochMillis)
            }
            record.deletedAtEpochMillis?.let {
                saveTombstone(accountKey, it)
            }
        }
        preferences.importForSync(document.preferences)
    }

    fun recordAccountDeleted(accountKey: AccountKey, deletedAtEpochMillis: Long = Clock.System.now().toEpochMilliseconds()) {
        saveTombstone(accountKey, deletedAtEpochMillis)
    }

    private suspend fun snapshotRecordsForSync(tokenRecords: List<CloudSyncAccountRecord>): List<CloudSyncAccountRecord> {
        val indexed = usageSnapshotStore.exportSnapshotsForSync()
        val indexedKeys = indexed.map { it.accountKey }.toSet()
        val legacy = tokenRecords.mapNotNull { tokenRecord ->
            if (tokenRecord.accountKey in indexedKeys) return@mapNotNull null
            val snapshot = usageSnapshotStore.load(tokenRecord.accountKey) ?: return@mapNotNull null
            CloudSyncAccountRecord(
                providerId = tokenRecord.providerId,
                accountId = tokenRecord.accountId,
                snapshot = CloudSyncSnapshotValue(
                    value = snapshot,
                    updatedAtEpochMillis = snapshot.collectedAt.toEpochMilliseconds()
                )
            )
        }
        return indexed + legacy
    }

    private fun mergeLocalRecords(
        records: List<CloudSyncAccountRecord>,
        tombstones: List<CloudSyncTombstone>
    ): List<CloudSyncAccountRecord> {
        val recordKeys = records.map { it.accountKey }
        val tombstoneKeys = tombstones.map { AccountKey(it.providerId, it.accountId) }
        return (recordKeys + tombstoneKeys).distinct().mapNotNull { key ->
            val token = records.mapNotNull { if (it.accountKey == key) it.token else null }
                .maxByOrNull { it.updatedAtEpochMillis }
            val snapshot = records.mapNotNull { if (it.accountKey == key) it.snapshot else null }
                .maxByOrNull { it.updatedAtEpochMillis }
            val deletedAt = tombstones
                .filter { it.providerId == key.providerId && it.accountId == key.accountId }
                .maxOfOrNull { it.deletedAtEpochMillis }
            val liveToken = token?.takeIf { deletedAt == null || it.updatedAtEpochMillis > deletedAt }
            val liveSnapshot = snapshot?.takeIf { deletedAt == null || it.updatedAtEpochMillis > deletedAt }
            if (liveToken == null && liveSnapshot == null && deletedAt == null) return@mapNotNull null
            CloudSyncAccountRecord(
                providerId = key.providerId,
                accountId = key.accountId,
                token = liveToken,
                snapshot = liveSnapshot,
                deletedAtEpochMillis = deletedAt
            )
        }.sortedWith(compareBy<CloudSyncAccountRecord> { it.providerId.name }.thenBy { it.accountId })
    }

    private fun saveTombstone(accountKey: AccountKey, deletedAtEpochMillis: Long) {
        val current = loadTombstones()
            .filterNot { it.providerId == accountKey.providerId && it.accountId == accountKey.accountId }
        saveTombstones(
            current + CloudSyncTombstone(
                providerId = accountKey.providerId,
                accountId = accountKey.accountId,
                deletedAtEpochMillis = deletedAtEpochMillis
            )
        )
    }

    private fun loadTombstones(): List<CloudSyncTombstone> {
        if (!settings.hasKey(KEY_TOMBSTONES)) return emptyList()
        return runCatching {
            json.decodeFromString(CloudSyncTombstones.serializer(), settings.getString(KEY_TOMBSTONES, "")).accounts
        }.getOrDefault(emptyList())
    }

    private fun saveTombstones(accounts: List<CloudSyncTombstone>) {
        settings.putString(
            KEY_TOMBSTONES,
            json.encodeToString(CloudSyncTombstones.serializer(), CloudSyncTombstones(accounts))
        )
    }

    private fun deviceId(): String {
        val existing = settings.getStringOrNull(KEY_DEVICE_ID)
        if (existing != null) return existing
        val generated = base64UrlNoPadding(secureRandomBytes(16))
        settings.putString(KEY_DEVICE_ID, generated)
        return generated
    }

    private companion object {
        const val KEY_DEVICE_ID = "cloud_sync_device_id_v1"
        const val KEY_TOMBSTONES = "cloud_sync_tombstones_v1"
    }
}

@Serializable
private data class CloudSyncTombstones(
    val accounts: List<CloudSyncTombstone> = emptyList()
)

@Serializable
private data class CloudSyncTombstone(
    val providerId: ProviderId,
    val accountId: String,
    val deletedAtEpochMillis: Long
)
