package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTokenStoreTest {
    @Test
    fun keepsDifferentEmailsForSameProviderSeparate() = runBlocking {
        val store = SettingsTokenStore(MapSettings())

        val firstKey = store.save(ProviderId.CODEX, token(email = "first@example.com", accessToken = "first"))
        val secondKey = store.save(ProviderId.CODEX, token(email = "second@example.com", accessToken = "second"))

        assertNotEquals(firstKey, secondKey)
        assertEquals(2, store.list().size)
        assertEquals("first", store.load(firstKey)?.accessToken)
        assertEquals("second", store.load(secondKey)?.accessToken)
    }

    @Test
    fun normalSaveNeverCoalescesOnEmailPlanDefaultOrServiceInstance() = runBlocking {
        val store = SettingsTokenStore(MapSettings())
        for (provider in ProviderId.entries) {
            val first = token("USER@example.invalid", "first").copy(accountId = "same-plan")
            val key = store.save(provider, first)
            val login = assertNotNull(store.load(key)?.loginGeneration)
            assertEquals(key, store.save(provider, first.copy(email = "user@example.invalid")))
            assertEquals(login, store.load(key)?.loginGeneration)
            val newLogin = store.save(provider, first.copy(accessToken = "second"))
            val workspace = store.save(provider, first.copy(accountId = "different-workspace"))
            val instance = store.save(provider, first.copy(apiServerUrl = "https://different.example.invalid"))
            val unnamed = first.copy(accountId = null, email = null)
            val unknownA = store.save(provider, unnamed)
            val unknownB = store.save(provider, unnamed.copy(accessToken = "another"))
            val rows = setOf(key, newLogin, workspace, instance, unknownA, unknownB)
            assertEquals(6, rows.size, "$provider")
            assertTrue(rows.all { it.accountId.matches(Regex("[A-Za-z0-9_-]{22}")) })
            assertEquals(6, rows.map { assertNotNull(store.load(it)?.loginGeneration) }.distinct().size)
        }
    }

    @Test
    fun importsAndExportsTokensForSync() = runBlocking {
        val store = SettingsTokenStore(MapSettings())
        val accountKey = AccountKey(ProviderId.CODEX, "user@example.com")

        store.importTokenForSync(accountKey, token(email = "user@example.com", accessToken = "synced"), 123)

        val exported = store.exportTokensForSync().single()
        assertEquals(accountKey, exported.accountKey)
        assertEquals("synced", exported.token?.value?.accessToken)
        assertEquals(123, exported.token?.updatedAtEpochMillis)
    }

    @Test
    fun unindexedMigrationPreservesAnIndependentRefreshGrantEvenWithTheSameBearer() = runBlocking {
        val settings = MapSettings()
        val store = SettingsTokenStore(settings)
        val original = token("owner@example.invalid", "same").copy(lastRefreshEpochMillis = 10)
        val key = store.save(ProviderId.CODEX, original)
        val originalLogin = store.load(key)?.loginGeneration
        store.importLegacyFile(ProviderId.CODEX, original)
        assertEquals(listOf(key), store.list().map { it.accountKey })
        settings.putString("oauth_token_codex", Json.encodeToString(original.copy(refreshToken = "independent")))
        val rows = store.list()
        assertEquals(2, rows.size); assertEquals(original.refreshToken, store.load(key)?.refreshToken)
        assertEquals(originalLogin, store.load(key)?.loginGeneration)
        val other = rows.single { it.accountKey != key }
        assertEquals("independent", other.token.refreshToken); assertNotEquals(originalLogin, other.token.loginGeneration)
        assertEquals(key, store.save(ProviderId.CODEX, original))
        assertEquals(other.accountKey, store.save(ProviderId.CODEX, other.token))
        assertEquals(2, store.list().size)
    }

    @Test
    fun upgradePreservesTheExactIndexedDevinGrantAndNeverTransfersItsCacheOrderOrTombstone() = runBlocking {
        for (savedTag in listOf(null, "persisted-1.0.17-grant")) {
            val settings = MapSettings()
            val store = SettingsTokenStore(settings)
            val snapshots = SettingsUsageSnapshotStore(MapSettings())
            val prefs = AppPreferences(MapSettings())
            val repository = CloudSyncLocalRepository(store, snapshots, prefs, MapSettings())
            val key = AccountKey(ProviderId.DEVIN, "owner@example.invalid")
            val old = token(key.accountId, "original").copy(lastRefreshEpochMillis = 100, apiServerUrl = "https://one.example.invalid")
            val digest = base64UrlNoPadding(sha256("DEVIN:${key.accountId}".encodeToByteArray()))
            val diskKey = "oauth_token_v1_devin_$digest"
            val generationField = savedTag?.let { ",\"devinLoginGeneration\":\"$it\"" }.orEmpty()
            settings.putString(diskKey, Json.encodeToString(old).dropLast(1) + generationField + "}")
            settings.putLong("${diskKey}_ts", 123)
            settings.putString("oauth_token_accounts_v1", """{"accounts":[{"providerId":"DEVIN","accountId":"${key.accountId}"}]}""")
            val legacyFile = old.copy(accessToken = "independent")
            settings.putString("oauth_token_devin", Json.encodeToString(legacyFile))
            val cache = ProviderUsageSnapshot(key.providerId, AuthState.LoggedIn, emptyList(), Instant.fromEpochMilliseconds(42))
            snapshots.save(key, cache, 42)
            prefs.setAccountManualOrder(listOf(key))
            val expected = savedTag ?: base64UrlNoPadding(sha256(
                "${old.apiServerUrl}\u0000${old.expiresAtEpochMillis}\u0000${old.accessToken}".encodeToByteArray()))

            val rows = store.list()
            assertEquals(2, rows.size)
            assertEquals(expected, store.load(key)?.loginGeneration)
            assertEquals(expected, SettingsTokenStore(settings).load(key)?.loginGeneration)
            val separate = rows.single { it.accountKey != key }.accountKey
            assertEquals("independent", store.load(separate)?.accessToken)
            assertNotEquals(expected, store.load(separate)?.loginGeneration)
            assertEquals(cache, snapshots.load(key)); assertNull(snapshots.load(separate))
            val backup = repository.exportDocument()
            assertEquals(listOf(key), decodeAccountOrder(backup.preferences.accountManualOrder?.value))
            assertTrue(backup.accounts.all { it.deletedAtEpochMillis == null })
            assertEquals(123, backup.accounts.single { it.accountKey == key }.token?.updatedAtEpochMillis)
            assertEquals(key, store.save(ProviderId.DEVIN, old))
            assertEquals(separate, store.save(ProviderId.DEVIN, legacyFile))
        }
    }

    @Test
    fun dropboxRestoresRowsAndReadingsButNeverOwnershipOrCachedProvenance() = runBlocking {
        val tokens = SettingsTokenStore(MapSettings())
        val snapshots = SettingsUsageSnapshotStore(MapSettings())
        val local = CloudSyncLocalRepository(tokens, snapshots, AppPreferences(MapSettings()), MapSettings())
        val key = tokens.save(ProviderId.CLAUDE_CODE, token("fixture@example.invalid", "bearer").copy(principalId = "private-principal"))
        val stored = assertNotNull(tokens.load(key))
        val login = assertNotNull(stored.loginGeneration)
        assertTrue(tokens.commitIfCurrent(key, stored, stored.copy(quotaIdentityHash = "local-target-hash")))
        val reading = ProviderUsageSnapshot(key.providerId, AuthState.LoggedIn, emptyList(), Instant.fromEpochMilliseconds(100),
            loginTag = login, quotaIdentity = "verified-target", quotaTarget = "project")
        snapshots.save(key, reading)
        val backup = local.exportDocument()
        val json = Json.encodeToString(backup)
        for (localOnly in listOf("devinLoginGeneration", login, "local-target-hash", "private-principal", "verified-target", "quotaTarget", "loginTag")) {
            assertFalse(json.contains(localOnly), localOnly)
        }
        local.applyDocument(backup)
        assertEquals(login, tokens.load(key)?.loginGeneration)
        assertEquals("local-target-hash", tokens.load(key)?.quotaIdentityHash)
        assertEquals("private-principal", tokens.load(key)?.principalId)
        tokens.delete(key); snapshots.delete(key)
        // An old client or hand-crafted document cannot inject a generation, identity or observation either.
        val record = backup.accounts.single()
        local.applyDocument(backup.copy(accounts = listOf(record.copy(
            token = record.token!!.copy(value = stored.copy(quotaIdentityHash = "forged")),
            snapshot = record.snapshot!!.copy(value = reading)))))
        val restored = assertNotNull(tokens.load(key))
        assertNotEquals(login, restored.loginGeneration)
        assertNull(restored.quotaIdentityHash); assertNull(restored.principalId)
        assertNull(snapshots.load(key)?.loginTag); assertNull(snapshots.load(key)?.quotaIdentity)
        assertNull(snapshots.load(key)?.quotaTarget)
    }

    @Test
    fun mergedDropboxDeletionsRetireCredentialsEvenWhenAccompaniedByANewerRestore() = runBlocking {
        val tokens = SettingsTokenStore(MapSettings())
        val snapshots = SettingsUsageSnapshotStore(MapSettings())
        val local = CloudSyncLocalRepository(tokens, snapshots, AppPreferences(MapSettings()), MapSettings())
        val key = AccountKey(ProviderId.DEVIN, "legacy-row")
        val token = token("owner@example.invalid", "identical").copy(lastRefreshEpochMillis = 10)
        val reading = ProviderUsageSnapshot(key.providerId, AuthState.LoggedIn, emptyList(), Instant.fromEpochMilliseconds(11))
        tokens.importTokenForSync(key, token, 10); snapshots.save(key, reading, 11)
        val old = assertNotNull(tokens.load(key)?.loginGeneration)
        val merged = CloudSyncDocumentV1(deviceId = "fixture", updatedAtEpochMillis = 31, accounts = listOf(
            CloudSyncAccountRecord(key.providerId, key.accountId, token = CloudSyncTokenValue(token, 30), deletedAtEpochMillis = 20)))
        local.applyDocument(merged)
        val restored = assertNotNull(tokens.load(key)?.loginGeneration)
        assertNotEquals(old, restored); assertNull(snapshots.load(key))
        local.applyDocument(merged) // Replaying that same old tombstone isn't another relogin.
        assertEquals(restored, tokens.load(key)?.loginGeneration)
        val snapshotOnly = merged.accounts.single().copy(token = null, snapshot = CloudSyncSnapshotValue(reading, 50), deletedAtEpochMillis = 40)
        local.applyDocument(merged.copy(accounts = listOf(snapshotOnly)))
        assertNull(tokens.load(key)); assertEquals(reading, snapshots.load(key))
    }

    @Test
    fun serializedDropboxTokensStripLocalProofOnReadAndWriteIncludingRemergedRecords() {
        val raw = token("fixture@example.invalid", "wire").copy(lastRefreshEpochMillis = 10,
            loginGeneration = "foreign-grant", quotaIdentityHash = "foreign-target", principalId = "foreign-principal")
        val incoming = Json.decodeFromString<CloudSyncTokenValue>("""{"value":${Json.encodeToString(raw)},"updatedAtEpochMillis":11}""")
        val local = incoming.value
        assertEquals("wire", local.accessToken); assertNull(local.loginGeneration); assertNull(local.quotaIdentityHash); assertNull(local.principalId)
        val outgoing = Json.encodeToString(incoming.copy(value = raw))
        for (field in listOf("devinLoginGeneration", "quotaIdentityHash", "principalId", "foreign-")) assertFalse(field in outgoing)
    }

    private fun token(email: String, accessToken: String): OAuthTokenBundle {
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = "refresh-$accessToken",
            email = email,
            expiresAtEpochMillis = Long.MAX_VALUE
        )
    }
}
