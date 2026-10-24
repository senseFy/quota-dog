package saien.quotadog

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saien.updater.UpdateConfiguration
import saien.updater.UpdateErrorCode
import saien.updater.UpdateException
import saien.updater.UpdateState
import saien.updater.Updater
import saien.updater.jvm.FileCheckpointStore
import saien.updater.jvm.HttpsUpdateTransport
import saien.updater.jvm.JcaCryptography
import saien.updater.jvm.MacOsInstaller

internal data class DesktopUpdateMetadata(
    val appId: String, val version: String, val sequence: Long, val channel: String,
    val baseUrl: String, val teamId: String, val keyId: String, val publicKey: String,
    val acceptanceDirectory: String,
) {
    companion object {
        fun load(): DesktopUpdateMetadata {
            val p = Properties().apply {
                checkNotNull(DesktopUpdater::class.java.getResourceAsStream("/quotadog-build.properties")).use(::load)
            }
            return DesktopUpdateMetadata(p.getProperty("appId"), p.getProperty("version"),
                p.getProperty("sequence").toLong(), p.getProperty("channel"), p.getProperty("baseUrl"),
                p.getProperty("teamId"), p.getProperty("keyId"), p.getProperty("publicKey"),
                p.getProperty("acceptanceDirectory", ""))
        }
    }
}

internal data class DesktopUpdateUiState(
    val message: String = "Preparing updates…",
    val canCheck: Boolean = false,
    val canDownload: Boolean = false,
    val canInstall: Boolean = false,
    val canCancel: Boolean = false,
    val canDiscard: Boolean = false,
    val canRecover: Boolean = false,
    val canSaveReceipt: Boolean = false,
    val progress: Float? = null,
    val notes: String? = null,
)

internal data class UpdateReceipt(val transactionId: String, val sequence: Long, val application: String)

/** A receipt is scoped to the installed path; never discard another installation's transaction. */
internal class UpdateReceiptStore(private val root: Path) {
    private val file = root.resolve("pending-installation")

