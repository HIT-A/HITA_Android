package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.utils.ColorTools
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class CoursePlanPreviewProjection(
    val termStartMillis: Long,
    val schedule: List<TimePeriodInDay>,
    val courses: List<ShenzhenCourseCatalogItem>,
    val incompleteCourses: List<ShenzhenCourseCatalogItem>,
    val maxWeek: Int
) {
    fun weekStartMillis(week: Int): Long =
        termStartMillis + (week.coerceAtLeast(1) - 1L) * WEEK_MILLIS

    fun eventsForWeek(week: Int): List<EventItem> {
        val targetWeek = week.coerceAtLeast(1)
        return courses.flatMap { course ->
            val courseID = course.taskId.ifBlank { course.id }
            course.meetings.mapIndexedNotNull { meetingIndex, meeting ->
                if (
                    targetWeek !in meeting.weeks ||
                    !meeting.isStructurallyComplete() ||
                    meeting.beginPeriod !in 1..schedule.size ||
                    meeting.endPeriod !in 1..schedule.size
                ) {
                    return@mapIndexedNotNull null
                }
                val dayStart = weekStartMillis(targetWeek) +
                    (meeting.weekday - 1L) * DAY_MILLIS
                EventItem().apply {
                    id = stableUUID("course-plan-preview:$courseID:$meetingIndex:$targetWeek")
                    type = EventItem.TYPE.CLASS
                    source = EventItem.SOURCE_EAS_IMPORT
                    name = course.courseName
                    place = meeting.location.ifBlank { null }
                    teacher = meeting.teacher.ifBlank { course.teacher.ifBlank { null } }
                    subjectId = courseID
                    from = Timestamp(dayStart + schedule[meeting.beginPeriod - 1].from.toMills())
                    to = Timestamp(dayStart + schedule[meeting.endPeriod - 1].to.toMills())
                    fromNumber = meeting.beginPeriod
                    lastNumber = meeting.endPeriod - meeting.beginPeriod + 1
                    color = ColorTools.colorForName(course.courseName)
                }
            }
        }.sorted()
    }

    private fun stableUUID(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

    companion object {
        /** Course times are academic local times and must not follow the emulator time zone. */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val WEEK_MILLIS = 7L * DAY_MILLIS
    }
}

object CoursePlanPreviewMapper {
    fun map(
        selectedCourses: List<ShenzhenCourseCatalogItem>,
        draftCourses: List<ShenzhenCourseCatalogItem>,
        termStartMillis: Long,
        schedule: List<TimePeriodInDay>
    ): CoursePlanPreviewProjection {
        val courses = (selectedCourses + draftCourses)
            .distinctBy { course -> course.taskId.ifBlank { course.id } }
        val periodCount = schedule.size
        val projectableCourses = courses.filter { course ->
            course.meetings.any { meeting ->
                meeting.isStructurallyComplete() &&
                    meeting.beginPeriod in 1..periodCount &&
                    meeting.endPeriod in 1..periodCount
            }
        }
        val incompleteCourses = courses.filter { course ->
            course.meetings.isEmpty() || course.meetings.any { meeting ->
                !meeting.isStructurallyComplete() ||
                    meeting.beginPeriod !in 1..periodCount ||
                    meeting.endPeriod !in 1..periodCount
            }
        }
        val maxWeek = projectableCourses
            .flatMap { it.meetings }
            .filter { meeting ->
                meeting.isStructurallyComplete() &&
                    meeting.beginPeriod in 1..periodCount &&
                    meeting.endPeriod in 1..periodCount
            }
            .flatMap { it.weeks }
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 1

        val normalizedStart = Instant.ofEpochMilli(termStartMillis)
            .atZone(CoursePlanPreviewProjection.ZONE)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(CoursePlanPreviewProjection.ZONE)
            .toInstant()
            .toEpochMilli()
        return CoursePlanPreviewProjection(
            termStartMillis = normalizedStart,
            schedule = schedule,
            courses = projectableCourses,
            incompleteCourses = incompleteCourses,
            maxWeek = maxWeek
        )
    }
}

object CoursePlanPreviewDiagnostics {
    fun issueReasons(
        course: ShenzhenCourseCatalogItem,
        schedulePeriodCount: Int
    ): List<String> {
        if (course.meetings.isEmpty()) {
            return listOf(
                if (course.schedule.isBlank()) {
                    "教务响应未提供上课时间，解析结果 meetings=0"
                } else {
                    "教务返回了上课时间文本，但未解析出 meeting"
                }
            )
        }
        return course.meetings.mapIndexedNotNull { index, meeting ->
            val reasons = buildList {
                if (meeting.weeks.isEmpty()) add("周次为空")
                if (meeting.weekday !in 1..7) add("星期=${meeting.weekday}")
                if (meeting.beginPeriod <= 0) add("开始节次=${meeting.beginPeriod}")
                if (meeting.endPeriod < meeting.beginPeriod) {
                    add("结束节次=${meeting.endPeriod}<开始节次=${meeting.beginPeriod}")
                }
                if (meeting.beginPeriod !in 1..schedulePeriodCount) {
                    add("开始节次=${meeting.beginPeriod} 超出作息 1..$schedulePeriodCount")
                }
                if (meeting.endPeriod !in 1..schedulePeriodCount) {
                    add("结束节次=${meeting.endPeriod} 超出作息 1..$schedulePeriodCount")
                }
            }
            reasons.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "meeting[$index]: ",
                separator = "；"
            )
        }
    }

    fun report(
        termID: String,
        allCourses: List<ShenzhenCourseCatalogItem>,
        projection: CoursePlanPreviewProjection
    ): String = buildString {
        appendLine("HITA Android 选课预览诊断")
        appendLine("term=$termID")
        appendLine("termStartMillis=${projection.termStartMillis}")
        appendLine("schedulePeriodCount=${projection.schedule.size}")
        appendLine("courseCount=${allCourses.size}")
        appendLine("projectableCourseCount=${projection.courses.size}")
        appendLine("issueCourseCount=${projection.incompleteCourses.size}")
        projection.incompleteCourses.forEachIndexed { courseIndex, course ->
            appendLine()
            appendLine("course[$courseIndex].name=${course.courseName}")
            appendLine("course[$courseIndex].code=${course.courseCode}")
            appendLine("course[$courseIndex].taskId=${course.taskId.ifBlank { course.id }}")
            appendLine("course[$courseIndex].rawSchedule=${course.schedule.ifBlank { "<empty>" }}")
            appendLine("course[$courseIndex].meetingCount=${course.meetings.size}")
            course.meetings.forEachIndexed { meetingIndex, meeting ->
                appendLine(
                    "course[$courseIndex].meeting[$meetingIndex]=" +
                        "weeks=${meeting.weeks},weekday=${meeting.weekday}," +
                        "period=${meeting.beginPeriod}-${meeting.endPeriod}," +
                        "teacher=${meeting.teacher.ifBlank { "<empty>" }}," +
                        "location=${meeting.location.ifBlank { "<empty>" }}"
                )
            }
            issueReasons(course, projection.schedule.size).forEach { reason ->
                appendLine("course[$courseIndex].reason=$reason")
            }
        }
        append("不包含 Cookie、Session 或 Web 请求头")
    }
}
