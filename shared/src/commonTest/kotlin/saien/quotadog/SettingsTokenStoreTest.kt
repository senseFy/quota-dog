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
    fun onlyTheExactCredentialRevisionCanCommitOrPublish() = runBlocking {
        val store = SettingsTokenStore(MapSettings())
        val key = store.save(ProviderId.CODEX, token("owner@example.invalid", "first"))
        val original = assertNotNull(store.load(key))
        val renewed = original.copy(accessToken = "renewed") // Same generation, distinct credential revision.
        var published = 0
        assertTrue(store.commitIfCurrent(key, original, renewed) { published++ })
        assertFalse(store.commitIfCurrent(key, original, original.copy(accessToken = "stale")) { published++ })
        assertEquals(renewed, store.load(key))

        store.delete(key)
        store.save(key, renewed)
        assertNotEquals(renewed.loginGeneration, assertNotNull(store.load(key)?.loginGeneration))
        assertFalse(store.commitIfCurrent(key, renewed) { published++ })
        assertEquals(1, published)
    }

    @Test
    fun unindexedMigrationPreservesAnIndependentRefreshGrantEvenWithTheSameBearer() = runBlocking {
        val settings = MapSettings()
        val store = SettingsTokenStore(settings)
        val original = token("owner@example.invalid", "same").copy(lastRefreshEpochMillis = 10, principalId = "verified-here")
        val key = store.save(ProviderId.CODEX, original)
        val originalLogin = assertNotNull(store.load(key)?.loginGeneration)
        store.importLegacyFile(ProviderId.CODEX, original)
        assertEquals(listOf(key), store.list().map { it.accountKey })
        settings.putString("oauth_token_codex", Json.encodeToString(original.copy(refreshToken = "independent",
            loginGeneration = originalLogin, quotaIdentityHash = "unverified-target")))
        val rows = store.list()
        assertEquals(2, rows.size); assertEquals(original.refreshToken, store.load(key)?.refreshToken)
        assertEquals(originalLogin, store.load(key)?.loginGeneration)
        assertEquals("verified-here", store.load(key)?.principalId)
        val other = rows.single { it.accountKey != key }
        assertEquals("independent", other.token.refreshToken); assertNotEquals(originalLogin, assertNotNull(other.token.loginGeneration))
        assertNull(other.token.quotaIdentityHash); assertNull(other.token.principalId)
        assertEquals(key, store.save(ProviderId.CODEX, original))
        assertEquals(other.accountKey, store.save(ProviderId.CODEX, other.token))
        assertEquals(2, store.list().size)
    }

    @Test
    fun upgradePreservesTheExactIndexedDevinGrantAndNeverTransfersItsCacheOrOrder() = runBlocking {
        for (savedTag in listOf(null, "persisted-1.0.17-grant")) {
            val settings = MapSettings()
            val store = SettingsTokenStore(settings)
            val snapshots = SettingsUsageSnapshotStore(MapSettings())
            val preferenceSettings = MapSettings()
            val key = AccountKey(ProviderId.DEVIN, "owner@example.invalid")
            val old = token(key.accountId, "original").copy(lastRefreshEpochMillis = 100, apiServerUrl = "https://one.example.invalid")
            val digest = base64UrlNoPadding(sha256("DEVIN:${key.accountId}".encodeToByteArray()))
            val diskKey = "oauth_token_v1_devin_$digest"
            val generationField = savedTag?.let { ",\"devinLoginGeneration\":\"$it\"" }.orEmpty()
            settings.putString(diskKey, """{
                "accessToken":"${old.accessToken}","refreshToken":"${old.refreshToken}","email":"${old.email}",
                "expiresAtEpochMillis":${old.expiresAtEpochMillis},"apiServerUrl":"${old.apiServerUrl}"$generationField
            }""")
            settings.putLong("${diskKey}_ts", 123)
            settings.putString("oauth_token_accounts_v1", """{"accounts":[{"providerId":"DEVIN","accountId":"${key.accountId}"}]}""")
            val legacyFile = old.copy(accessToken = "independent")
            settings.putString("oauth_token_devin", Json.encodeToString(legacyFile))
            val cache = ProviderUsageSnapshot(key.providerId, AuthState.LoggedIn, emptyList(), Instant.fromEpochMilliseconds(42))
            snapshots.save(key, cache)
            AppPreferences(preferenceSettings).setAccountManualOrder(listOf(key))
            val expected = savedTag ?: base64UrlNoPadding(sha256(
                "${old.apiServerUrl}\u0000${old.expiresAtEpochMillis}\u0000${old.accessToken}".encodeToByteArray()))

            val rows = store.list()
            assertEquals(2, rows.size)
            assertEquals(expected, store.load(key)?.loginGeneration)
            assertEquals(expected, SettingsTokenStore(settings).load(key)?.loginGeneration)
            assertEquals(123L, store.load(key)?.lastRefreshEpochMillis)
            val separate = rows.single { it.accountKey != key }.accountKey
            assertEquals("independent", store.load(separate)?.accessToken)
            assertNotEquals(expected, store.load(separate)?.loginGeneration)
            assertEquals(cache, snapshots.load(key)); assertNull(snapshots.load(separate))
            assertEquals(listOf(key), AppPreferences(preferenceSettings).accountManualOrder.value)
            assertEquals(key, store.save(ProviderId.DEVIN, old))
            assertEquals(separate, store.save(ProviderId.DEVIN, legacyFile))
        }
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
