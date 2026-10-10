package cn.limpu.hita.feature.timetablecompare

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Pure, read-only comparison of actual course times against complete campus periods. */
class TimetableComparisonEngine {
    fun compare(
        own: ComparisonSchedule,
        friend: ComparisonSchedule,
        request: ComparisonRequest,
    ): ComparisonResult {
        validate(own)
        validate(friend)
        require(request.startMinute in 0..1440 && request.endMinute in 0..1440 &&
            request.startMinute < request.endMinute) { "Invalid daily comparison range" }
        val monday = Instant.ofEpochMilli(request.mondayMillis).atZone(CAMPUS_ZONE).toLocalDate()
        require(monday.dayOfWeek == DayOfWeek.MONDAY &&
            monday.atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli() == request.mondayMillis) {
            "Comparison week must start at campus Monday midnight"
        }
        if (!PeriodStructure.isCompatible(own.periods, friend.periods)) {
            throw IncompatiblePeriodsException()
        }
        return ComparisonResult((0L..6L).map { offset ->
            val date = monday.plusDays(offset)
            val midnight = date.atStartOfDay(CAMPUS_ZONE)
            val slots = own.periods.mapIndexedNotNull { index, period ->
                if (period.startMinute < request.startMinute || period.endMinute > request.endMinute) {
                    return@mapIndexedNotNull null
                }
                val span = TimeSpan(
                    midnight.plusMinutes(period.startMinute.toLong()).toInstant().toEpochMilli(),
                    midnight.plusMinutes(period.endMinute.toLong()).toInstant().toEpochMilli(),
                )
                val status = if (!contains(own.valid, span) || !contains(friend.valid, span)) {
                    PeriodStatus.UNKNOWN
                } else {
                    val ownBusy = own.occupied.any { occupies(it, span) }
                    val friendBusy = friend.occupied.any { occupies(it, span) }
                    when {
                        ownBusy && friendBusy -> PeriodStatus.CONFLICT
                        ownBusy -> PeriodStatus.OWN_ONLY
                        friendBusy -> PeriodStatus.FRIEND_ONLY
                        else -> PeriodStatus.FREE
                    }
                }
                ComparedPeriod(index, span, status)
            }
            ComparisonDay(date, slots)
        })
    }

    private fun validate(schedule: ComparisonSchedule) {
        require(schedule.valid.startMillis < schedule.valid.endMillis) { "Invalid schedule validity" }
        require(PeriodStructure.isValid(schedule.periods)) { "Invalid period structure" }
        require(schedule.occupied.all { it.startMillis < it.endMillis }) { "Invalid occupied interval" }
    }

    private fun contains(valid: TimeSpan, period: TimeSpan): Boolean =
        valid.startMillis <= period.startMillis && valid.endMillis >= period.endMillis

    private fun occupies(course: TimeSpan, period: TimeSpan): Boolean =
        course.startMillis < period.endMillis && course.endMillis > period.startMillis

    private companion object {
        val CAMPUS_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
