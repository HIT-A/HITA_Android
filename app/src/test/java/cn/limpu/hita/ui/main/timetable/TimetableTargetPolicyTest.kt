package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import org.junit.Assert.*
import org.junit.Test
import java.sql.Timestamp
import java.time.Instant

class TimetableTargetPolicyTest {
    @Test fun earliestInTermTableOwnsAddingButScheduleFallsBackOutsideAllTerms() {
        val early = table("early", 10, 30)
        val late = table("late", 20, 40)
        assertSame(early, TimetableTargetPolicy.hitTimetable(listOf(late, early), 25))
        assertSame(early, TimetableTargetPolicy.hitTimetable(listOf(late, early), 30))
        assertSame(late, TimetableTargetPolicy.hitTimetable(listOf(late, early), 31))
        assertNull(TimetableTargetPolicy.hitTimetable(listOf(early, late), 50))
        assertSame(early, TimetableTargetPolicy.scheduleTimetable(listOf(late, early), 50))
        assertNull(TimetableTargetPolicy.scheduleTimetable(emptyList(), 50))
    }

    @Test fun thisSlotAndAllRetainTheSelectedOccurrenceAndExcludeOtherEventTypes() {
        val selected = event("first")
        val nextWeek = event("next", "2026-10-19T02:00:00Z")
        val anotherSlot = event("other").apply { fromNumber = 5 }
        val exam = event("exam").apply { type = EventItem.TYPE.EXAM }
        val all = listOf(selected, nextWeek, anotherSlot, exam)
        assertEquals(listOf("first"), ids(TimetableTargetPolicy.targets(selected, all, TimetableBatchScope.THIS)))
        assertEquals(listOf("first", "next"), ids(TimetableTargetPolicy.targets(selected, all, TimetableBatchScope.SLOT)))
        assertEquals(listOf("first", "next", "other"), ids(TimetableTargetPolicy.targets(selected, all, TimetableBatchScope.ALL)))
        for (scope in TimetableBatchScope.entries) assertEquals(listOf("first"), ids(TimetableTargetPolicy.targets(selected, emptyList(), scope)))
        assertEquals(listOf("exam"), ids(TimetableTargetPolicy.targets(exam, emptyList(), TimetableBatchScope.THIS)))
    }

    @Test fun projectionsAndForeignTargetsCannotEnterMutationAndSelectionIsDetachedFromRoomObjects() {
        val selected = event("first")
        val readonly = event("readonly").apply { source = "${EventItem.SOURCE_COURSE_PLAN}:plan" }
        val foreign = event("foreign").apply { timetableId = "other-table" }
        val otherSubject = event("subject").apply { subjectId = "other-subject" }
        for (scope in TimetableBatchScope.entries) assertTrue(TimetableTargetPolicy.targets(readonly, listOf(selected), scope).isEmpty())
        val targets = TimetableTargetPolicy.targets(selected, listOf(selected, readonly, foreign, otherSubject), TimetableBatchScope.ALL)
        assertEquals(listOf("first"), ids(targets))
        targets.single().place = "edited"
        targets.single().from.time = 123
        assertEquals("original", selected.place)
        assertNotEquals(123L, selected.from.time)
    }

    private fun ids(events: List<EventItem>) = events.map { it.id }
    private fun table(id: String, start: Long, end: Long) = Timetable().apply { this.id = id; startTime = Timestamp(start); endTime = Timestamp(end) }
    private fun event(id: String, date: String = "2026-10-12T02:00:00Z") = EventItem().apply {
        this.id = id; subjectId = "subject"; timetableId = "table"; fromNumber = 3; lastNumber = 2
        from = Timestamp(Instant.parse(date).toEpochMilli()); to = Timestamp(from.time + 60 * 60_000)
        place = "original"
    }
}
