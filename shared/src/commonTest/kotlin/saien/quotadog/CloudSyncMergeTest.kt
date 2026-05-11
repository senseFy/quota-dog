package saien.quotadog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudSyncMergeTest {
    @Test
    fun tombstonePreventsOlderTokenFromReturning() {
        val local = CloudSyncDocumentV1(
            deviceId = "local",
            updatedAtEpochMillis = 100,
            accounts = listOf(
                CloudSyncAccountRecord(
                    providerId = ProviderId.CLAUDE_CODE,
                    accountId = "user@example.com",
                    deletedAtEpochMillis = 200
                )
            )
        )
        val remote = CloudSyncDocumentV1(
            deviceId = "remote",
            updatedAtEpochMillis = 90,
            accounts = listOf(
                CloudSyncAccountRecord(
                    providerId = ProviderId.CLAUDE_CODE,
                    accountId = "user@example.com",
                    token = CloudSyncTokenValue(token("old"), updatedAtEpochMillis = 150)
                )
            )
        )

        val merged = mergeCloudSyncDocuments(local, remote, nowEpochMillis = 250)
        val account = merged.accounts.single()

        assertEquals(200, account.deletedAtEpochMillis)
        assertNull(account.token)
    }

    @Test
    fun newerTokenWinsOverOlderTombstone() {
        val local = CloudSyncDocumentV1(
            deviceId = "local",
            updatedAtEpochMillis = 100,
            accounts = listOf(
                CloudSyncAccountRecord(
                    providerId = ProviderId.CODEX,
                    accountId = "user@example.com",
                    deletedAtEpochMillis = 100
                )
            )
        )
        val remote = CloudSyncDocumentV1(
            deviceId = "remote",
            updatedAtEpochMillis = 200,
            accounts = listOf(
                CloudSyncAccountRecord(
                    providerId = ProviderId.CODEX,
                    accountId = "user@example.com",
                    token = CloudSyncTokenValue(token("new"), updatedAtEpochMillis = 300)
                )
            )
        )

        val merged = mergeCloudSyncDocuments(local, remote, nowEpochMillis = 350)
        val account = merged.accounts.single()

        assertEquals("new", account.token?.value?.accessToken)
        assertEquals(100, account.deletedAtEpochMillis)
    }

    @Test
    fun mergesPreferencesPerField() {
        val local = CloudSyncDocumentV1(
            deviceId = "local",
            updatedAtEpochMillis = 100,
            preferences = CloudSyncPreferencesRecord(
                themeMode = CloudSyncStringPreference("Dark", updatedAtEpochMillis = 200),
                autoRefreshMinutes = CloudSyncIntPreference(5, updatedAtEpochMillis = 100)
            )
        )
        val remote = CloudSyncDocumentV1(
            deviceId = "remote",
            updatedAtEpochMillis = 100,
            preferences = CloudSyncPreferencesRecord(
                themeMode = CloudSyncStringPreference("Light", updatedAtEpochMillis = 150),
                autoRefreshMinutes = CloudSyncIntPreference(30, updatedAtEpochMillis = 300)
            )
        )

        val merged = mergeCloudSyncDocuments(local, remote, nowEpochMillis = 400)

        assertEquals("Dark", merged.preferences.themeMode?.value)
        assertEquals(30, merged.preferences.autoRefreshMinutes?.value)
    }

    private fun token(accessToken: String): OAuthTokenBundle {
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = "refresh-$accessToken",
            expiresAtEpochMillis = Long.MAX_VALUE
        )
    }
}
