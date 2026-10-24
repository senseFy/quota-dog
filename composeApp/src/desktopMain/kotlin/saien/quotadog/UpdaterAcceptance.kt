package saien.quotadog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import saien.quotadog.app.theme.QuotaDogTheme

/** Build-time-only acceptance entry point. Production metadata cannot activate it. */
fun main() = application {
    val metadata = remember { DesktopUpdateMetadata.load() }
    check(metadata.appId == "saien.quotadog.acceptance" && metadata.acceptanceDirectory.isNotEmpty())
    val root = Path.of(metadata.acceptanceDirectory).toRealPath()
    val app = Path.of(checkNotNull(System.getProperty("jpackage.app-path"))).parent.parent.parent.toRealPath()
    check(app.startsWith(root) && Files.readString(root.resolve(".quotadog-updater-acceptance")) == "v1\n")
    val directory = app.parent
    val data = directory.resolve("data")
    val updater = remember { DesktopUpdater(metadata, data, beforeReceiptWrite = {
        if (Files.exists(directory.resolve("fail-receipt"))) {
            throw java.io.IOException("Acceptance: receipt storage unavailable after handoff")
        }
    }) }
    DisposableEffect(updater) { onDispose { updater.close() } }
    DesktopQuitHandler { updater.quit(::exitApplication) }
    fun record(event: String) {
        Files.writeString(directory.resolve("events.log"), "${metadata.sequence} ${ProcessHandle.current().pid()} $event\n", CREATE, APPEND)
    }
    Window(onCloseRequest = { updater.quit(::exitApplication) }, title = "QuotaDog Update Acceptance",
        state = rememberWindowState(width = 600.dp, height = 360.dp)) {
        QuotaDogTheme(darkTheme = true) {
            Column(Modifier.fillMaxSize().padding(32.dp)) { DesktopUpdateSettings(updater) }
        }
    }
    LaunchedEffect(Unit) {
        record("boot")
        try {
            withTimeout(15 * 60_000L) {
                updater.start { record("normal-exit"); exitApplication() }
                check(updater.state.value.canCheck) { updater.state.value.message }
                check(Files.readString(data.resolve("user-data.txt")) == "preserve-this-data\n")
                if (metadata.sequence == 2L) {
                    check(!Files.exists(data.resolve("pending-installation")))
                    // The helper deliberately retains its advisory lock file. Only
                    // transaction directories (including the old app) must disappear.
                    check(Files.list(directory).use { paths -> paths.noneMatch {
                        Files.isDirectory(it) && it.fileName.toString().startsWith(".kmp-update-")
                    } })
                    record("confirmed-data-preserved")
                    Files.writeString(directory.resolve("result.txt"), "success\n")
                    delay(1000)
                    exitApplication()
                } else {
                    check(metadata.sequence == 1L)
                    val scenario = Files.readString(directory.resolve("scenario.txt")).trim()
                    updater.check()
                    val checked = updater.state.first { it.canDownload || it.canCheck }
                    if (scenario == "bad-signature") {
                        check(checked.canCheck && checked.message.startsWith("Update verification failed"))
                        Files.writeString(directory.resolve("result.txt"), "rejected-signature\n")
                        exitApplication()
                    } else {
                        check(checked.canDownload) { checked.message }
                        record("checked")
                        updater.download()
                        val downloaded = updater.state.first { it.canInstall || it.canCheck }
                        if (scenario == "bad-artifact") {
                            check(downloaded.canCheck && downloaded.message.startsWith("Update verification failed"))
                            check(Files.list(data.resolve("downloads")).use { !it.findAny().isPresent })
                            Files.writeString(directory.resolve("result.txt"), "rejected-artifact\n")
                            exitApplication()
                        } else {
                            check(scenario in setOf("success", "receipt-failure") && downloaded.canInstall) { downloaded.message }
                            record("prepared")
                            if (scenario == "receipt-failure") Files.writeString(directory.resolve("fail-receipt"), "fail\n")
                            updater.install()
                            if (scenario == "receipt-failure") {
                                updater.state.first { it.canSaveReceipt }
                                record("receipt-save-blocked")
                                var quitInvoked = false
                                updater.quit { quitInvoked = true; exitApplication() }
                                delay(1000)
                                check(!quitInvoked && updater.state.value.canSaveReceipt)
                                check(!Files.exists(data.resolve("pending-installation")))
                                record("quit-blocked")
                                Files.delete(directory.resolve("fail-receipt"))
                                updater.saveReceiptAndQuit()
                            }
                            awaitCancellation()
                        }
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Files.writeString(directory.resolve("result.txt"), "failure: ${error.message}\n")
            exitApplication()
        }
    }
}
