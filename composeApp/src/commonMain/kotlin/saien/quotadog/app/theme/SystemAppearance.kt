package saien.quotadog.app.theme

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual invalidation for System-mode appearance.
 *
 * Compose Desktop before 1.12 does not recompose when the OS theme changes.
 * Call [request] on window / menu-bar resume so [rememberSystemDarkTheme] re-queries
 * the live system value.
 */
object SystemThemeRefresh {
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun request() {
        _generation.value += 1
    }
}

/** Live OS dark-appearance flag; Desktop re-queries when [SystemThemeRefresh] bumps. */
@Composable
expect fun rememberSystemDarkTheme(): Boolean
