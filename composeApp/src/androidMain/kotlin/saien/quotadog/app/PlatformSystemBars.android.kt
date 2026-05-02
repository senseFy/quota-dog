package saien.quotadog.app

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun ApplyPlatformSystemBars(darkAppearance: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // Edge-to-edge guarantee - Activity may not have called this yet on older devices.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Fully transparent so the Compose background colour is what the user sees behind
        // the system bars.
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.Transparent.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.Transparent.toArgb()
        val controller = WindowCompat.getInsetsController(window, view)
        // appearanceLight* = true asks the system to render bars with *dark* icons (because
        // the background is light). So when our theme is dark we want it to be false.
        controller.isAppearanceLightStatusBars = !darkAppearance
        controller.isAppearanceLightNavigationBars = !darkAppearance
    }
}
