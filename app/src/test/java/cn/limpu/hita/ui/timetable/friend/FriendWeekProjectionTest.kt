package cn.limpu.hita.ui.timetable.friend

import cn.limpu.hita.feature.timetableshare.protocol.*
import cn.limpu.hita.ui.main.timetable.TimetableDisplayTime
import cn.limpu.hita.ui.main.timetable.views.TimetableOverlapLayout
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.TimeZone

class FriendWeekProjectionTest {
    private fun time(s: String) = LocalDateTime.parse(s).atZone(FriendWeekProjection.ZONE).toInstant().toEpochMilli()
    private fun fixture(mode: ShareMode = ShareMode.FULL) = SharedTimetable(
        "friend-id", "friend", mode,
        SharedTerm("table", "term", time("2026-09-02T00:00"), time("2026-09-17T00:00")),
        listOf(SharedPeriod(480, 600)), if (mode == ShareMode.FULL) listOf("Math") else emptyList(),
        listOf(SharedOccurrence(time("2026-09-05T23:00"), time("2026-09-06T01:00"), if (mode == ShareMode.FULL) 0 else null, if (mode == ShareMode.FULL) "room" else null)))

    @Test fun projectionIsIndependentOfDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            val snapshot = fixture()
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val monday = FriendWeekProjection.firstMonday(snapshot)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            assertEquals(monday, FriendWeekProjection.firstMonday(snapshot))
            assertEquals(time("2026-08-31T00:00"), monday)
            assertEquals(1, FriendWeekProjection.weekNumber(snapshot, monday))
            assertEquals(monday, FriendWeekProjection.initialMonday(snapshot, time("2026-10-01T00:00")))
            assertEquals(monday, FriendWeekProjection.initialMonday(snapshot, time("2026-08-31T12:00")))
            assertEquals(time("2026-09-14T00:00"), FriendWeekProjection.moveWeek(snapshot, monday, 99))
            assertEquals(monday, FriendWeekProjection.moveWeek(snapshot, monday, -99))
            assertEquals(time("2026-09-07T00:00"), FriendWeekProjection.initialMonday(snapshot, time("2026-09-10T15:00")))
            assertEquals(3, FriendWeekProjection.weekNumber(snapshot, time("2026-09-14T00:00")))
            assertTrue(FriendWeekProjection.events(snapshot, time("2026-09-07T00:00"), "Busy").isEmpty())
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun midnightSegmentsHaveUniqueIdsOriginalDetailAndFullRange() {
        val snapshot = fixture()
        val events = FriendWeekProjection.events(snapshot, FriendWeekProjection.firstMonday(snapshot), "Busy")
        assertEquals(2, events.size)
        assertEquals(2, events.map { it.id }.distinct().size)
        assertEquals(2, TimetableOverlapLayout.arrange(events, { TimetableDisplayTime.dayOfWeek(it.from.time, FriendWeekProjection.ZONE) }).size)
        assertEquals(1440, TimetableDisplayTime.endMinutes(events[0], FriendWeekProjection.ZONE))
        assertEquals(0 to 24, TimetableDisplayTime.hourRange(events, 8, 10, FriendWeekProjection.ZONE))
        events.forEach { assertEquals(snapshot.occurrences[0], FriendWeekProjection.originalOccurrence(snapshot, it)) }
        assertEquals(6, TimetableDisplayTime.dayOfWeek(events[0].from.time, FriendWeekProjection.ZONE))
        assertEquals(7, TimetableDisplayTime.dayOfWeek(events[1].from.time, FriendWeekProjection.ZONE))
        val busy = FriendWeekProjection.events(fixture(ShareMode.BUSY), FriendWeekProjection.firstMonday(snapshot), "Busy")
        assertTrue(busy.all { it.name == "Busy" && it.place == "" && it.teacher == "" })
    }
    @Test fun helpersAndConflictsUseExplicitZone() {
        val original = TimeZone.getDefault()
        try {
            val snapshot = fixture().copy(occurrences = listOf(
                SharedOccurrence(time("2026-09-03T07:30"), time("2026-09-03T09:30"), 0, ""),
                SharedOccurrence(time("2026-09-03T08:30"), time("2026-09-03T10:00"), 0, "")))
            for (device in listOf("America/New_York", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(device))
                val events = FriendWeekProjection.events(snapshot, FriendWeekProjection.firstMonday(snapshot), "Busy")
                val dow = { e: cn.limpu.hita.data.model.timetable.EventItem -> TimetableDisplayTime.dayOfWeek(e.from.time, FriendWeekProjection.ZONE) }
                assertEquals(4, dow(events[0]))
                assertEquals(3, TimetableDisplayTime.calendar(events[0].from.time, FriendWeekProjection.ZONE).get(java.util.Calendar.DAY_OF_MONTH))
                assertEquals(450, TimetableDisplayTime.minutes(events[0].from.time, FriendWeekProjection.ZONE))
                assertTrue(TimetableDisplayTime.isInWeek(FriendWeekProjection.firstMonday(snapshot), events[0].from.time, FriendWeekProjection.ZONE))
                val cards = TimetableOverlapLayout.conflictCards(TimetableOverlapLayout.arrange(events, dow), dow)
                assertEquals(2, cards.single().second!!.size)
            }
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun overlappingSameCampusDayCrossesForeignDeviceMidnight() {
        val original = TimeZone.getDefault()
        try {
            for ((device, hour) in listOf("America/New_York" to 11, "Pacific/Auckland" to 19, "UTC" to 7)) {
                TimeZone.setDefault(TimeZone.getTimeZone(device))
                val first = time("2026-09-03T${hour.toString().padStart(2, '0')}:30")
                val snapshot = fixture().copy(occurrences = listOf(SharedOccurrence(first, first + 120 * 60000, 0, ""),
                    SharedOccurrence(first + 60 * 60000, first + 150 * 60000, 0, "")))
                val events = FriendWeekProjection.events(snapshot, FriendWeekProjection.firstMonday(snapshot), "Busy")
                assertNotEquals(events[0].getDow(), events[1].getDow())
                val dow = { e: cn.limpu.hita.data.model.timetable.EventItem -> TimetableDisplayTime.dayOfWeek(e.from.time, FriendWeekProjection.ZONE) }
                assertEquals(2, TimetableOverlapLayout.conflictCards(TimetableOverlapLayout.arrange(events, dow), dow).single().second!!.size)
            }
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun weekBoundaryOverlapKeepsSecondDayAndMidnightEndDoesNotCreateExtraDay() {
        val snapshot = fixture().copy(occurrences = listOf(
            SharedOccurrence(time("2026-09-06T23:00"), time("2026-09-07T01:00"), 0, ""),
            SharedOccurrence(time("2026-09-07T23:00"), time("2026-09-08T00:00"), 0, "")))
        val events = FriendWeekProjection.events(snapshot, time("2026-09-07T00:00"), "Busy")
        assertEquals(2, events.size)
        assertEquals(time("2026-09-07T00:00"), events[0].from.time)
        assertEquals(1440, TimetableDisplayTime.endMinutes(events[1], FriendWeekProjection.ZONE))
    }
}
