package saien.quotadog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class CloudSyncCoordinator(
    private val localRepository: CloudSyncLocalRepository,
    private val remoteBackend: CloudSyncRemoteBackend = DropboxSyncBackend()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()
    private var passphrase: String? = null
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CloudSyncUiState> = _state

    fun startRestoreSession() {
        _state.value = initialState()
    }

    fun startConnectDropbox(syncPassphrase: String, onComplete: () -> Unit = {}) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val normalizedPassphrase = syncPassphrase.trim()
            if (normalizedPassphrase.length < 8) {
                setError("Use a sync passphrase with at least 8 characters")
                return@launch
            }
            passphrase = normalizedPassphrase
            _state.value = CloudSyncUiState(
                status = CloudSyncStatus.Connecting,
                connected = false,
                busy = true,
                message = "Connecting Dropbox..."
            )
            try {
                remoteBackend.connect()
                syncOnce(onComplete)
            } catch (_: CancellationException) {
                setCancelled()
            } catch (error: Throwable) {
                setError(safeUserMessage(error, "Dropbox sync connection failed"))
            } finally {
                if (activeJob === coroutineContext[Job]) activeJob = null
            }
        }
    }

    fun startUnlock(syncPassphrase: String, onComplete: () -> Unit = {}) {
        val normalizedPassphrase = syncPassphrase.trim()
        if (normalizedPassphrase.length < 8) {
            setError("Use a sync passphrase with at least 8 characters")
            return
        }
        passphrase = normalizedPassphrase
        startSyncNow(onComplete)
    }

    fun startSyncNow(onComplete: () -> Unit = {}) {
        activeJob?.cancel()
        activeJob = scope.launch {
            syncOnce(onComplete)
            if (activeJob === coroutineContext[Job]) activeJob = null
        }
    }

    fun startResetCloudSync(syncPassphrase: String, onComplete: () -> Unit = {}) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val normalizedPassphrase = syncPassphrase.trim()
            if (normalizedPassphrase.length < 8) {
                setError("Use a sync passphrase with at least 8 characters")
                return@launch
            }
            if (!remoteBackend.hasConnection()) {
                _state.value = CloudSyncUiState(
                    status = CloudSyncStatus.Disconnected,
                    connected = false,
                    busy = false,
                    message = "Connect Dropbox before resetting the sync file"
                )
                return@launch
            }
            passphrase = normalizedPassphrase
            _state.value = _state.value.copy(
                status = CloudSyncStatus.Syncing,
                connected = true,
                busy = true,
                message = "Resetting Dropbox sync file..."
            )
            try {
                val local = localRepository.exportDocument()
                    .copy(updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds())
                val remote = remoteBackend.pull()
                val encrypted = CloudSyncCrypto.encryptDocument(local, normalizedPassphrase)
                remoteBackend.push(encrypted, remote?.rev ?: remoteBackend.storedRev())
                _state.value = CloudSyncUiState(
                    status = CloudSyncStatus.Connected,
                    connected = true,
                    busy = false,
                    lastSyncedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    message = "Dropbox sync file reset from this device"
                )
                onComplete()
            } catch (_: CancellationException) {
                setCancelled()
            } catch (_: DropboxSyncConflictException) {
                setError("Dropbox sync file changed while resetting. Please try again.")
            } catch (error: Throwable) {
                setError(safeUserMessage(error, "Dropbox sync reset failed"))
            } finally {
                if (activeJob === coroutineContext[Job]) activeJob = null
            }
        }
    }

    fun startPushLocalChanges() {
        if (!remoteBackend.hasConnection() || passphrase == null) return
        activeJob?.cancel()
        activeJob = scope.launch {
            syncOnce()
            if (activeJob === coroutineContext[Job]) activeJob = null
        }
    }

    fun recordAccountDeleted(accountKey: AccountKey) {
        localRepository.recordAccountDeleted(accountKey)
        startPushLocalChanges()
    }

    fun disconnect() {
        activeJob?.cancel()
        activeJob = null
        remoteBackend.disconnect()
        passphrase = null
        _state.value = CloudSyncUiState(
            status = CloudSyncStatus.Disconnected,
            connected = false,
            busy = false,
            message = "Dropbox sync disconnected on this device"
        )
    }

    fun cancelCurrentOperation() {
        activeJob?.cancel()
        activeJob = null
        setCancelled()
    }

    private suspend fun syncOnce(onComplete: () -> Unit = {}) {
        syncMutex.withLock {
            if (!remoteBackend.hasConnection()) {
                _state.value = CloudSyncUiState(
                    status = CloudSyncStatus.Disconnected,
                    connected = false,
                    busy = false,
                    message = "Dropbox sync is not connected"
                )
                return
            }
            val activePassphrase = passphrase
            if (activePassphrase == null) {
                _state.value = CloudSyncUiState(
                    status = CloudSyncStatus.Locked,
                    connected = true,
                    busy = false,
                    message = "Enter your sync passphrase to unlock Dropbox sync"
                )
                return
            }
            _state.value = _state.value.copy(
                status = CloudSyncStatus.Syncing,
                connected = true,
                busy = true,
                message = "Syncing with Dropbox..."
            )
            try {
                val local = localRepository.exportDocument()
                val remote = remoteBackend.pull()
                val merged = if (remote == null) {
                    local
                } else {
                    mergeCloudSyncDocuments(
                        local = local,
                        remote = CloudSyncCrypto.decryptDocument(remote.content, activePassphrase)
                    )
                }
                localRepository.applyDocument(merged)
                pushMergedDocument(merged, activePassphrase, remote?.rev ?: remoteBackend.storedRev())
                _state.value = CloudSyncUiState(
                    status = CloudSyncStatus.Connected,
                    connected = true,
                    busy = false,
                    lastSyncedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    message = "Dropbox sync is up to date"
                )
                onComplete()
            } catch (_: CancellationException) {
                setCancelled()
            } catch (error: CloudSyncCryptoException) {
                passphrase = null
                _state.value = CloudSyncUiState(
                    status = CloudSyncStatus.Locked,
                    connected = true,
                    busy = false,
                    message = error.message ?: "Sync passphrase did not match"
                )
            } catch (error: Throwable) {
                setError(safeUserMessage(error, "Dropbox sync failed"))
            }
        }
    }

    private suspend fun pushMergedDocument(
        merged: CloudSyncDocumentV1,
        activePassphrase: String,
        rev: String?
    ) {
        val toUpload = merged.copy(updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds())
        val encrypted = CloudSyncCrypto.encryptDocument(toUpload, activePassphrase)
        try {
            remoteBackend.push(encrypted, rev)
        } catch (_: DropboxSyncConflictException) {
            val latest = remoteBackend.pull()
                ?: return remoteBackend.push(encrypted, null).let { Unit }
            val latestDocument = CloudSyncCrypto.decryptDocument(latest.content, activePassphrase)
            val retryMerged = mergeCloudSyncDocuments(toUpload, latestDocument)
            localRepository.applyDocument(retryMerged)
            val retryEncrypted = CloudSyncCrypto.encryptDocument(
                retryMerged.copy(updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds()),
                activePassphrase
            )
            remoteBackend.push(retryEncrypted, latest.rev)
        }
    }

    private fun initialState(): CloudSyncUiState {
        return if (remoteBackend.hasConnection()) {
            CloudSyncUiState(
                status = CloudSyncStatus.Locked,
                connected = true,
                busy = false,
                message = "Dropbox sync is connected. Enter your sync passphrase to unlock it."
            )
        } else {
            CloudSyncUiState(status = CloudSyncStatus.Disconnected)
        }
    }

    private fun setError(message: String) {
        _state.value = CloudSyncUiState(
            status = CloudSyncStatus.Error,
            connected = remoteBackend.hasConnection(),
            busy = false,
            lastSyncedAtEpochMillis = _state.value.lastSyncedAtEpochMillis,
            message = message
        )
    }

    private fun setCancelled() {
        val connected = remoteBackend.hasConnection() || _state.value.connected
        _state.value = if (connected) {
            CloudSyncUiState(
                status = if (passphrase == null) CloudSyncStatus.Locked else CloudSyncStatus.Connected,
                connected = true,
                busy = false,
                lastSyncedAtEpochMillis = _state.value.lastSyncedAtEpochMillis,
                message = "Dropbox sync cancelled"
            )
        } else {
            passphrase = null
            CloudSyncUiState(
                status = CloudSyncStatus.Disconnected,
                connected = false,
                busy = false,
                message = "Dropbox connection cancelled"
            )
        }
    }
}
