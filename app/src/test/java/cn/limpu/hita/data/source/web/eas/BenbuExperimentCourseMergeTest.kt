package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.resolveEasCourseDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BenbuExperimentCourseMergeTest {
    private val source = BenbuEASWebSource()
    private val schedule = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)

    private fun experiment(
        name: String = "电路实验",
        date: String = "2026-10-20",
        start: String = "08:00",
        end: String = "10:00",
        teacher: String = "教师甲",
        room: String = "实验室 101"
    ) = CourseItem().apply {
        this.name = name
        scheduledDate = date
        dow = 2
        startTime = start
        endTime = end
        this.teacher = teacher
        classroom = room
        begin = -1
        last = -1
    }

    private fun merge(vararg courses: CourseItem) =
        source.mergeAdjacentCourses(courses.toList(), schedule)

    @Test
    fun differentExperimentsOnTheSameDayAreAllKept() {
        val merged = merge(
            experiment("电路实验", start = "08:00", end = "10:00"),
            experiment("电磁实验", start = "13:00", end = "15:00"),
            experiment("电子实验", start = "16:00", end = "18:00")
        )
        assertEquals(listOf("电路实验", "电磁实验", "电子实验"), merged.map { it.name })
        assertEquals(listOf("08:00", "13:00", "16:00"), merged.map { it.startTime })
        assertTrue(merged.all { it.scheduledDate == "2026-10-20" })
    }

    @Test
    fun sameExperimentOnTheSameDayKeepsDifferentTimeSlots() {
        val merged = merge(
            experiment(),
            experiment(start = "13:00", end = "15:00")
        )
        assertEquals(2, merged.size)
        assertEquals(listOf("08:00", "13:00"), merged.map { it.startTime })
        assertEquals(listOf("10:00", "15:00"), merged.map { it.endTime })
    }

    @Test
    fun adjacentExperimentSessionsAreNotJoinedIntoOneLongSession() {
        val merged = merge(
            experiment(),
            experiment(start = "10:00", end = "12:00")
        )
        assertEquals(2, merged.size)
        assertEquals(listOf("10:00", "12:00"), merged.map { it.endTime })
    }

    @Test
    fun overlappingSessionsAreBothPreserved() {
        val merged = merge(
            experiment(),
            experiment(start = "09:00", end = "11:00")
        )
        assertEquals(2, merged.size)
        assertEquals(listOf("08:00", "09:00"), merged.map { it.startTime })
    }

    @Test
    fun sameTimeSessionsWithDifferentNamesTeachersOrRoomsAreKept() {
        val merged = merge(
            experiment(),
            experiment("电磁实验"),
            experiment(teacher = "教师乙"),
            experiment(room = "实验室 102")
        )
        assertEquals(4, merged.size)
    }

    @Test
    fun sameWeekdayAndTimeOnDifferentDatesAreNotDeduplicated() {
        val merged = merge(
            experiment(date = "2026-10-20"),
            experiment(date = "2026-10-27")
        )
        assertEquals(listOf("2026-10-20", "2026-10-27"), merged.map { it.scheduledDate })
        val termStart = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.AUGUST, 31)
        }
        val dates = merged.flatMap { resolveEasCourseDates(it, termStart) }
        assertEquals(listOf(20, 27), dates.map { it.get(Calendar.DAY_OF_MONTH) })
    }

    @Test
    fun identicalSourceRecordsAreStillDeduplicated() {
        val merged = merge(experiment(), experiment())
        assertEquals(1, merged.size)
    }

    @Test
    fun weeklyLessonsKeepTheirExistingMergeAndImportBehavior() {
        val first = CourseItem().apply {
            name = "高等数学"
            weeks = mutableListOf(8)
            dow = 2
            begin = 1
            last = 1
        }
        val second = CourseItem().apply {
            name = "高等数学"
            weeks = mutableListOf(8)
            dow = 2
            begin = 2
            last = 1
        }
        val merged = merge(first, second, experiment(), experiment(start = "13:00", end = "15:00"))
        assertEquals(3, merged.size)
        val weekly = merged.single { it.scheduledDate == null }
        assertEquals(1, weekly.begin)
        assertEquals(2, weekly.last)
        assertEquals(listOf(8), weekly.weeks)
        val termStart = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.AUGUST, 31)
        }
        val date = resolveEasCourseDates(weekly, termStart).single()
        assertEquals(Calendar.OCTOBER, date.get(Calendar.MONTH))
        assertEquals(20, date.get(Calendar.DAY_OF_MONTH))
    }
}
