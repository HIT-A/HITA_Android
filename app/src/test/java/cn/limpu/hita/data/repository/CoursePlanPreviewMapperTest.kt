package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.ShenzhenCourseMeeting
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.time.ZoneId
import java.time.ZonedDateTime

class CoursePlanPreviewMapperTest {
    @Test
    fun `selected courses and manual draft are merged by teaching task`() {
        val selected = course("A", "教务名称", meeting(weeks = listOf(2), begin = 3, end = 4))
        val duplicateDraft = selected.copy(courseName = "草稿旧名称")
        val manual = course("B", "手动课程", meeting(weeks = listOf(1), begin = 1, end = 2))

        val projection = CoursePlanPreviewMapper.map(
            selectedCourses = listOf(selected),
            draftCourses = listOf(duplicateDraft, manual),
            termStartMillis = monday(),
            schedule = schedule(12)
        )

        assertEquals(listOf("教务名称", "手动课程"), projection.courses.map { it.courseName })
        assertEquals(2, projection.maxWeek)
        assertEquals(0, Calendar.getInstance().apply {
            timeInMillis = projection.termStartMillis
        }.get(Calendar.SECOND))
        assertEquals(1, projection.eventsForWeek(2).size)
        assertEquals(3, projection.eventsForWeek(2).single().fromNumber)
        assertEquals(2, projection.eventsForWeek(2).single().lastNumber)
    }

    @Test
    fun `invalid meetings are reported without hiding valid meetings`() {
        val partiallyValid = course(
            "A",
            "部分有效课程",
            meeting(weeks = listOf(1), begin = 1, end = 2),
            meeting(weeks = listOf(1), begin = 13, end = 14)
        )
        val fullyInvalid = course(
            "B",
            "无有效时间课程",
            meeting(weeks = listOf(1), begin = 13, end = 14)
        )

        val projection = CoursePlanPreviewMapper.map(
            selectedCourses = listOf(partiallyValid, fullyInvalid),
            draftCourses = emptyList(),
            termStartMillis = monday(),
            schedule = schedule(12)
        )

        assertEquals(listOf("部分有效课程"), projection.courses.map { it.courseName })
        assertEquals(setOf("部分有效课程", "无有效时间课程"), projection.incompleteCourses.map { it.courseName }.toSet())
        assertEquals(1, projection.eventsForWeek(1).size)
        assertTrue(projection.eventsForWeek(1).single().to.time > projection.eventsForWeek(1).single().from.time)
    }

    @Test
    fun `diagnostics distinguishes missing upstream schedule from invalid periods`() {
        val missingSchedule = course("A", "未排课课程")
        val invalidPeriod = course(
            "B",
            "节次异常课程",
            meeting(weeks = listOf(1), begin = 13, end = 14)
        ).copy(schedule = "第13-14节")
        val projection = CoursePlanPreviewMapper.map(
            selectedCourses = listOf(missingSchedule, invalidPeriod),
            draftCourses = emptyList(),
            termStartMillis = monday(),
            schedule = schedule(12)
        )

        val report = CoursePlanPreviewDiagnostics.report(
            termID = "2026-20271",
            allCourses = listOf(missingSchedule, invalidPeriod),
            projection = projection
        )

        assertTrue(report.contains("教务响应未提供上课时间"))
        assertTrue(report.contains("开始节次=13 超出作息 1..12"))
        assertTrue(report.contains("rawSchedule=第13-14节"))
        assertTrue(report.contains("不包含 Cookie、Session"))
    }

    @Test
    fun `preview anchor and events stay in academic zone when default zone is UTC`() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val academicZone = ZoneId.of("Asia/Shanghai")
            val termStart = ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, academicZone)
                .toInstant().toEpochMilli()
            val projection = CoursePlanPreviewMapper.map(
                selectedCourses = listOf(course("A", "跨时区课程", meeting(listOf(1), 1, 1).copy(weekday = 1))),
                draftCourses = emptyList(),
                termStartMillis = termStart,
                schedule = schedule(3)
            )

            assertEquals(termStart, projection.termStartMillis)
            assertEquals(
                termStart + 8 * 60 * 60 * 1000L,
                projection.eventsForWeek(1).single().from.time
            )
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    private fun course(
        id: String,
        name: String,
        vararg meetings: ShenzhenCourseMeeting
    ) = ShenzhenCourseCatalogItem(
        id = id,
        taskId = id,
        courseCode = id,
        courseName = name,
        meetings = meetings.toList(),
        source = ShenzhenCourseCatalogSource.AVAILABLE
    )

    private fun meeting(
        weeks: List<Int>,
        begin: Int,
        end: Int
    ) = ShenzhenCourseMeeting(
        weeks = weeks,
        weekday = 2,
        beginPeriod = begin,
        endPeriod = end,
        teacher = "教师",
        location = "教室"
    )

    private fun schedule(count: Int): List<TimePeriodInDay> = (0 until count).map { index ->
        val hour = 8 + index
        TimePeriodInDay(TimeInDay(hour, 0), TimeInDay(hour, 45))
    }

    private fun monday(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 31, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
