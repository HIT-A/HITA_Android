package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import org.junit.Assert.*
import org.junit.Test
import java.sql.Timestamp
import java.time.ZoneId
import java.time.ZonedDateTime

class TimetableGridGeometryTest {
    @Test fun gridUsesSevenColumnsAndKeepsMinimumCardHeightWithoutNegativeWidth() {
        val monday = TimetableGridGeometry.card(1, 510, 10, 8, 100f, 2f)
        val sunday = TimetableGridGeometry.card(7, 510, 10, 8, 100f, 2f)
        assertEquals(TimetableGridGeometry.Card(2f, 60f, 96f, 30f), monday)
        assertEquals(602f, sunday.x, 0f)
        assertEquals(0f, TimetableGridGeometry.card(1, 470, 60, 8, 0f, 0f).width, 0f)
        assertEquals(0f, TimetableGridGeometry.card(1, 470, 60, 8, 0f, 0f).height, 0f)
        assertNull(TimetableGridGeometry.dayAt(5f, 0f))
        assertEquals(1, TimetableGridGeometry.dayAt(0f, 700f))
        assertEquals(7, TimetableGridGeometry.dayAt(700f, 700f))
        assertEquals(4, TimetableGridGeometry.dayAt(350f, 700f))
    }

    @Test fun hitTestPreservesBeforeBetweenAndAfterPeriodsAtCompressedAndExpandedScales() {
        val schedule = listOf(period(8, 30, 9, 15), period(9, 30, 10, 15))
        for (scale in listOf(0.12f, 1f, 3f)) {
            fun hit(minutes: Int) = TimetableGridGeometry.periodAt(minutes * scale, 8, 22, scale, schedule)
            assertPeriod(period(8, 0, 8, 30), hit(0))
            assertPeriod(schedule[0], hit(45))
            assertPeriod(period(9, 15, 9, 30), hit(80))
            assertPeriod(schedule[1], hit(100))
            assertPeriod(period(10, 15, 22, 0), hit(200))
        }
        assertNull(TimetableGridGeometry.periodAt(1f, 8, 22, 0f, schedule))
        assertNull(TimetableGridGeometry.periodAt(-1f, 8, 22, 1f, schedule))
        assertNull(TimetableGridGeometry.periodAt(1f, 8, 22, 1f, emptyList()))
    }

    @Test fun compressedDensitySwipeThresholdAndEveningHintRespectMeasuredBounds() {
        assertNull(TimetableGridGeometry.compressedScale(400f, 0))
        assertEquals(0.12f, TimetableGridGeometry.compressedScale(-20f, 840)!!, 0f)
        assertEquals(0.5f, TimetableGridGeometry.compressedScale(420f, 840)!!, 0f)
        assertEquals(5, TimetableGridGeometry.labelStep(0.12f))
        assertEquals(1, TimetableGridGeometry.labelStep(1f))
        assertEquals(0, TimetableGridGeometry.swipe(15f, 100f))
        assertEquals(-1, TimetableGridGeometry.swipe(15.1f, 100f))
        assertEquals(1, TimetableGridGeometry.swipe(-15.1f, 100f))
        assertEquals(0, TimetableGridGeometry.swipe(15f, 0f))
        assertTrue(TimetableGridGeometry.eveningHint(true, true, 0, 500, 64f))
        assertFalse(TimetableGridGeometry.eveningHint(true, true, 436, 500, 64f))
        assertFalse(TimetableGridGeometry.eveningHint(true, true, 0, 0, 64f))
        assertFalse(TimetableGridGeometry.eveningHint(false, true, 0, 500, 64f))
    }

    @Test fun explicitZoneKeepsMidnightEndAndCalendarWeekAcrossDaylightSavingTransition() {
        val zone = ZoneId.of("America/New_York")
        val monday = ZonedDateTime.of(2026, 3, 2, 0, 0, 0, 0, zone)
        val sundayNight = ZonedDateTime.of(2026, 3, 8, 23, 0, 0, 0, zone)
        val nextMonday = monday.plusWeeks(1)
        val event = EventItem().apply {
            from = Timestamp(sundayNight.toInstant().toEpochMilli())
            to = Timestamp(nextMonday.toInstant().toEpochMilli())
        }
        assertEquals(7, TimetableDisplayTime.dayOfWeek(event.from.time, zone))
        assertTrue(TimetableDisplayTime.isInWeek(monday.toInstant().toEpochMilli(), event.from.time, zone))
        assertFalse(TimetableDisplayTime.isInWeek(monday.toInstant().toEpochMilli(), event.to.time, zone))
        assertEquals(1440, TimetableDisplayTime.endMinutes(event, zone))
        assertEquals(8 to 24, TimetableDisplayTime.hourRange(listOf(event), 8, 22, zone))
        assertEquals(1380, TimetableDisplayTime.minutes(event.from.time, zone))
    }

    private fun period(a: Int, b: Int, c: Int, d: Int) = TimePeriodInDay(TimeInDay(a, b), TimeInDay(c, d))
    private fun assertPeriod(expected: TimePeriodInDay, actual: TimePeriodInDay?) {
        assertNotNull(actual)
        assertEquals(expected.from.toMills(), actual!!.from.toMills())
        assertEquals(expected.to.toMills(), actual.to.toMills())
    }
}
