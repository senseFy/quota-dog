package saien.quotadog

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import saien.quotadog.app.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "QuotaDog"
    ) {
        App()
    }
}
