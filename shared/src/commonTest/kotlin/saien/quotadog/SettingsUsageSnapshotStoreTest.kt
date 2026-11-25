package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsUsageSnapshotStoreTest {
    @Test
    fun savesLoadsAndDeletesUsageSnapshots() {
        val store = SettingsUsageSnapshotStore(MapSettings())
        val accountKey = AccountKey(ProviderId.CODEX, "user@example.com")
        val snapshot = ProviderUsageSnapshot(
            providerId = ProviderId.CODEX,
            authState = AuthState.LoggedIn,
            windows = listOf(
                UsageWindow(
                    id = "primary",
                    label = "5-hour window",
                    usedRatio = 0.42,
                    resetsAt = Instant.fromEpochSeconds(1_700_000_000)
                )
            ),
            collectedAt = Instant.fromEpochSeconds(1_699_999_900),
            accountEmail = "user@example.com"
        )

        store.save(accountKey, snapshot)

        assertEquals(snapshot, store.load(accountKey))
        store.delete(accountKey)
        assertNull(store.load(accountKey))
    }

    @Test
    fun cachedSnapshotNeverRestoresLocalIdentityProof() {
        val store = SettingsUsageSnapshotStore(MapSettings())
        val accountKey = AccountKey(ProviderId.CODEX, "user@example.com")
        val snapshot = ProviderUsageSnapshot(
            providerId = ProviderId.CODEX,
            authState = AuthState.LoggedIn,
            windows = emptyList(),
            collectedAt = Instant.fromEpochSeconds(1),
            accountEmail = "user@example.com"
        )

        store.save(accountKey, snapshot.copy(loginTag = "verified-login", quotaIdentity = "identity", quotaTarget = "project"))
        assertEquals(snapshot, store.load(accountKey))
    }

    @Test
    fun deletingALegacySnapshotForgetsOnlyThatAccountsReadingAndIndexEntry() {
        val settings = MapSettings()
        val store = SettingsUsageSnapshotStore(settings)
        val first = AccountKey(ProviderId.CODEX, "deleted")
        val second = AccountKey(ProviderId.CODEX, "kept")
        val reading = ProviderUsageSnapshot(ProviderId.CODEX, AuthState.LoggedIn, emptyList(), Instant.fromEpochSeconds(1))
        store.save(first, reading)
        store.save(second, reading)
        settings.putString("usage_snapshot_accounts_v1", """{"accounts":[{"providerId":"CODEX","accountId":"deleted"},{"providerId":"CODEX","accountId":"kept"}]}""")

        store.delete(first)
        assertNull(store.load(first))
        assertEquals(reading, store.load(second))
        assertEquals("""{"accounts":[{"providerId":"CODEX","accountId":"kept"}]}""", settings.getStringOrNull("usage_snapshot_accounts_v1"))
    }

    @Test
    fun savesSnapshotForLongProviderNames() {
        val store = SettingsUsageSnapshotStore(MapSettings())
        val accountKey = AccountKey(ProviderId.ANTIGRAVITY, "user@example.com")
        val snapshot = ProviderUsageSnapshot(
            providerId = ProviderId.ANTIGRAVITY,
            authState = AuthState.LoggedIn,
            windows = listOf(
                UsageWindow(
                    id = "antigravity-gemini-session",
                    label = "Gemini · 5-hour",
                    usedRatio = 0.0,
                    resetsAt = Instant.fromEpochSeconds(1_700_000_000),
                ),
            ),
            collectedAt = Instant.fromEpochSeconds(1_699_999_900),
            accountEmail = "user@example.com",
        )

        store.save(accountKey, snapshot)
        assertEquals(snapshot, store.load(accountKey))
        store.delete(accountKey)
        assertNull(store.load(accountKey))
    }
}
