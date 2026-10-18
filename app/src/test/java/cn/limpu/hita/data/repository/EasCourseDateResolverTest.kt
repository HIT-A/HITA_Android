package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.CourseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EasCourseDateResolverTest {
    private fun termStart() = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.AUGUST, 31, 0, 0, 0)
    }

    private fun circuitCourse() = CourseItem().apply {
        name = "电路实验"
        scheduledDate = "2026-10-20"
        // 旧解析用 9 月 14 日推算开学日，会把这节课误记为第 6 周。
        weeks = mutableListOf(6)
        dow = 2
        begin = -1
        last = -1
        startTime = "13:00"
        endTime = "18:10"
    }

    private fun assertDate(expected: String, actual: Calendar) {
        val date = "%04d-%02d-%02d".format(
            actual.get(Calendar.YEAR),
            actual.get(Calendar.MONTH) + 1,
            actual.get(Calendar.DAY_OF_MONTH)
        )
        assertEquals(expected, date)
    }

    @Test
    fun originalDateDoesNotMoveTwoWeeksEarlierWhenTermStartsOnAugust31() {
        val dates = resolveEasCourseDates(circuitCourse(), termStart())
        assertEquals(1, dates.size)
        assertDate("2026-10-20", dates.single())
    }

    @Test
    fun originalDateIsIndependentOfWeekAndWeekdayMetadata() {
        val course = circuitCourse().apply {
            weeks = mutableListOf(10, 12)
            dow = 5
        }
        assertDate("2026-10-20", resolveEasCourseDates(course, termStart()).single())
    }

    @Test
    fun firstTeachingDayBeforeTheOldEstimatedStartIsNotDropped() {
        val course = circuitCourse().apply {
            scheduledDate = "2026-08-31"
            weeks.clear()
            dow = 1
        }
        assertDate("2026-08-31", resolveEasCourseDates(course, termStart()).single())
    }

    @Test
    fun weeklyCoursesStillUseTheirTeachingWeeks() {
        val course = circuitCourse().apply {
            scheduledDate = null
            weeks = mutableListOf(6, 8)
        }
        val dates = resolveEasCourseDates(course, termStart())
        assertEquals(2, dates.size)
        assertDate("2026-10-06", dates[0])
        assertDate("2026-10-20", dates[1])
    }

    @Test
    fun originalSundayDateIsPreserved() {
        val course = circuitCourse().apply { scheduledDate = "2026-10-18" }
        val date = resolveEasCourseDates(course, termStart()).single()
        assertDate("2026-10-18", date)
        assertEquals(Calendar.SUNDAY, date.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun originalDateCanCrossIntoTheNextCalendarYear() {
        val course = circuitCourse().apply { scheduledDate = "2027-01-05" }
        assertDate("2027-01-05", resolveEasCourseDates(course, termStart()).single())
    }

    @Test
    fun invalidExplicitDateDoesNotFallBackToTheWrongWeek() {
        listOf("2026-02-30", "2026-13-01", "invalid", "").forEach { invalid ->
            val course = circuitCourse().apply { scheduledDate = invalid }
            assertTrue(resolveEasCourseDates(course, termStart()).isEmpty())
        }
    }

    @Test
    fun resolvingAnExplicitDatePreservesTimeZoneWithoutMutatingTermStart() {
        val start = termStart().apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
        val originalMillis = start.timeInMillis
        val date = resolveEasCourseDates(circuitCourse(), start).single()
        assertEquals(originalMillis, start.timeInMillis)
        assertEquals(start.timeZone.id, date.timeZone.id)
        assertEquals(0, date.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, date.get(Calendar.MINUTE))
        assertEquals(0, date.get(Calendar.SECOND))
        assertEquals(0, date.get(Calendar.MILLISECOND))
        assertDate("2026-10-20", date)
    }
}