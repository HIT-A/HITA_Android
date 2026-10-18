package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.utils.TimeTools
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Calendar

/** 明确排到某日的课程保留原日期；按教学周排课的课程沿用课表开学日。 */
internal fun resolveEasCourseDates(course: CourseItem, termStart: Calendar): List<Calendar> {
    val scheduledDate = course.scheduledDate
    if (scheduledDate != null) {
        val date = try {
            LocalDate.parse(scheduledDate)
        } catch (_: DateTimeParseException) {
            // 有原始日期但格式无效时，不退回估算周次生成可能错误的课时。
            return emptyList()
        }
        return listOf((termStart.clone() as Calendar).apply {
            clear()
            set(date.year, date.monthValue - 1, date.dayOfMonth, 0, 0, 0)
        })
    }
    return course.weeks.map { week ->
        TimeTools.getDateAtWOT(termStart, week, course.dow)
    }
}