package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock

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
        settings.putLong(updatedAtKey(KEY_THEME), nowMillis())
        _themeMode.value = mode
    }

    fun setAutoRefreshMinutes(minutes: Int) {
        val sanitized = if (minutes < 0) 0 else minutes
        settings.putInt(KEY_AUTO_REFRESH, sanitized)
        settings.putLong(updatedAtKey(KEY_AUTO_REFRESH), nowMillis())
        _autoRefreshMinutes.value = sanitized
    }

    fun setUsageDisplayMode(mode: UsageDisplayMode) {
        settings.putString(KEY_USAGE_DISPLAY_MODE, mode.name)
        settings.putLong(updatedAtKey(KEY_USAGE_DISPLAY_MODE), nowMillis())
        _usageDisplayMode.value = mode
    }

    fun setShowProjectedUsage(show: Boolean) {
        settings.putBoolean(KEY_SHOW_PROJECTED_USAGE, show)
        settings.putLong(updatedAtKey(KEY_SHOW_PROJECTED_USAGE), nowMillis())
        _showProjectedUsage.value = show
    }

    fun setEmailPrivacyMode(mode: EmailPrivacyMode) {
        settings.putString(KEY_EMAIL_PRIVACY_MODE, mode.name)
        settings.putLong(updatedAtKey(KEY_EMAIL_PRIVACY_MODE), nowMillis())
        _emailPrivacyMode.value = mode
    }

    fun exportForSync(): CloudSyncPreferencesRecord {
        return CloudSyncPreferencesRecord(
            themeMode = CloudSyncStringPreference(
                value = _themeMode.value.name,
                updatedAtEpochMillis = preferenceUpdatedAt(KEY_THEME)
            ),
            autoRefreshMinutes = CloudSyncIntPreference(
                value = _autoRefreshMinutes.value,
                updatedAtEpochMillis = preferenceUpdatedAt(KEY_AUTO_REFRESH)
            ),
            usageDisplayMode = CloudSyncStringPreference(
                value = _usageDisplayMode.value.name,
                updatedAtEpochMillis = preferenceUpdatedAt(KEY_USAGE_DISPLAY_MODE)
            ),
            showProjectedUsage = CloudSyncBooleanPreference(
                value = _showProjectedUsage.value,
                updatedAtEpochMillis = preferenceUpdatedAt(KEY_SHOW_PROJECTED_USAGE)
            ),
            emailPrivacyMode = CloudSyncStringPreference(
                value = _emailPrivacyMode.value.name,
                updatedAtEpochMillis = preferenceUpdatedAt(KEY_EMAIL_PRIVACY_MODE)
            )
        )
    }

    fun importForSync(preferences: CloudSyncPreferencesRecord) {
        preferences.themeMode?.let {
            val value = runCatching { ThemeMode.valueOf(it.value) }.getOrNull() ?: return@let
            importString(KEY_THEME, value.name, it.updatedAtEpochMillis)
            _themeMode.value = value
        }
        preferences.autoRefreshMinutes?.let {
            importInt(KEY_AUTO_REFRESH, it.value.coerceAtLeast(0), it.updatedAtEpochMillis)
            _autoRefreshMinutes.value = it.value.coerceAtLeast(0)
        }
        preferences.usageDisplayMode?.let {
            val value = runCatching { UsageDisplayMode.valueOf(it.value) }.getOrNull() ?: return@let
            importString(KEY_USAGE_DISPLAY_MODE, value.name, it.updatedAtEpochMillis)
            _usageDisplayMode.value = value
        }
        preferences.showProjectedUsage?.let {
            settings.putBoolean(KEY_SHOW_PROJECTED_USAGE, it.value)
            settings.putLong(updatedAtKey(KEY_SHOW_PROJECTED_USAGE), it.updatedAtEpochMillis)
            _showProjectedUsage.value = it.value
        }
        preferences.emailPrivacyMode?.let {
            val value = runCatching { EmailPrivacyMode.valueOf(it.value) }.getOrNull() ?: return@let
            importString(KEY_EMAIL_PRIVACY_MODE, value.name, it.updatedAtEpochMillis)
            _emailPrivacyMode.value = value
        }
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

    private fun importString(key: String, value: String, updatedAtEpochMillis: Long) {
        settings.putString(key, value)
        settings.putLong(updatedAtKey(key), updatedAtEpochMillis)
    }

    private fun importInt(key: String, value: Int, updatedAtEpochMillis: Long) {
        settings.putInt(key, value)
        settings.putLong(updatedAtKey(key), updatedAtEpochMillis)
    }

    private fun preferenceUpdatedAt(key: String): Long {
        return settings.getLongOrNull(updatedAtKey(key)) ?: 0L
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private fun updatedAtKey(key: String): String = "${key}_updated_at"

    private companion object {
        const val KEY_THEME = "pref_theme_mode"
        const val KEY_AUTO_REFRESH = "pref_auto_refresh_minutes"
        const val KEY_USAGE_DISPLAY_MODE = "pref_usage_display_mode"
        const val KEY_SHOW_PROJECTED_USAGE = "pref_show_projected_usage"
        const val KEY_EMAIL_PRIVACY_MODE = "pref_email_privacy_mode"
    }
}
