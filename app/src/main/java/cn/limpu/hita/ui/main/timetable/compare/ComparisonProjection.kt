package cn.limpu.hita.ui.main.timetable.compare

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetablecompare.*
import cn.limpu.hita.feature.timetableshare.protocol.*
import cn.limpu.hita.utils.ColorTools
import java.sql.Timestamp
import java.time.*
import java.time.temporal.TemporalAdjusters

val CAMPUS_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
data class ComparisonSegmentPosition(val topMinute: Double, val durationMinute: Double)
fun position(span: TimeSpan, date: LocalDate, startHour: Int): ComparisonSegmentPosition {
    val midnight = date.atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli()
    return ComparisonSegmentPosition((span.startMillis - midnight) / 60_000.0 - startHour * 60,
        (span.endMillis - span.startMillis) / 60_000.0)
}
fun visibleComparisonSlots(day: ComparisonDay, showFree: Boolean, showConflicts: Boolean) =
    day.slots.filter { (showFree && it.status == PeriodStatus.FREE) || (showConflicts && it.status == PeriodStatus.CONFLICT) }

/** The host keeps its ordinary system-zone anchor. Convert its displayed date, not its instant. */
fun comparisonMonday(anchor: Long, sourceZone: ZoneId): Long = Instant.ofEpochMilli(anchor).atZone(sourceZone)
    .toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    .atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli()
fun ordinaryMonday(campusMonday: Long, targetZone: ZoneId): Long = Instant.ofEpochMilli(campusMonday)
    .atZone(CAMPUS_ZONE).toLocalDate().atStartOfDay(targetZone).toInstant().toEpochMilli()

data class ComparisonCourseSegment(val display: EventItem, val original: EventItem)
/** Only display copies are split. Raw records remain the time authority and are never mutated. */
fun projectComparisonCourses(courses: List<EventItem>, monday: Long,
    colors: Map<String, Int> = emptyMap()): List<ComparisonCourseSegment> {
    val first = Instant.ofEpochMilli(monday).atZone(CAMPUS_ZONE).toLocalDate()
    val end = first.plusWeeks(1).atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli()
    return courses.filter { it.type == EventItem.TYPE.CLASS && it.from.time < end && it.to.time > monday }
        .flatMap { original ->
            val result = mutableListOf<ComparisonCourseSegment>()
            var start = maxOf(monday, original.from.time)
            val limit = minOf(end, original.to.time)
            while (start < limit) {
                val date = Instant.ofEpochMilli(start).atZone(CAMPUS_ZONE).toLocalDate()
                val stop = minOf(limit, date.plusDays(1).atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli())
                val copy = EventItem().apply {
                    id = "comparison:${original.id}:$date"
                    type = original.type; source = original.source; name = original.name
                    place = original.place; teacher = original.teacher; subjectId = original.subjectId
                    timetableId = original.timetableId; from = Timestamp(start); to = Timestamp(stop)
                    fromNumber = original.fromNumber; lastNumber = original.lastNumber
                    createdAt = original.createdAt
                    color = colors[original.subjectId]?.takeIf { it != 0 }
                        ?: original.color.takeIf { it != 0 } ?: ColorTools.colorForName(original.name)
                }
                result += ComparisonCourseSegment(copy, original)
                start = stop
            }
            result
        }
}

data class ComparisonCourseDetail(val span: TimeSpan, val name: String?, val place: String?)
data class ComparisonSlotDetails(val own: List<ComparisonCourseDetail>, val friend: List<ComparisonCourseDetail>)
private fun intersects(a: TimeSpan, b: TimeSpan) = a.startMillis < b.endMillis && a.endMillis > b.startMillis
fun comparisonSlotDetails(slot: TimeSpan, own: List<EventItem>, friend: SharedTimetable): ComparisonSlotDetails {
    val ownDetails = own.filter { intersects(slot, TimeSpan(it.from.time, it.to.time)) }
        .map { ComparisonCourseDetail(TimeSpan(it.from.time,it.to.time),it.name,it.place) }
    val friendDetails = friend.occurrences.filter { intersects(slot,TimeSpan(it.startMillis,it.endMillis)) }.map {
        val span = TimeSpan(it.startMillis,it.endMillis)
        // Branch before touching the dictionary or place; BUSY never resolves hidden details.
        if (friend.mode == ShareMode.BUSY) ComparisonCourseDetail(span,null,null)
        else ComparisonCourseDetail(span,it.courseIndex?.let(friend.courses::getOrNull),it.place)
    }
    return ComparisonSlotDetails(ownDetails,friendDetails)
}

/** Any course portion outside the full structure warrants the notice, including rest gaps. */
fun hasOutOfStructureCourses(segments: List<ComparisonCourseSegment>, periods: List<ComparisonPeriod>): Boolean =
    segments.any { segment ->
        val from = segment.display.from.time
        val to = segment.display.to.time
        val date = Instant.ofEpochMilli(from).atZone(CAMPUS_ZONE).toLocalDate()
        val midnight = date.atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli()
        val covered = periods.sumOf { period ->
            (minOf(to,midnight+period.endMinute*60_000L) - maxOf(from,midnight+period.startMinute*60_000L)).coerceAtLeast(0)
        }
        covered < to-from
    }
