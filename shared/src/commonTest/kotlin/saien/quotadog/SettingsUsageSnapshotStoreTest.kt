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
    fun importsAndExportsSnapshotsForSync() {
        val store = SettingsUsageSnapshotStore(MapSettings())
        val accountKey = AccountKey(ProviderId.CODEX, "user@example.com")
        val snapshot = ProviderUsageSnapshot(
            providerId = ProviderId.CODEX,
            authState = AuthState.LoggedIn,
            windows = emptyList(),
            collectedAt = Instant.fromEpochSeconds(1),
            accountEmail = "user@example.com"
        )

        store.importSnapshotForSync(accountKey, snapshot, 456)

        val exported = store.exportSnapshotsForSync().single()
        assertEquals(accountKey, exported.accountKey)
        assertEquals(snapshot, exported.snapshot?.value)
        assertEquals(456, exported.snapshot?.updatedAtEpochMillis)
    }
}
