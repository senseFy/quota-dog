package saien.quotadog

import com.russhwolf.settings.MapSettings
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformTokenStoreTest {
    @Test
    fun deleteMigratesLegacyFilesBeforeRemovingTheLocalLogin() = runBlocking {
        val dir = Files.createTempDirectory("quotadog-legacy-test").toFile()
        try {
            val store = PlatformTokenStore(MapSettings(), dir)
            val token = OAuthTokenBundle("fixture", "fixture-grant", expiresAtEpochMillis = Long.MAX_VALUE)
            val key = store.save(ProviderId.CODEX, token)
            val duplicate = dir.resolve("codex-token.json").apply { writeText(Json.encodeToString(token)) }
            val independent = token.copy(
                accessToken = "another-login",
                principalId = "unverified",
                loginGeneration = "foreign-grant",
                quotaIdentityHash = "foreign-target",
            )
            val other = dir.resolve("claude_code-token.json").apply { writeText(Json.encodeToString(independent)) }

            // No list() first: a later startup must not resurrect the deleted duplicate.
            store.delete(key)
            assertFalse(duplicate.exists())
            assertFalse(other.exists())
            assertNull(store.load(key))
            val remaining = store.list().single()
            assertEquals(ProviderId.CLAUDE_CODE, remaining.accountKey.providerId)
            assertEquals(independent.accessToken, remaining.token.accessToken)
            assertNotEquals(independent.loginGeneration, assertNotNull(remaining.token.loginGeneration))
            assertNull(remaining.token.principalId)
            assertNull(remaining.token.quotaIdentityHash)
        } finally {
            dir.deleteRecursively()
        }
    }
}
