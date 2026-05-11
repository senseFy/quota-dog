package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { System, Light, Dark }

enum class UsageDisplayMode { Used, Remaining }

enum class EmailPrivacyMode { Visible, Masked }

/**
 * Persistent, observable user preferences.
 *
 * Backed by [Settings] (per-platform: SharedPreferences / NSUserDefaults / java.util.prefs).
 * UI consumes [themeMode] / [autoRefreshMinutes] as [StateFlow] and writes through the
 * setter methods, which both persist and emit synchronously.
 */
class AppPreferences(
    private val settings: Settings = createPreferenceSettings(),
) {
    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _autoRefreshMinutes = MutableStateFlow(loadAutoRefreshMinutes())

    /** 0 means disabled. Otherwise interval in minutes. */
    val autoRefreshMinutes: StateFlow<Int> = _autoRefreshMinutes

    private val _usageDisplayMode = MutableStateFlow(loadUsageDisplayMode())
    val usageDisplayMode: StateFlow<UsageDisplayMode> = _usageDisplayMode

    private val _showProjectedUsage = MutableStateFlow(loadShowProjectedUsage())
    val showProjectedUsage: StateFlow<Boolean> = _showProjectedUsage

    private val _emailPrivacyMode = MutableStateFlow(loadEmailPrivacyMode())
    val emailPrivacyMode: StateFlow<EmailPrivacyMode> = _emailPrivacyMode

    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME, mode.name)
        _themeMode.value = mode
    }

    fun setAutoRefreshMinutes(minutes: Int) {
        val sanitized = if (minutes < 0) 0 else minutes
        settings.putInt(KEY_AUTO_REFRESH, sanitized)
        _autoRefreshMinutes.value = sanitized
    }

    fun setUsageDisplayMode(mode: UsageDisplayMode) {
        settings.putString(KEY_USAGE_DISPLAY_MODE, mode.name)
        _usageDisplayMode.value = mode
    }

    fun setShowProjectedUsage(show: Boolean) {
        settings.putBoolean(KEY_SHOW_PROJECTED_USAGE, show)
        _showProjectedUsage.value = show
    }

    fun setEmailPrivacyMode(mode: EmailPrivacyMode) {
        settings.putString(KEY_EMAIL_PRIVACY_MODE, mode.name)
        _emailPrivacyMode.value = mode
    }

    private fun loadThemeMode(): ThemeMode {
        val raw = settings.getStringOrNull(KEY_THEME) ?: return ThemeMode.System
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.System)
    }

    private fun loadAutoRefreshMinutes(): Int {
        return settings.getIntOrNull(KEY_AUTO_REFRESH) ?: 0
    }

    private fun loadUsageDisplayMode(): UsageDisplayMode {
        val raw = settings.getStringOrNull(KEY_USAGE_DISPLAY_MODE) ?: return UsageDisplayMode.Used
        return runCatching { UsageDisplayMode.valueOf(raw) }.getOrDefault(UsageDisplayMode.Used)
    }

    private fun loadShowProjectedUsage(): Boolean {
        return settings.getBooleanOrNull(KEY_SHOW_PROJECTED_USAGE) ?: false
    }

    private fun loadEmailPrivacyMode(): EmailPrivacyMode {
        val raw = settings.getStringOrNull(KEY_EMAIL_PRIVACY_MODE) ?: return EmailPrivacyMode.Visible
        return runCatching { EmailPrivacyMode.valueOf(raw) }.getOrDefault(EmailPrivacyMode.Visible)
    }

    private companion object {
        const val KEY_THEME = "pref_theme_mode"
        const val KEY_AUTO_REFRESH = "pref_auto_refresh_minutes"
        const val KEY_USAGE_DISPLAY_MODE = "pref_usage_display_mode"
        const val KEY_SHOW_PROJECTED_USAGE = "pref_show_projected_usage"
        const val KEY_EMAIL_PRIVACY_MODE = "pref_email_privacy_mode"
    }
}
