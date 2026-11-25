package saien.quotadog

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { System, Light, Dark }

enum class UsageDisplayMode { Used, Remaining }

enum class EmailPrivacyMode { Visible, Masked }

/** How much of each account the dashboard shows, from full cards to one row per account. */
enum class DashboardLayout { Cards, Compact, FoldFull, OneLine }

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
    init {
        // Retire only the former Dropbox connection's local keys. In particular, do not
        // interpret the tombstones as requests to delete accounts or cached readings.
        for (key in retiredDropboxKeys) {
            if (settings.hasKey(key)) settings.remove(key)
        }
    }

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

    private val _accountSortMode = MutableStateFlow(loadAccountSortMode())
    val accountSortMode: StateFlow<AccountSortMode> = _accountSortMode

    private val _accountManualOrder = MutableStateFlow(loadAccountManualOrder())
    val accountManualOrder: StateFlow<List<AccountKey>> = _accountManualOrder

    private val _accountSortReversed = MutableStateFlow(loadAccountSortReversed())
    val accountSortReversed: StateFlow<Boolean> = _accountSortReversed

    private val _sinkExhaustedAccounts = MutableStateFlow(loadSinkExhaustedAccounts())
    val sinkExhaustedAccounts: StateFlow<Boolean> = _sinkExhaustedAccounts

    private val _dashboardLayout = MutableStateFlow(loadDashboardLayout())
    val dashboardLayout: StateFlow<DashboardLayout> = _dashboardLayout

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

    fun setAccountSortMode(mode: AccountSortMode) {
        if (_accountSortMode.value == mode) return
        settings.putString(KEY_ACCOUNT_SORT_MODE, mode.name)
        _accountSortMode.value = mode
    }

    fun setAccountManualOrder(keys: List<AccountKey>) {
        val distinct = keys.distinct()
        if (_accountManualOrder.value == distinct) return
        settings.putString(KEY_ACCOUNT_MANUAL_ORDER, encodeAccountOrder(distinct))
        _accountManualOrder.value = distinct
    }

    fun setAccountSortReversed(reversed: Boolean) {
        if (_accountSortReversed.value == reversed) return
        settings.putBoolean(KEY_ACCOUNT_SORT_REVERSED, reversed)
        _accountSortReversed.value = reversed
    }

    fun setSinkExhaustedAccounts(enabled: Boolean) {
        settings.putBoolean(KEY_SINK_EXHAUSTED_ACCOUNTS, enabled)
        _sinkExhaustedAccounts.value = enabled
    }

    fun setDashboardLayout(layout: DashboardLayout) {
        settings.putString(KEY_DASHBOARD_LAYOUT, layout.name)
        _dashboardLayout.value = layout
    }

    // A local record or an incoming grant from one pairing, never an email or a match tag.
    fun phoneAlias(source: String): String? = settings.getStringOrNull(phoneAliasKey(source))

    fun setPhoneAlias(source: String, alias: String) {
        val key = phoneAliasKey(source)
        val name = alias.trim().take(48)
        if (name.isEmpty()) settings.remove(key) else settings.putString(key, name)
    }

    private fun phoneAliasKey(source: String) = "phone_alias_" + base64UrlNoPadding(sha256(source.encodeToByteArray()))

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

    private fun loadAccountSortMode(): AccountSortMode {
        val raw = settings.getStringOrNull(KEY_ACCOUNT_SORT_MODE) ?: return AccountSortMode.Name
        return runCatching { AccountSortMode.valueOf(raw) }.getOrDefault(AccountSortMode.Name)
    }

    private fun loadAccountManualOrder(): List<AccountKey> {
        return decodeAccountOrder(settings.getStringOrNull(KEY_ACCOUNT_MANUAL_ORDER)).orEmpty()
    }

    private fun loadAccountSortReversed(): Boolean {
        return settings.getBooleanOrNull(KEY_ACCOUNT_SORT_REVERSED) ?: false
    }

    private fun loadSinkExhaustedAccounts(): Boolean {
        return settings.getBooleanOrNull(KEY_SINK_EXHAUSTED_ACCOUNTS) ?: true
    }

    private fun loadDashboardLayout(): DashboardLayout {
        val raw = settings.getStringOrNull(KEY_DASHBOARD_LAYOUT) ?: return DashboardLayout.Compact
        return runCatching { DashboardLayout.valueOf(raw) }.getOrDefault(DashboardLayout.Compact)
    }

    private companion object {
        val retiredDropboxKeys = listOf(
            "cloud_sync_dropbox_token_v1",
            "cloud_sync_dropbox_rev_v1",
            "cloud_sync_device_id_v1",
            "cloud_sync_tombstones_v1",
        )
        const val KEY_THEME = "pref_theme_mode"
        const val KEY_AUTO_REFRESH = "pref_auto_refresh_minutes"
        const val KEY_USAGE_DISPLAY_MODE = "pref_usage_display_mode"
        const val KEY_SHOW_PROJECTED_USAGE = "pref_show_projected_usage"
        const val KEY_EMAIL_PRIVACY_MODE = "pref_email_privacy_mode"
        const val KEY_ACCOUNT_SORT_MODE = "pref_account_sort_mode"
        const val KEY_ACCOUNT_MANUAL_ORDER = "pref_account_manual_order"
        const val KEY_ACCOUNT_SORT_REVERSED = "pref_account_sort_reversed"
        const val KEY_SINK_EXHAUSTED_ACCOUNTS = "pref_sink_exhausted_accounts"
        const val KEY_DASHBOARD_LAYOUT = "pref_dashboard_layout"
    }
}
