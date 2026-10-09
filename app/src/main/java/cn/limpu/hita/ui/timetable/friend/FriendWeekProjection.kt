package cn.limpu.hita.ui.timetable.friend

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetableshare.protocol.*
import java.sql.Timestamp
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit

/** Snapshot adapters stay in memory and never enter the personal timetable data layer. */
object FriendWeekProjection {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate()
    private fun monday(millis: Long) = date(millis).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun millis(date: LocalDate) = date.atStartOfDay(ZONE).toInstant().toEpochMilli()
    fun firstMonday(snapshot: SharedTimetable): Long = millis(monday(snapshot.term.startMillis))
    fun weekNumber(snapshot: SharedTimetable, mondayMillis: Long): Int =
        ChronoUnit.WEEKS.between(monday(snapshot.term.startMillis), monday(mondayMillis)).toInt() + 1
    fun moveWeek(snapshot: SharedTimetable, mondayMillis: Long, delta: Int): Long =
        millis(monday(mondayMillis).plusWeeks(delta.toLong()).coerceIn(monday(snapshot.term.startMillis), monday(snapshot.term.endMillis)))
    fun initialMonday(snapshot: SharedTimetable, nowMillis: Long): Long =
        if (date(nowMillis) in date(snapshot.term.startMillis)..date(snapshot.term.endMillis)) millis(monday(nowMillis)) else firstMonday(snapshot)
    fun events(snapshot: SharedTimetable, mondayMillis: Long, busyLabel: String): List<EventItem> {
        val windowStart = millis(monday(mondayMillis))
        val windowEnd = millis(monday(mondayMillis).plusWeeks(1))
        return snapshot.occurrences.flatMapIndexed { index, occurrence ->
            val result = mutableListOf<EventItem>()
            var start = maxOf(windowStart, occurrence.startMillis)
            val end = minOf(windowEnd, occurrence.endMillis)
            while (start < end) {
                val segmentEnd = minOf(end, millis(date(start).plusDays(1)))
                result += EventItem().apply {
                    id = "${snapshot.shareId}:$index:${date(start)}"
                    subjectId = "${snapshot.shareId}:" + (if (snapshot.mode == ShareMode.FULL) occurrence.courseIndex else "busy")
                    timetableId = snapshot.shareId
                    name = if (snapshot.mode == ShareMode.BUSY) busyLabel else snapshot.courses[requireNotNull(occurrence.courseIndex)]
                    place = if (snapshot.mode == ShareMode.BUSY) "" else occurrence.place.orEmpty()
                    teacher = ""
                    from = Timestamp(start)
                    to = Timestamp(segmentEnd)
                }
                start = segmentEnd
            }
            result
        }
    }
    fun originalOccurrence(snapshot: SharedTimetable, event: EventItem): SharedOccurrence? {
        val index = event.id.removePrefix("${snapshot.shareId}:").substringBefore(':').toIntOrNull() ?: return null
        return if (event.id.startsWith("${snapshot.shareId}:")) snapshot.occurrences.getOrNull(index) else null
    }
}
