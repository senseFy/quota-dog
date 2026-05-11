package saien.quotadog

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
    fun overwritesSameEmailForSameProvider() = runBlocking {
        val store = SettingsTokenStore(MapSettings())

        val firstKey = store.save(ProviderId.CLAUDE_CODE, token(email = "USER@example.com", accessToken = "old"))
        val secondKey = store.save(ProviderId.CLAUDE_CODE, token(email = "user@example.com", accessToken = "new"))

        assertEquals(firstKey, secondKey)
        assertEquals(1, store.list().size)
        assertEquals("new", store.load(firstKey)?.accessToken)
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

    private fun token(email: String, accessToken: String): OAuthTokenBundle {
        return OAuthTokenBundle(
            accessToken = accessToken,
            refreshToken = "refresh-$accessToken",
            email = email,
            expiresAtEpochMillis = Long.MAX_VALUE
        )
    }
}
