package saien.quotadog

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrokBillingTest {
    @Test
    fun mapsWeeklyCreditsLabelFromResetWindow() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val resetAt = now.plusTestSeconds(7L * 24L * 60L * 60L)
        assertEquals("Weekly credits", grokCreditsWindowLabel(resetAt, now))
    }

    @Test
    fun prefersPeriodTypeOverResetWindow() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val resetAt = now.plusTestSeconds(30L * 24L * 60L * 60L)
        assertEquals(
            "Weekly credits",
            grokCreditsWindowLabel(resetAt, now, periodType = "USAGE_PERIOD_TYPE_WEEKLY"),
        )
    }

    @Test
    fun mapsMonthlyCreditsLabelFromResetWindow() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val resetAt = now.plusTestSeconds(30L * 24L * 60L * 60L)
        assertEquals("Monthly credits", grokCreditsWindowLabel(resetAt, now))
    }

    @Test
    fun rejectsUnauthorizedBillingResponses() {
        assertFailsWith<ProviderException> {
            GrokBillingFetcher.parseResponseBytes(statusCode = 401, body = ByteArray(0))
        }
    }

    @Test
    fun parsesFixed32UsageFromRawProtobuf() {
        val payload = byteArrayOf(
            0x0D, // field 1, wire 5
            0x00, 0x00, 0x48, 0x42, // 50.0f
        )
        val snapshot = GrokBillingFetcher.parseGrpcWebPayload(payload, now = Instant.fromEpochSeconds(1_700_000_000))
        assertEquals(50.0, snapshot.usedPercent)
    }

    @Test
    fun treatsZeroUsageWhenOnlyUsagePeriodPresent() {
        // nested field 1/6 varint (path prefix [1,6]) + reset timestamp field 1/5/1
        val payload = byteArrayOf(
            // field 1, length-delimited message
            0x0A, 0x0C,
            // nested field 5, length-delimited
            0x2A, 0x06,
            // nested field 1 varint = 1_800_000_000
            0x08, 0x80.toByte(), 0xA4.toByte(), 0xA7.toByte(), 0xDA.toByte(), 0x06,
            // nested field 6, length-delimited with a varint child
            0x32, 0x02,
            0x08, 0x01,
        )
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val snapshot = GrokBillingFetcher.parseGrpcWebPayload(payload, now = now)
        assertEquals(0.0, snapshot.usedPercent)
        assertEquals(Instant.fromEpochSeconds(1_800_000_000), snapshot.resetsAt)
    }
}

private fun Instant.plusTestSeconds(seconds: Long): Instant {
    return Instant.fromEpochMilliseconds(toEpochMilliseconds() + seconds * 1_000L)
}
