package saien.quotadog

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime

/** Full local timestamp for reset/expiry details, including the offset at that instant. */
fun Instant.exactLocalTimeLabel(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = toLocalDateTime(timeZone)
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    val second = local.second.toString().padStart(2, '0')
    val offset = offsetIn(timeZone).toString().let { if (it == "Z") "UTC" else "UTC$it" }
    return "${local.date} $hour:$minute:$second\n$offset · ${timeZone.id}"
}
