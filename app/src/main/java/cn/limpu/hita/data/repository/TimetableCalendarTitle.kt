package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.web.eas.AcademicCalendarPeriod
import java.time.Instant
import java.time.ZoneId

/** Unknown/outside a personal timetable is not evidence of a university vacation. */
object TimetableCalendarTitle {
    data class Result(val week: Int? = null, val vacation: Boolean = false)
    private val zone = ZoneId.of("Asia/Shanghai")
    fun resolve(timetables: List<Timetable>, atMillis: Long, periods: List<AcademicCalendarPeriod>): Result {
        val day = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().toEpochDay()
        for (table in timetables.sortedBy { it.startTime.time }) {
            val campus = table.code?.substringBefore(':')
            if (campus in setOf("BENBU", "SHENZHEN", "WEIHAI")) {
                val start = Instant.ofEpochMilli(table.startTime.time).atZone(zone).toLocalDate().toEpochDay()
                val term = periods.firstOrNull { it.campus == campus && !it.vacation && it.startDay == start }
                if (term != null) {
                    if (term.contains(day)) return Result(week = ((day - start) / 7 + 1).toInt())
                    continue
                }
            }
            if (atMillis in table.startTime.time..table.endTime.time) {
                val week = table.getWeekNumber(atMillis)
                if (week > 0) return Result(week = week)
            }
        }
        val campuses = timetables.map { it.code?.substringBefore(':') }.toSet()
        val knownCampuses = campuses.isNotEmpty() && campuses.all { it in setOf("BENBU", "SHENZHEN", "WEIHAI") }
        return Result(vacation = knownCampuses && campuses.all { campus ->
            periods.any { it.campus == campus && it.vacation && it.contains(day) } &&
                periods.none { it.campus == campus && !it.vacation && it.contains(day) }
        })
    }
}
