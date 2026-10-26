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
    ): ComparisonResult = compareAll(own, listOf(friend), request).withoutFriendIndexes()

    /**
     * 一份合并后的我的课表对多位好友。
     * 共同空闲:我与每一位好友该节都无课。同节有课:我有课且至少一位好友有课。
     * 作息必须完全一致;任一方有效期盖不住整节则该节未知。
     */
    fun compareAll(
        own: ComparisonSchedule,
        friends: List<ComparisonSchedule>,
        request: ComparisonRequest,
    ): ComparisonResult {
        validate(own)
        require(friends.isNotEmpty()) { "At least one friend schedule" }
        friends.forEach { validate(it) }
        require(request.startMinute in 0..1440 && request.endMinute in 0..1440 &&
            request.startMinute < request.endMinute) { "Invalid daily comparison range" }
        val monday = Instant.ofEpochMilli(request.mondayMillis).atZone(CAMPUS_ZONE).toLocalDate()
        require(monday.dayOfWeek == DayOfWeek.MONDAY &&
            monday.atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli() == request.mondayMillis) {
            "Comparison week must start at campus Monday midnight"
        }
        if (friends.any { !PeriodStructure.isCompatible(own.periods, it.periods) }) {
            throw IncompatiblePeriodsException()
        }
        return ComparisonResult((0L..6L).map { offset ->
            val date = monday.plusDays(offset)
            val midnight = date.atStartOfDay(CAMPUS_ZONE)
            val slots = own.periods.mapIndexed { index, period ->
                // 时间范围只是展示视口，不再过滤节次：晚节始终参与对比，避免视口配置丢失数据。
                val span = TimeSpan(
                    midnight.plusMinutes(period.startMinute.toLong()).toInstant().toEpochMilli(),
                    midnight.plusMinutes(period.endMinute.toLong()).toInstant().toEpochMilli(),
                )
                val known = contains(own.valid, span) && friends.all { contains(it.valid, span) }
                val busyFriends = if (!known) emptyList() else friends.mapIndexedNotNull { friendIndex, friend ->
                    friendIndex.takeIf { friend.occupied.any { occupies(it, span) } }
                }
                val ownBusy = known && own.occupied.any { occupies(it, span) }
                val status = when {
                    !known -> PeriodStatus.UNKNOWN
                    ownBusy && busyFriends.isNotEmpty() -> PeriodStatus.CONFLICT
                    ownBusy -> PeriodStatus.OWN_ONLY
                    busyFriends.isNotEmpty() -> PeriodStatus.FRIEND_ONLY
                    else -> PeriodStatus.FREE
                }
                ComparedPeriod(index, span, status, busyFriends)
            }
            ComparisonDay(date, slots)
        })
    }

    private fun ComparisonResult.withoutFriendIndexes(): ComparisonResult =
        ComparisonResult(days.map { day ->
            day.copy(slots = day.slots.map { it.copy(busyFriendIndexes = emptyList()) })
        })

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
