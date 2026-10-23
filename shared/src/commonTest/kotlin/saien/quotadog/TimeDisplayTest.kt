package saien.quotadog

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeDisplayTest {
    @Test
    fun exactTimeIncludesLocalDateSecondsAndZoneWithoutRounding() {
        val instant = Instant.parse("2026-12-31T19:04:59.987Z")
        assertEquals(
            "2027-01-01 03:04:59\nUTC+08:00 · Asia/Shanghai",
            instant.exactLocalTimeLabel(TimeZone.of("Asia/Shanghai")),
        )
        assertEquals(
            "2026-12-31 19:04:59\nUTC · Z",
            instant.exactLocalTimeLabel(TimeZone.UTC),
        )
    }

    @Test
    fun repeatedDaylightSavingHourHasUnambiguousOffset() {
        val zone = TimeZone.of("America/New_York")
        assertEquals(
            "2026-11-01 01:30:07\nUTC-04:00 · America/New_York",
            Instant.parse("2026-11-01T05:30:07Z").exactLocalTimeLabel(zone),
        )
        assertEquals(
            "2026-11-01 01:30:07\nUTC-05:00 · America/New_York",
            Instant.parse("2026-11-01T06:30:07Z").exactLocalTimeLabel(zone),
        )
    }
}
