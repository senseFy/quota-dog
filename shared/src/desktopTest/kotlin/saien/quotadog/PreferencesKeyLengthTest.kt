package saien.quotadog

import com.russhwolf.settings.PreferencesSettings
import kotlinx.datetime.Instant
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreferencesKeyLengthTest {
    @Test
    fun antigravityTokenAndSnapshotFitJavaPreferences() {
        val node = Preferences.userRoot().node("saien/quotadog/prefs-test-keylen")
        node.clear()
        val settings = PreferencesSettings(node)
        try {
            val accountKey = AccountKey(ProviderId.ANTIGRAVITY, "user@example.com")
            val tokenStore = SettingsTokenStore(settings)
            val snapshotStore = SettingsUsageSnapshotStore(settings)
            val token = OAuthTokenBundle(
                accessToken = "access",
                refreshToken = "refresh",
                email = "user@example.com",
                expiresAtEpochMillis = 2_000_000_000_000L,
            )
            kotlinx.coroutines.runBlocking {
                tokenStore.save(ProviderId.ANTIGRAVITY, token)
            }
            val snapshot = ProviderUsageSnapshot(
                providerId = ProviderId.ANTIGRAVITY,
                authState = AuthState.LoggedIn,
                windows = listOf(
                    UsageWindow("antigravity-gemini-session", "Gemini · 5-hour", 0.1, Instant.fromEpochSeconds(2_000_000_000)),
                ),
                collectedAt = Instant.fromEpochSeconds(1_999_999_000),
                accountEmail = "user@example.com",
            )
            snapshotStore.save(accountKey, snapshot)
            assertEquals(snapshot, snapshotStore.load(accountKey))
            kotlinx.coroutines.runBlocking {
                tokenStore.delete(accountKey)
            }
            snapshotStore.delete(accountKey)
            assertNull(snapshotStore.load(accountKey))
        } finally {
            node.removeNode()
        }
    }
}
