package saien.quotadog.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

@Composable
actual fun rememberSystemDarkTheme(): Boolean {
    val generation by SystemThemeRefresh.generation.collectAsState()
    return remember(generation) {
        currentSystemTheme == SystemTheme.DARK
    }
}
