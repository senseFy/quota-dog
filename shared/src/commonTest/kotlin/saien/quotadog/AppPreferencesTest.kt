package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPreferencesTest {
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
    fun importsAndExportsPreferencesForSync() {
        val settings = MapSettings()
        val preferences = AppPreferences(settings)

        preferences.importForSync(
            CloudSyncPreferencesRecord(
                themeMode = CloudSyncStringPreference("Dark", 10),
                autoRefreshMinutes = CloudSyncIntPreference(15, 20),
                usageDisplayMode = CloudSyncStringPreference("Remaining", 30),
                showProjectedUsage = CloudSyncBooleanPreference(true, 40),
                emailPrivacyMode = CloudSyncStringPreference("Masked", 50)
            )
        )

        val exported = preferences.exportForSync()
        assertEquals(ThemeMode.Dark, preferences.themeMode.value)
        assertEquals(15, preferences.autoRefreshMinutes.value)
        assertEquals(UsageDisplayMode.Remaining, preferences.usageDisplayMode.value)
        assertTrue(preferences.showProjectedUsage.value)
        assertEquals(EmailPrivacyMode.Masked, preferences.emailPrivacyMode.value)
        assertEquals(10, exported.themeMode?.updatedAtEpochMillis)
        assertEquals(50, exported.emailPrivacyMode?.updatedAtEpochMillis)
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
        preferences.setAccountManualOrder(order)
        preferences.setAccountSortReversed(true)

        val restored = AppPreferences(settings)
        assertEquals(AccountSortMode.Manual, restored.accountSortMode.value)
        assertEquals(order, restored.accountManualOrder.value)
        assertTrue(restored.accountSortReversed.value)

        val exported = restored.exportForSync()
        assertEquals("Manual", exported.accountSortMode?.value)
        assertEquals(encodeAccountOrder(order), exported.accountManualOrder?.value)

        val imported = AppPreferences(MapSettings())
        imported.importForSync(exported)
        assertEquals(AccountSortMode.Manual, imported.accountSortMode.value)
        assertEquals(order, imported.accountManualOrder.value)
        assertTrue(imported.accountSortReversed.value)
    }

    @Test
    fun staleSyncDoesNotOverwriteLocalSortPreferences() {
        val settings = MapSettings()
        val preferences = AppPreferences(settings)
        val order = listOf(AccountKey(ProviderId.CODEX, "a"), AccountKey(ProviderId.GROK, "b"))
        val current = CloudSyncPreferencesRecord(
            accountSortMode = CloudSyncStringPreference("Manual", 200),
            accountManualOrder = CloudSyncStringPreference(encodeAccountOrder(order), 200),
            accountSortReversed = CloudSyncBooleanPreference(true, 200),
        )
        preferences.importForSync(current)

        // A captured sync snapshot may be older or share a millisecond with a later edit.
        for (timestamp in listOf(100L, 200L)) {
            preferences.importForSync(
                CloudSyncPreferencesRecord(
                    accountSortMode = CloudSyncStringPreference("Name", timestamp),
                    accountManualOrder = CloudSyncStringPreference(encodeAccountOrder(order.reversed()), timestamp),
                    accountSortReversed = CloudSyncBooleanPreference(false, timestamp),
                ),
            )

            val restored = AppPreferences(settings)
            assertEquals(AccountSortMode.Manual, restored.accountSortMode.value)
            assertEquals(order, restored.accountManualOrder.value)
            assertTrue(restored.accountSortReversed.value)
            assertEquals(current.accountManualOrder, restored.exportForSync().accountManualOrder)
        }

        preferences.importForSync(
            CloudSyncPreferencesRecord(
                accountSortMode = CloudSyncStringPreference("RefreshTime", 300),
                accountManualOrder = CloudSyncStringPreference(encodeAccountOrder(order.reversed()), 300),
                accountSortReversed = CloudSyncBooleanPreference(false, 300),
            ),
        )
        assertEquals(AccountSortMode.RefreshTime, preferences.accountSortMode.value)
        assertEquals(order.reversed(), preferences.accountManualOrder.value)
        assertFalse(preferences.accountSortReversed.value)
    }

    @Test
    fun invalidManualOrderSyncDoesNotClearTheSavedOrder() {
        val preferences = AppPreferences(MapSettings())
        val order = listOf(AccountKey(ProviderId.DROID, "kept"))
        preferences.setAccountManualOrder(order)

        preferences.importForSync(
            CloudSyncPreferencesRecord(
                accountManualOrder = CloudSyncStringPreference(
                    "{",
                    preferences.exportForSync().accountManualOrder!!.updatedAtEpochMillis + 1,
                )
            )
        )

        assertEquals(order, preferences.accountManualOrder.value)
    }
}
