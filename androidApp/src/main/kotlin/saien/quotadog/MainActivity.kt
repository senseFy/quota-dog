package saien.quotadog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import saien.quotadog.app.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeAndroidPlatform(applicationContext)
        // Edge-to-edge: let Compose draw under the status bar and gesture/nav bar.
        // ApplyPlatformSystemBars() in App.kt handles per-theme icon tint via the
        // WindowInsetsControllerCompat returned from this same window.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            App()
        }
    }
}