    fun checkWritable() {
        // Exercise actual allocation/write/fsync before handing ownership to the helper.
        // Permissions alone do not catch a full disk or an unwritable receipt target.
        check(!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "An earlier update receipt still needs recovery"
        }
        val probe = Files.createTempFile(root, "receipt-probe-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            FileChannel.open(probe, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(ByteArray(16_384))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        } finally { Files.deleteIfExists(probe) }
    }

    fun read(): UpdateReceipt? {
        if (!Files.exists(file)) return null
        require(Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 16_384)
        val values = Files.readAllLines(file)
        require(values.size == 3)
        require(UUID.fromString(values[0]).toString() == values[0])
        return UpdateReceipt(values[0], values[1].toLong().also { require(it > 0) }, values[2])
    }

    fun write(receipt: UpdateReceipt) {
        require(UUID.fromString(receipt.transactionId).toString() == receipt.transactionId)
        require(receipt.sequence > 0 && '\n' !in receipt.application && '\r' !in receipt.application)
        val temporary = Files.createTempFile(root, "receipt-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            val bytes = "${receipt.transactionId}\n${receipt.sequence}\n${receipt.application}\n".encodeToByteArray()
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun clear() { Files.deleteIfExists(file) }
}

/** Owned by the application, not the window (which may disappear into the tray). */
internal class DesktopUpdater(
    val metadata: DesktopUpdateMetadata = DesktopUpdateMetadata.load(),
    private val dataDirectory: Path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "QuotaDog", "updater"),
    private val beforeReceiptWrite: () -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(DesktopUpdateUiState())
    val state = mutableState.asStateFlow()
    private var updater: Updater? = null
    private var transport: HttpsUpdateTransport? = null
    private lateinit var installer: MacOsInstaller
    private lateinit var application: Path
    private lateinit var receipts: UpdateReceiptStore
    private var exitGuard: UpdateExitGuard? = null
    private var operation: Job? = null
    private var working = false
    private var started = false
    private var recoveryRequired = true
    private var notice: String? = null
    private var exit: () -> Unit = {}

    /** Called after the host UI has started successfully. It never checks or downloads implicitly. */
    suspend fun start(onExit: () -> Unit) {
        if (started) return
        started = true
        working = true
        exit = onExit
        try {
            withContext(Dispatchers.IO) {
                require(System.getProperty("os.name").startsWith("Mac"))
                val executable = System.getProperty("jpackage.app-path")?.let(Path::of)
                    ?: error("Run an installed QuotaDog app to use updates.")
                application = executable.parent.parent.parent.toRealPath()
                val helper = application.resolve("Contents/Helpers/kmp-updater-helper")
                check(Files.isExecutable(helper)) { "Install a signed QuotaDog release to use updates." }
                val signature = ProcessBuilder("/usr/bin/codesign", "--display", "--verbose=4", application.toString())
                    .redirectErrorStream(true).start()
                check(signature.waitFor(10, TimeUnit.SECONDS)) { "Could not inspect this installation." }
                val identity = signature.inputStream.bufferedReader().readText()
                check(signature.exitValue() == 0 && identity.lineSequence().any { it == "TeamIdentifier=${metadata.teamId}" } &&
                    identity.contains("Authority=Developer ID Application:")) {
                    "Install a signed QuotaDog release to use updates."
                }
                check(Files.isWritable(application) && Files.isWritable(application.parent)) {
                    "Move QuotaDog to a writable Applications folder to use updates."
                }
                check(System.getProperty("os.version").substringBefore('.').toInt() >= 13) {
                    "Updates require macOS 13 or later."
                }
                Files.createDirectories(dataDirectory)
                check(!Files.isSymbolicLink(dataDirectory))
                Files.setPosixFilePermissions(dataDirectory, PosixFilePermissions.fromString("rwx------"))
                val checkpoints = FileCheckpointStore(dataDirectory.resolve("checkpoints"))
                receipts = UpdateReceiptStore(dataDirectory)
                exitGuard = UpdateExitGuard(
                    preflight = { withContext(Dispatchers.IO) { receipts.checkWritable() } },
                    persist = { receipt -> withContext(Dispatchers.IO) {
                        beforeReceiptWrite()
                        receipts.write(receipt)
                    } },
                )
                installer = MacOsInstaller(application, helper, metadata.appId, metadata.teamId,
                    metadata.sequence, dataDirectory.resolve("requests"))
                val host = java.net.URI(metadata.baseUrl).host
                val network = HttpsUpdateTransport(dataDirectory.resolve("downloads"), setOf(host))
                transport = network
                updater = Updater(UpdateConfiguration(metadata.appId, metadata.channel, metadata.sequence,
                    when (System.getProperty("os.arch")) {
                        "aarch64", "arm64" -> "macos-aarch64"
                        "x86_64", "amd64" -> "macos-x64"
                        else -> error("This processor is not supported.")
                    }, System.getProperty("os.version"), "${metadata.baseUrl}/feeds/${metadata.channel}.json"),
                    network, JcaCryptography(mapOf(metadata.keyId to metadata.publicKey)), checkpoints, network, installer)
            }
            scope.launch { updater!!.state.collect { render() } }
            recover()
            working = false
            render()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            transport?.close()
            mutableState.value = DesktopUpdateUiState(
                message = if (error is IllegalStateException) error.message ?: "Updates are unavailable."
                else "Updates are unavailable for this installation.")
        }
    }

    fun check() = action {
        updater!!.discard()
        updater!!.check()
    }

    fun download() = action { updater!!.download() }
    fun discard() = action { updater!!.discard() }
    fun cancel() { operation?.cancel() }

    fun install() = action {
        val ready = updater!!.state.value as? UpdateState.Ready ?: return@action
        exitGuard!!.handoff(accept = {
            val accepted = updater!!.install()
            UpdateReceipt(accepted.transactionId, ready.offer.release.sequence, application.toString())
        }, onExit = exit)
    }

    fun saveReceiptAndQuit() = action { exitGuard!!.requestExit(exit) }

    fun retryRecovery() = action(allowRecovery = true) { recover() }

    private suspend fun recover() {
        try {
            withContext(Dispatchers.IO) {
                val receipt = receipts.read() ?: return@withContext
                check(receipt.application == application.toString())
                val transaction = application.parent.resolve(".kmp-update-${metadata.appId}-${receipt.transactionId}")
                if (metadata.sequence == receipt.sequence && !Files.exists(transaction)) {
                    // Confirmation may have succeeded before receipt deletion was interrupted.
                    receipts.clear()
                    return@withContext
                }
                when (installer.installationStatus(receipt.transactionId)) {
                    "installed" -> {
                        check(metadata.sequence == receipt.sequence)
                        installer.confirmInstallation(receipt.transactionId)
                        receipts.clear()
                        notice = "Updated to ${metadata.version}."
                    }
                    "original-retained" -> {
                        check(metadata.sequence < receipt.sequence)
                        val log = Files.createTempFile(dataDirectory, "recovery-", ".log")
                        try {
                            val process = ProcessBuilder(application.resolve("Contents/Helpers/kmp-updater-helper").toString(),
                                "discard", transaction.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                            if (!process.waitFor(130, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                                error("Recovery timed out")
                            }
                            check(process.exitValue() == 0)
                            receipts.clear()
                            notice = "The previous update did not finish. You can try again."
                        } finally { Files.deleteIfExists(log) }
                    }
                    else -> error("Unrecognized installation status")
                }
            }
            recoveryRequired = false
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recoveryRequired = true
            notice = "The previous update needs attention. Retry recovery before updating again."
        }
        render()
    }

    private fun action(allowRecovery: Boolean = false, block: suspend () -> Unit) {
        if (working || updater == null || (recoveryRequired && !allowRecovery)) return
        working = true
        notice = null
        operation = scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                notice = if (exitGuard?.needsReceipt == true) {
                    RECEIPT_FAILURE_MESSAGE
                } else errorMessage((error as? UpdateException)?.code)
            } finally {
                working = false
                render()
            }
        }
        render()
    }

    private fun render() {
        val value = updater?.state?.value ?: return
        val offer = when (value) {
            is UpdateState.Available -> value.offer
            is UpdateState.Ready -> value.offer
            else -> null
        }
        val message = notice ?: when (value) {
            UpdateState.Idle -> "Check for a newer version of QuotaDog."
            UpdateState.Checking -> "Checking for updates…"
            UpdateState.UpToDate -> "You’re up to date."
            UpdateState.NoCompatibleUpdate -> "No update is available for this Mac."
            is UpdateState.Available -> "QuotaDog ${value.offer.release.version} is available."
            is UpdateState.Downloading -> "Downloading update…"
            is UpdateState.Preparing -> "Verifying and preparing update…"
            is UpdateState.Ready -> "Ready to install ${value.offer.release.version}. QuotaDog will close and reopen."
            is UpdateState.HandingOff -> "Starting installation…"
            is UpdateState.AwaitingExit -> "Restarting QuotaDog…"
            is UpdateState.Failed -> errorMessage(value.code)
        }
        mutableState.value = DesktopUpdateUiState(message = message,
            canCheck = !working && !recoveryRequired && (value is UpdateState.Idle || value is UpdateState.UpToDate ||
                value is UpdateState.NoCompatibleUpdate || value is UpdateState.Failed),
            canDownload = !working && !recoveryRequired && value is UpdateState.Available,
            canInstall = !working && !recoveryRequired && value is UpdateState.Ready,
            canCancel = working && (value is UpdateState.Checking || value is UpdateState.Downloading || value is UpdateState.Preparing),
            canDiscard = !working && !recoveryRequired && (value is UpdateState.Ready || value is UpdateState.Available),
            canRecover = !working && recoveryRequired,
            canSaveReceipt = !working && exitGuard?.needsReceipt == true,
            progress = (value as? UpdateState.Downloading)?.let { (it.bytes.toDouble() / it.offer.artifact.size).toFloat() },
            notes = offer?.release?.notes?.let { it["en"] ?: it.values.firstOrNull() })
    }

    /** Ordinary quit cancels transfers and releases staged files before closing the process. */
    fun quit(onExit: () -> Unit) {
        scope.launch {
            operation?.cancelAndJoin()
            try {
                if (exitGuard?.needsReceipt == true) {
                    exitGuard!!.requestExit(onExit)
                } else {
                    if (!recoveryRequired && updater?.state?.value !is UpdateState.AwaitingExit) {
                        runCatching { updater?.discard() }
                    }
                    onExit()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice = RECEIPT_FAILURE_MESSAGE
                render()
            }
        }
    }

    override fun close() { scope.cancel(); transport?.close() }
}

private const val RECEIPT_FAILURE_MESSAGE =
    "Could not save update recovery information. Quit is paused. Free disk space or restore folder access, then save and quit. If QuotaDog does not reopen, open it again to recover."

private fun errorMessage(code: UpdateErrorCode?): String = when (code) {
    UpdateErrorCode.NETWORK -> "Could not reach the update service. Try again later."
    UpdateErrorCode.EXPIRED_MANIFEST -> "Update information has expired. Try again later."
    UpdateErrorCode.CLOCK_ROLLBACK -> "Check your Mac’s date and time, then try again."
    UpdateErrorCode.STORAGE -> "Could not save the update. Check free space and folder permissions."
    UpdateErrorCode.ARTIFACT_INTEGRITY, UpdateErrorCode.UNTRUSTED_SIGNATURE,
    UpdateErrorCode.INVALID_MANIFEST, UpdateErrorCode.WRONG_SCOPE,
    UpdateErrorCode.MANIFEST_ROLLBACK, UpdateErrorCode.MANIFEST_CONFLICT -> "Update verification failed. Your installed app has not been replaced."
    UpdateErrorCode.UNSUPPORTED_INSTALLATION -> "This installation cannot update itself. Install the latest signed DMG."
    else -> "The update could not finish. Check again to retry."
}
