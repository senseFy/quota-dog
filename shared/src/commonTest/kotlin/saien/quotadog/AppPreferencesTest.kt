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
}
