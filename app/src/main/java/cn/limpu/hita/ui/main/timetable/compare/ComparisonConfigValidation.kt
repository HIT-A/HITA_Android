package cn.limpu.hita.ui.main.timetable.compare

import cn.limpu.hita.feature.timetablecompare.ComparisonPeriod
import cn.limpu.hita.feature.timetablecompare.PeriodStructure

enum class ConfigProblem { INVALID_RANGE, INVALID_STRUCTURE, INCOMPATIBLE, NO_COMPLETE_PERIOD }
fun comparisonConfigError(config: ComparisonConfig, own: List<ComparisonPeriod>, friend: List<ComparisonPeriod>): ConfigProblem? = when {
    !config.isValid() -> ConfigProblem.INVALID_RANGE
    !PeriodStructure.isValid(own) || !PeriodStructure.isValid(friend) -> ConfigProblem.INVALID_STRUCTURE
    !PeriodStructure.isCompatible(own, friend) -> ConfigProblem.INCOMPATIBLE
    own.none { it.startMinute >= config.startMinute && it.endMinute <= config.endMinute } -> ConfigProblem.NO_COMPLETE_PERIOD
    else -> null
}
fun parseComparisonTime(text: String): Int? {
    if (!Regex("[0-9]{2}:[0-9]{2}").matches(text)) return null
    val hour = text.substring(0, 2).toInt()
    val minute = text.substring(3, 5).toInt()
    return when {
        hour in 0..23 && minute in 0..59 -> hour * 60 + minute
        hour == 24 && minute == 0 -> 1440
        else -> null
    }
}
fun comparisonTime(minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
