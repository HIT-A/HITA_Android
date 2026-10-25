package cn.limpu.hita.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ExperimentChangeMatchingTest {
    private fun lesson(hour: Int, place: String, week: Int = 1) = MergeLesson(
        name = "操作系统实验", place = place, teacher = "教师$hour",
        fromMillis = time(hour, week), toMillis = time(hour + 2, week),
        fromNumber = 0, lastNumber = 0, weekOfTerm = week,
    )
    private fun time(hour: Int, week: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 7, hour, 0)
        add(Calendar.DATE, (week - 1) * 7)
    }.timeInMillis

    private fun apply(local: List<MergeLesson>, incoming: List<MergeLesson>, vararg kinds: PendingChangeKind): List<MergeLesson> {
        val oldCourse = MergeCourse("s1", "操作系统实验", "OS", local)
        val newCourse = oldCourse.copy(lessons = incoming)
        val update = TimetableRefreshMergePolicy.plan(listOf(oldCourse), listOf(newCourse), emptyMap(), 1000L).pendingUpdates.single()
        val adopted = update.rows.filter { it.kind in kinds }.mapTo(mutableSetOf()) { it.fingerprint }
        return CourseChangeConfirm.applyRows(oldCourse, newCourse, adopted, update.rows)!!.lessons
    }

    @Test fun twoSessionsKeepTheirOwnRoomsEvenWhenIncomingOrderChanges() {
        val local = listOf(lesson(8, "A"), lesson(14, "B"))
        val actual = apply(local, listOf(lesson(14, "B2"), lesson(8, "A2")), PendingChangeKind.PLACE)
        assertEquals(listOf("A2", "B2"), actual.map { it.place })
        assertEquals(local.map { it.fromMillis }, actual.map { it.fromMillis })
        assertEquals(local.map { it.teacher }, actual.map { it.teacher })
    }
    @Test fun movingMorningPastAfternoonDoesNotOverwriteUnchangedSession() {
        val local = listOf(lesson(8, "A"), lesson(14, "B"))
        val actual = apply(local, listOf(lesson(14, "B2"), lesson(18, "A2")), PendingChangeKind.TIME)
        assertEquals(listOf(time(14, 1), time(18, 1)), actual.map { it.fromMillis })
        assertEquals(listOf("B", "A"), actual.map { it.place })
    }
    @Test fun acceptingTimeAddsSecondSessionInSameWeek() {
        val actual = apply(listOf(lesson(8, "A")), listOf(lesson(8, "A"), lesson(14, "B")), PendingChangeKind.TIME)
        assertEquals(listOf(time(8, 1), time(14, 1)), actual.map { it.fromMillis })
    }
    @Test fun ignoringTimeRetainsLocalSessionCount() {
        val local = listOf(lesson(8, "A"), lesson(14, "B"))
        val actual = apply(local, listOf(lesson(14, "B2"), lesson(18, "C", 2)), PendingChangeKind.PLACE)
        assertEquals(listOf("A", "B2"), actual.map { it.place })
        assertEquals(local.map { it.fromMillis }, actual.map { it.fromMillis })
    }
    @Test fun acceptingTimeRemovesOnlyMissingSession() {
        val actual = apply(listOf(lesson(8, "A"), lesson(14, "B")), listOf(lesson(14, "B"), lesson(18, "C", 2)), PendingChangeKind.TIME)
        assertEquals(listOf(time(14, 1)), actual.map { it.fromMillis })
    }
    @Test fun weekChangesRetainAllSessionsInAddedWeeks() {
        val actual = apply(listOf(lesson(8, "A")), listOf(lesson(8, "A"), lesson(8, "A", 2), lesson(14, "B", 2)), PendingChangeKind.WEEKS)
        assertEquals(listOf(1, 2, 2), actual.map { it.weekOfTerm })
    }
}
