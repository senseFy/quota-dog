package saien.quotadog

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudSyncCryptoTest {
    @Test
    fun encryptsAndDecryptsDocument() {
        val document = CloudSyncDocumentV1(
            deviceId = "device-a",
            updatedAtEpochMillis = 1_000,
            accounts = listOf(
                CloudSyncAccountRecord(
                    providerId = ProviderId.CODEX,
                    accountId = "user@example.com",
                    token = CloudSyncTokenValue(
                        value = OAuthTokenBundle(
                            accessToken = "access",
                            refreshToken = "refresh",
                            email = "user@example.com",
                            expiresAtEpochMillis = Long.MAX_VALUE
                        ),
                        updatedAtEpochMillis = 1_000
                    ),
                    snapshot = CloudSyncSnapshotValue(
                        value = ProviderUsageSnapshot(
                            providerId = ProviderId.CODEX,
                            authState = AuthState.LoggedIn,
                            windows = listOf(
                                UsageWindow("primary", "5-hour window", 0.5, Instant.fromEpochSeconds(2))
                            ),
                            collectedAt = Instant.fromEpochSeconds(1),
                            accountEmail = "user@example.com"
                        ),
                        updatedAtEpochMillis = 1_001
                    )
                )
            )
        )

        val encrypted = CloudSyncCrypto.encryptDocument(document, "correct horse battery staple", iterations = 2)
        val decrypted = CloudSyncCrypto.decryptDocument(encrypted, "correct horse battery staple")

        assertEquals(document, decrypted)
        assertFailsWith<CloudSyncCryptoException> {
            CloudSyncCrypto.decryptDocument(encrypted, "wrong horse battery staple")
        }
    }
}
