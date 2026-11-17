package cn.limpu.hita.ui.main.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar days, including DST days, rather than fixed 24-hour durations. */
data class TodayDateWindow private constructor(val date: LocalDate, val zoneId: ZoneId) {
    val start: Long = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val tomorrowStart: Long = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val dayAfterStart: Long = date.plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val preparationStart: Long = date.atTime(20, 30).atZone(zoneId).toInstant().toEpochMilli()

    companion object {
        fun from(now: Instant, zoneId: ZoneId): TodayDateWindow =
            TodayDateWindow(now.atZone(zoneId).toLocalDate(), zoneId)
    }
}
