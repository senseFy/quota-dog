package saien.quotadog.app

import androidx.compose.runtime.Composable

/**
 * Configure the platform's status / nav bar appearance to match the current Compose theme.
 *
 * @param darkAppearance true when the underlying Compose background is *dark* (so system
 *  bars need light-colored content) - i.e. equivalent to the app's "isDarkTheme" flag.
 *
 * No-op on desktop. On Android updates `WindowInsetsControllerCompat`; on iOS forwards to a
 * registered Swift handler that mutates the key window's `overrideUserInterfaceStyle`.
 */
@Composable
expect fun ApplyPlatformSystemBars(darkAppearance: Boolean)
