package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPreferencesTest {
    @Test
    fun sinkExhaustedAccountsDefaultsOnAndRoundTrips() {
        val settings = MapSettings()
        val preferences = AppPreferences(settings)

        assertTrue(preferences.sinkExhaustedAccounts.value)

        preferences.setSinkExhaustedAccounts(false)

        assertFalse(AppPreferences(settings).sinkExhaustedAccounts.value)
    }

    @Test
    fun dashboardLayoutDefaultsToCompactAndPersistsTheChosenLayout() {
        val settings = MapSettings()
        val preferences = AppPreferences(settings)

        assertEquals(DashboardLayout.Compact, preferences.dashboardLayout.value)

        preferences.setDashboardLayout(DashboardLayout.FoldFull)

        assertEquals(DashboardLayout.FoldFull, AppPreferences(settings).dashboardLayout.value)
    }

    @Test
    fun phoneNicknamesStayWithTheirRecordOrPairAndCanBeCleared() {
        val settings = MapSettings()
        val prefs = AppPreferences(settings)
        prefs.setPhoneAlias("local:CODEX:one", " Work ")
        prefs.setPhoneAlias("grant:mac-a:one", "Personal")
        val restored = AppPreferences(settings)
        assertEquals("Work", restored.phoneAlias("local:CODEX:one"))
        assertEquals(null, restored.phoneAlias("local:CODEX:two"))
        assertEquals(null, restored.phoneAlias("grant:mac-b:one"))
        restored.setPhoneAlias("local:CODEX:one", " ")
        assertEquals(null, restored.phoneAlias("local:CODEX:one"))
        assertEquals("Personal", restored.phoneAlias("grant:mac-a:one"))
    }

    @Test
    fun projectedUsageDisplayDefaultsOff() {
        val preferences = AppPreferences(MapSettings())

        assertFalse(preferences.showProjectedUsage.value)
    }

    @Test
    fun projectedUsageDisplayPersistsSelection() {
        val settings = MapSettings()
        val preferences = AppPreferences(settings)

        preferences.setShowProjectedUsage(true)

        assertTrue(AppPreferences(settings).showProjectedUsage.value)
    }

    @Test
    fun displayAndRefreshChoicesPersistLocally() {
        val settings = MapSettings()
        val preferences = AppPreferences(settings)

        preferences.setThemeMode(ThemeMode.Dark)
        preferences.setAutoRefreshMinutes(15)
        preferences.setUsageDisplayMode(UsageDisplayMode.Remaining)
        preferences.setEmailPrivacyMode(EmailPrivacyMode.Masked)

        val restored = AppPreferences(settings)
        assertEquals(ThemeMode.Dark, restored.themeMode.value)
        assertEquals(15, restored.autoRefreshMinutes.value)
        assertEquals(UsageDisplayMode.Remaining, restored.usageDisplayMode.value)
        assertEquals(EmailPrivacyMode.Masked, restored.emailPrivacyMode.value)
    }

    @Test
    fun accountSortDefaultsToNameAndRoundTripsManualOrder() {
        val settings = MapSettings()
        val preferences = AppPreferences(settings)
        val order = listOf(
            AccountKey(ProviderId.GROK, "b@example.com"),
            AccountKey(ProviderId.CODEX, "a@example.com"),
        )

        assertEquals(AccountSortMode.Name, preferences.accountSortMode.value)
        assertEquals(emptyList(), preferences.accountManualOrder.value)
        assertFalse(preferences.accountSortReversed.value)

        preferences.setAccountSortMode(AccountSortMode.Manual)
        preferences.setAccountManualOrder(order + order.first())
        preferences.setAccountSortReversed(true)

        val restored = AppPreferences(settings)
        assertEquals(AccountSortMode.Manual, restored.accountSortMode.value)
        assertEquals(order, restored.accountManualOrder.value)
        assertTrue(restored.accountSortReversed.value)
    }

    @Test
    fun upgradeDiscardsOnlyTheFourDropboxSettingsWithoutApplyingTombstones() = runBlocking {
        val key = AccountKey(ProviderId.CODEX, "fixture-row")
        val digest = base64UrlNoPadding(sha256("CODEX:fixture-row".encodeToByteArray()))
        val index = """{"accounts":[{"providerId":"CODEX","accountId":"fixture-row"}]}"""
        val token = OAuthTokenBundle("fixture-bearer", "fixture-refresh", expiresAtEpochMillis = 300,
            lastRefreshEpochMillis = 123, loginGeneration = "saved-grant", quotaIdentityHash = "saved-target")
        val reading = ProviderUsageSnapshot(ProviderId.CODEX, AuthState.LoggedIn,
            listOf(UsageWindow("primary", "Quota", 0.4, null)), Instant.fromEpochMilliseconds(100))

        // Co-locate even the account and pairing fixtures: compare every unrelated value byte for byte.
        val kept = mapOf<String, Any>(
            "oauth_token_accounts_v1" to index, "oauth_token_v1_codex_$digest" to Json.encodeToString(token),
            "usage_snapshot_accounts_v1" to index, "usage_snapshot_v1_codex_$digest" to Json.encodeToString(reading),
            "pref_account_sort_mode" to "Manual", "pref_account_manual_order" to index, "pref_theme_mode" to "Dark",
            "host_sync_enabled_v1" to true, "phones" to "fixture-pairing",
            "cloud_sync_unrelated_key" to "not-owned", "unrelated_updated_at" to 17L,
        )
        val disk = kept.toMutableMap().apply {
            put("cloud_sync_dropbox_token_v1", """{"accessToken":"obsolete","refreshToken":"obsolete","accountId":"fixture","expiresAtEpochMillis":300}""")
            put("cloud_sync_dropbox_rev_v1", "old-revision")
            put("cloud_sync_device_id_v1", "old-device")
            put("cloud_sync_tombstones_v1", """{"accounts":[{"providerId":"CODEX","accountId":"fixture-row","deletedAtEpochMillis":999}]}""")
        }
        val settings = MapSettings(disk)
        repeat(2) {
            val preferences = AppPreferences(settings)
            assertEquals(kept, disk)
            assertEquals(ThemeMode.Dark, preferences.themeMode.value)
            assertEquals(AccountSortMode.Manual, preferences.accountSortMode.value)
            assertEquals(listOf(key), preferences.accountManualOrder.value)
            assertEquals(listOf(StoredToken(key, token)), SettingsTokenStore(settings).list())
            assertEquals(reading, SettingsUsageSnapshotStore(settings).load(key))
        }
    }
}
