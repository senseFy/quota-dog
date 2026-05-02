package saien.quotadog

import androidx.compose.ui.window.ComposeUIViewController
import saien.quotadog.app.App

private val iosQuotaDogStore = QuotaDogStore()

fun MainViewController() = ComposeUIViewController {
    App(store = iosQuotaDogStore)
}
