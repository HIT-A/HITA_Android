package cn.limpu.hita.feature.timetablecompare

import java.time.LocalDate

data class TimeSpan(val startMillis: Long, val endMillis: Long)
data class ComparisonPeriod(val startMinute: Int, val endMinute: Int)
data class ComparisonSchedule(
    val valid: TimeSpan,
    val periods: List<ComparisonPeriod>,
    val occupied: List<TimeSpan>,
)
enum class PeriodStatus { FREE, OWN_ONLY, FRIEND_ONLY, CONFLICT, UNKNOWN }
/** Index is zero-based in the full schedule structure, including filtered-out periods. */
data class ComparedPeriod(val index: Int, val span: TimeSpan, val status: PeriodStatus)
data class ComparisonRequest(
    val mondayMillis: Long,
    val startMinute: Int = 510,
    val endMinute: Int = 1350,
)
data class ComparisonDay(val date: LocalDate, val slots: List<ComparedPeriod>) {
    val windows get() = slots.filter { it.status != PeriodStatus.UNKNOWN }.map { it.span }
    val free get() = slots.filter { it.status == PeriodStatus.FREE }.map { it.span }
    val conflicts get() = slots.filter { it.status == PeriodStatus.CONFLICT }.map { it.span }
}
data class ComparisonResult(val days: List<ComparisonDay>)
class IncompatiblePeriodsException : IllegalArgumentException()

object PeriodStructure {
    fun isValid(periods: List<ComparisonPeriod>): Boolean =
        periods.isNotEmpty() && periods.all {
            it.startMinute in 0..1440 && it.endMinute in 0..1440 && it.startMinute < it.endMinute
        } && periods.zipWithNext().all { (previous, next) -> previous.endMinute <= next.startMinute }

    fun isCompatible(own: List<ComparisonPeriod>, friend: List<ComparisonPeriod>): Boolean =
        isValid(own) && isValid(friend) && own == friend
}
