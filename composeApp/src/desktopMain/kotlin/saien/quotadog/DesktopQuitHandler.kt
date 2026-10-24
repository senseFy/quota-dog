package saien.quotadog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.QuitHandler

internal fun guardedQuitHandler(onQuit: () -> Unit) = QuitHandler { _, response ->
    // Cancel the OS's direct termination path; the updater decides when exit is safe.
    response.cancelQuit()
    EventQueue.invokeLater(onQuit)
}

/** Includes Cmd+Q and Dock/system Quit, not just the app's tray button. */
@Composable
internal fun DesktopQuitHandler(onQuit: () -> Unit) {
    val current = rememberUpdatedState(onQuit)
    DisposableEffect(Unit) {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop()
            .takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) } else null
        desktop?.setQuitHandler(guardedQuitHandler { current.value() })
        onDispose { desktop?.setQuitHandler(null) }
    }
}
