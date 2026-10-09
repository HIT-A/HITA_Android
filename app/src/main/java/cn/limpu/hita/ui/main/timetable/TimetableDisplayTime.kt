package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.utils.TimeTools
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.TimeZone

/** Explicit display zone is opt-in; null retains the personal timetable's time helpers. */
object TimetableDisplayTime {
    fun calendar(millis: Long, zone: ZoneId? = null): Calendar =
        (if (zone == null) Calendar.getInstance() else Calendar.getInstance(TimeZone.getTimeZone(zone))).apply { timeInMillis = millis }
    fun dayOfWeek(millis: Long, zone: ZoneId? = null): Int =
        if (zone == null) TimeTools.getDow(millis) else Instant.ofEpochMilli(millis).atZone(zone).dayOfWeek.value
    fun minutes(millis: Long, zone: ZoneId? = null): Int = if (zone == null) {
        TimeTools.getHour(millis) * 60 + TimeTools.getMinute(millis)
    } else Instant.ofEpochMilli(millis).atZone(zone).let { it.hour * 60 + it.minute }
    fun isInWeek(monday: Long, millis: Long, zone: ZoneId? = null): Boolean = if (zone == null) {
        millis >= monday && millis < monday + 7 * 24 * 60 * 60 * 1000L
    } else {
        val first = Instant.ofEpochMilli(monday).atZone(zone).toLocalDate()
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        !date.isBefore(first) && date.isBefore(first.plusWeeks(1))
    }
    fun printTime(millis: Long, zone: ZoneId? = null): String = if (zone == null) TimeTools.printTime(millis)
        else DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))
    /** A midnight endpoint belongs at the bottom of the preceding dated segment. */
    fun endMinutes(event: EventItem, zone: ZoneId): Int {
        val start = Instant.ofEpochMilli(event.from.time).atZone(zone)
        val end = Instant.ofEpochMilli(event.to.time).atZone(zone)
        return if (end.toLocalDate().isAfter(start.toLocalDate()) && end.toLocalTime() == java.time.LocalTime.MIDNIGHT) 1440
            else end.hour * 60 + end.minute
    }
    fun hourRange(events: List<EventItem>, startHour: Int, endHour: Int, zone: ZoneId): Pair<Int, Int> =
        minOf(startHour, events.minOfOrNull { minutes(it.from.time, zone) / 60 } ?: startHour) to
            maxOf(endHour, events.maxOfOrNull { (endMinutes(it, zone) + 59) / 60 } ?: endHour)
}
