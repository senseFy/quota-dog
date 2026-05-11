package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
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
}
