package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayHeaderResolverTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val window = TodayDateWindow.from(Instant.parse("2026-11-17T04:00:00Z"), zone)
    private val noon = window.start + 12 * 3_600_000L
    private val schedule = listOf(480 to 690, 810 to 1020)
    private fun event(id: String, from: Long, to: Long) = EventItem().apply {
        this.id = id; name = id; this.from = Timestamp(from); this.to = Timestamp(to)
    }
    @Test fun mealGreetingDoesNotHideImminentEventAndCountdownRoundsUp() {
        val agenda = TodayProjection.project(window, listOf(event("soon", noon + 20_000, noon + 90_000)), noon)
        val result = TodayHeaderResolver.resolve(noon, zone, agenda, listOf(schedule))
        assertEquals(TodayGreeting.LUNCH, result.greeting)
        assertEquals(TodayFocus.UPCOMING, result.focus)
        assertEquals(1L, result.remainingMinutes)
        assertEquals("soon", result.title)
    }
    @Test fun mismatchedOrMissingMemberSchedulesUseTimeGreeting() {
        assertEquals(TodayGreeting.AFTERNOON, TodayHeaderResolver.resolve(noon, zone, null, listOf(schedule, emptyList())).greeting)
        assertEquals(TodayGreeting.AFTERNOON, TodayHeaderResolver.resolve(noon, zone, null, emptyList()).greeting)
    }
    @Test fun activeConflictDoesNotInventSingleTitleOrProgress() {
        val agenda = TodayProjection.project(window, listOf(event("a", noon - 100, noon + 100), event("b", noon - 100, noon + 100)), noon)
        val result = TodayHeaderResolver.resolve(noon, zone, agenda, listOf(schedule))
        assertEquals(TodayFocus.ACTIVE, result.focus)
        assertEquals(2, result.count)
        assertNull(result.title); assertNull(result.progress); assertNull(result.remainingMinutes)
    }
    @Test fun endBoundarySwitchesFocusWithoutAZeroMinuteActiveState() {
        val agenda = TodayProjection.project(window, listOf(event("a", noon - 100, noon)), noon)
        assertEquals(TodayFocus.FINISHED, TodayHeaderResolver.resolve(noon, zone, agenda, emptyList()).focus)
    }
    @Test fun clockWakesAtEventBoundaryBeforeNextMinute() {
        assertEquals(noon + 12_345, TodayClock.nextWake(noon, window, listOf(event("a", noon + 12_345, noon + 70_000))))
        assertEquals(noon + 60_000, TodayClock.nextWake(noon, window, emptyList()))
    }
    @Test fun clockWakesAtPreparationAndMidnight() {
        assertEquals(window.preparationStart, TodayClock.nextWake(window.preparationStart - 123, window, emptyList()))
        assertEquals(window.tomorrowStart, TodayClock.nextWake(window.tomorrowStart - 123, window, emptyList()))
    }
    @Test fun invalidEventsDoNotIntroduceClockBoundaries() {
        assertEquals(noon + 60_000, TodayClock.nextWake(noon, window, listOf(event("bad", noon + 10, noon + 5))))
    }
}
