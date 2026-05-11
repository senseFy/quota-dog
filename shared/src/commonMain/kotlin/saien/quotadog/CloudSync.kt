package saien.quotadog

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

enum class CloudSyncStatus {
    Disconnected,
    Locked,
    Connecting,
    Syncing,
    Connected,
    Error
}

data class CloudSyncUiState(
    val status: CloudSyncStatus = CloudSyncStatus.Disconnected,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val lastSyncedAtEpochMillis: Long? = null,
    val message: String? = null
)

@Serializable
data class CloudSyncDocumentV1(
    val schemaVersion: Int = 1,
    val deviceId: String,
    val updatedAtEpochMillis: Long,
    val accounts: List<CloudSyncAccountRecord> = emptyList(),
    val preferences: CloudSyncPreferencesRecord = CloudSyncPreferencesRecord()
)

@Serializable
data class CloudSyncAccountRecord(
    val providerId: ProviderId,
    val accountId: String,
    val token: CloudSyncTokenValue? = null,
    val snapshot: CloudSyncSnapshotValue? = null,
    val deletedAtEpochMillis: Long? = null
) {
    val accountKey: AccountKey get() = AccountKey(providerId, accountId)
}

@Serializable
data class CloudSyncTokenValue(
    val value: OAuthTokenBundle,
    val updatedAtEpochMillis: Long
)

@Serializable
data class CloudSyncSnapshotValue(
    val value: ProviderUsageSnapshot,
    val updatedAtEpochMillis: Long
)

@Serializable
data class CloudSyncPreferencesRecord(
    val themeMode: CloudSyncStringPreference? = null,
    val autoRefreshMinutes: CloudSyncIntPreference? = null,
    val usageDisplayMode: CloudSyncStringPreference? = null,
    val showProjectedUsage: CloudSyncBooleanPreference? = null,
    val emailPrivacyMode: CloudSyncStringPreference? = null
)

@Serializable
data class CloudSyncStringPreference(
    val value: String,
    val updatedAtEpochMillis: Long
)

@Serializable
data class CloudSyncIntPreference(
    val value: Int,
    val updatedAtEpochMillis: Long
)

@Serializable
data class CloudSyncBooleanPreference(
    val value: Boolean,
    val updatedAtEpochMillis: Long
)

fun mergeCloudSyncDocuments(
    local: CloudSyncDocumentV1,
    remote: CloudSyncDocumentV1,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds()
): CloudSyncDocumentV1 {
    val accountKeys = (local.accounts + remote.accounts)
        .map { it.accountKey }
        .distinct()
    val mergedAccounts = accountKeys.mapNotNull { key ->
        mergeAccountRecords(
            local.accounts.firstOrNull { it.accountKey == key },
            remote.accounts.firstOrNull { it.accountKey == key }
        )
    }.sortedWith(compareBy<CloudSyncAccountRecord> { it.providerId.name }.thenBy { it.accountId })

    return CloudSyncDocumentV1(
        schemaVersion = 1,
        deviceId = local.deviceId,
        updatedAtEpochMillis = maxOf(local.updatedAtEpochMillis, remote.updatedAtEpochMillis, nowEpochMillis),
        accounts = mergedAccounts,
        preferences = mergePreferences(local.preferences, remote.preferences)
    )
}

private fun mergeAccountRecords(
    local: CloudSyncAccountRecord?,
    remote: CloudSyncAccountRecord?
): CloudSyncAccountRecord? {
    val base = local ?: remote ?: return null
    val deletedAt = listOfNotNull(local?.deletedAtEpochMillis, remote?.deletedAtEpochMillis).maxOrNull()
    val token = newest(local?.token, remote?.token) { it.updatedAtEpochMillis }
        ?.takeIf { deletedAt == null || it.updatedAtEpochMillis > deletedAt }
    val snapshot = newest(local?.snapshot, remote?.snapshot) { it.updatedAtEpochMillis }
        ?.takeIf { deletedAt == null || it.updatedAtEpochMillis > deletedAt }
    if (token == null && snapshot == null && deletedAt == null) return null
    return CloudSyncAccountRecord(
        providerId = base.providerId,
        accountId = base.accountId,
        token = token,
        snapshot = snapshot,
        deletedAtEpochMillis = deletedAt
    )
}

private fun mergePreferences(
    local: CloudSyncPreferencesRecord,
    remote: CloudSyncPreferencesRecord
): CloudSyncPreferencesRecord {
    return CloudSyncPreferencesRecord(
        themeMode = newest(local.themeMode, remote.themeMode) { it.updatedAtEpochMillis },
        autoRefreshMinutes = newest(local.autoRefreshMinutes, remote.autoRefreshMinutes) { it.updatedAtEpochMillis },
        usageDisplayMode = newest(local.usageDisplayMode, remote.usageDisplayMode) { it.updatedAtEpochMillis },
        showProjectedUsage = newest(local.showProjectedUsage, remote.showProjectedUsage) { it.updatedAtEpochMillis },
        emailPrivacyMode = newest(local.emailPrivacyMode, remote.emailPrivacyMode) { it.updatedAtEpochMillis }
    )
}

private fun <T> newest(first: T?, second: T?, timestamp: (T) -> Long): T? {
    return when {
        first == null -> second
        second == null -> first
        timestamp(second) > timestamp(first) -> second
        else -> first
    }
}
