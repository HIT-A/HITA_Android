package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayClockTest {
    private val window = TodayDateWindow.from(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val noon = window.start + 12 * 3_600_000L
    private fun event(id: String, from: Long, to: Long) = EventItem().apply {
        this.id = id; name = id; this.from = Timestamp(from); this.to = Timestamp(to)
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
