package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudSyncCoordinatorTest {
    @Test
    fun manualOrderChangedDuringPullIsPreservedAndUploaded() = runBlocking {
        assertManualOrderSurvivesPendingPull(conflict = false, remoteMissing = false)
    }

    @Test
    fun manualOrderChangedWhileCreatingRemoteFileIsUploaded() = runBlocking {
        assertManualOrderSurvivesPendingPull(conflict = false, remoteMissing = true)
    }

    @Test
    fun conflictRetryIncludesManualOrderChangedDuringPull() = runBlocking {
        assertManualOrderSurvivesPendingPull(conflict = true, remoteMissing = false)
    }

    @Test
    fun conflictRetryWithDeletedRemoteIncludesLatestManualOrder() = runBlocking {
        assertManualOrderSurvivesPendingPull(conflict = true, remoteMissing = true)
    }

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

    private suspend fun assertManualOrderSurvivesPendingPull(conflict: Boolean, remoteMissing: Boolean) {
        withTimeout(10_000) {
            val passphrase = "review-test-passphrase"
            val preferences = AppPreferences(MapSettings())
            val backend = PausedPullBackend(conflict, remoteMissing)
            val coordinator = CloudSyncCoordinator(
                CloudSyncLocalRepository(
                    tokenStore = SettingsTokenStore(MapSettings()),
                    usageSnapshotStore = SettingsUsageSnapshotStore(MapSettings()),
                    preferences = preferences,
                    settings = MapSettings(),
                ),
                backend,
            )
            try {
                coordinator.startUnlock(passphrase)
                coordinator.state.first { it.status == CloudSyncStatus.Connected }
                val original = listOf(
                    AccountKey(ProviderId.CODEX, "a@example.com"),
                    AccountKey(ProviderId.GROK, "b@example.com"),
                )
                preferences.setAccountManualOrder(original)
                preferences.setAccountSortMode(AccountSortMode.Manual)
                coordinator.startPushLocalChanges()
                backend.pendingPull.await()

                // The drag saves locally; its next push waits until the pointer is released.
                preferences.setAccountManualOrder(original.reversed())
                backend.releasePull.complete(Unit)
                coordinator.state.first { it.status == CloudSyncStatus.Connected }

                assertEquals(original.reversed(), preferences.accountManualOrder.value)
                val uploaded = CloudSyncCrypto.decryptDocument(backend.remote!!.content, passphrase)
                assertEquals(original.reversed(), decodeAccountOrder(uploaded.preferences.accountManualOrder?.value))
            } finally {
                coordinator.cancelCurrentOperation()
            }
        }
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

    private class PausedPullBackend(
        private val conflict: Boolean,
        private val remoteMissing: Boolean,
    ) : CloudSyncRemoteBackend {
        val pendingPull = CompletableDeferred<Unit>()
        val releasePull = CompletableDeferred<Unit>()
        var remote: DropboxRemoteFile? = null
            private set
        private var pulls = 0
        private var pushes = 0

        override fun hasConnection(): Boolean = true
        override fun storedRev(): String? = remote?.rev
        override suspend fun connect(): String = "test-account"

        override suspend fun pull(): DropboxRemoteFile? {
            pulls++
            if (pulls == if (conflict) 3 else 2) {
                pendingPull.complete(Unit)
                releasePull.await()
                if (remoteMissing) return null
            }
            return remote
        }

        override suspend fun push(content: String, rev: String?): DropboxRemoteFile {
            pushes++
            if (conflict && pushes == 2) throw DropboxSyncConflictException()
            return DropboxRemoteFile(content, "rev-$pushes").also { remote = it }
        }

        override fun disconnect() = Unit
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
