package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudSyncCoordinatorTest {
    @Test
    fun revConflictPullsLatestAndMergesBeforeRetry() = runBlocking {
        val passphrase = "correct horse battery staple"
        val tokenStore = SettingsTokenStore(MapSettings())
        val usageStore = SettingsUsageSnapshotStore(MapSettings())
        val preferences = AppPreferences(MapSettings())
        val localRepository = CloudSyncLocalRepository(
            tokenStore = tokenStore,
            usageSnapshotStore = usageStore,
            preferences = preferences,
            settings = MapSettings()
        )
        val accountKey = AccountKey(ProviderId.CODEX, "user@example.com")
        tokenStore.importTokenForSync(accountKey, token("local"), updatedAtEpochMillis = 200)

        val initialRemote = encryptedDocument(
            passphrase = passphrase,
            accessToken = "remote-old",
            updatedAtEpochMillis = 100,
            rev = "rev-1"
        )
        val conflictRemote = encryptedDocument(
            passphrase = passphrase,
            accessToken = "remote-new",
            updatedAtEpochMillis = 300,
            rev = "rev-2"
        )
        val backend = FakeConflictBackend(initialRemote, conflictRemote)
        val coordinator = CloudSyncCoordinator(localRepository, backend)

        coordinator.startUnlock(passphrase)
        coordinator.awaitConnected()

        assertEquals("remote-new", tokenStore.load(accountKey)?.accessToken)
        assertEquals(2, backend.pushAttempts)
    }

    private fun encryptedDocument(
        passphrase: String,
        accessToken: String,
        updatedAtEpochMillis: Long,
        rev: String
    ): DropboxRemoteFile {
        val document = CloudSyncDocumentV1(
            deviceId = "remote",
            updatedAtEpochMillis = updatedAtEpochMillis,
            accounts = listOf(
                CloudSyncAccountRecord(
                    providerId = ProviderId.CODEX,
                    accountId = "user@example.com",
                    token = CloudSyncTokenValue(token(accessToken), updatedAtEpochMillis)
                )
            )
        )
        return DropboxRemoteFile(
            content = CloudSyncCrypto.encryptDocument(document, passphrase, iterations = 2),
            rev = rev
        )
    }

    private fun token(accessToken: String): OAuthTokenBundle {
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = "refresh-$accessToken",
            email = "user@example.com",
            expiresAtEpochMillis = Long.MAX_VALUE
        )
    }

    private suspend fun CloudSyncCoordinator.awaitConnected() {
        withTimeout(5_000) {
            while (state.value.status != CloudSyncStatus.Connected) {
                delay(20)
            }
        }
    }

    private class FakeConflictBackend(
        initialRemote: DropboxRemoteFile,
        private val conflictRemote: DropboxRemoteFile
    ) : CloudSyncRemoteBackend {
        private var remote = initialRemote
        var pushAttempts = 0

        override fun hasConnection(): Boolean = true
        override fun storedRev(): String? = remote.rev
        override suspend fun connect(): String = "dropbox-account"
        override suspend fun pull(): DropboxRemoteFile? = remote

        override suspend fun push(content: String, rev: String?): DropboxRemoteFile {
            pushAttempts += 1
            if (pushAttempts == 1) {
                remote = conflictRemote
                throw DropboxSyncConflictException()
            }
            remote = DropboxRemoteFile(content, "rev-${pushAttempts + 1}")
            return remote
        }

        override fun disconnect() = Unit
    }
}
