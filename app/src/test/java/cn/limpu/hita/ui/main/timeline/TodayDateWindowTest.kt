package cn.limpu.hita.ui.main.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayDateWindowTest {
    @Test fun `window clears seconds and uses local calendar midnight`() {
        val window = TodayDateWindow.from(Instant.parse("2026-12-31T15:59:47.123Z"), ZoneId.of("Asia/Shanghai"))
        assertEquals(LocalDate.of(2026, 12, 31), window.date)
        assertEquals(Instant.parse("2026-12-30T16:00:00Z").toEpochMilli(), window.start)
        assertEquals(Instant.parse("2026-12-31T16:00:00Z").toEpochMilli(), window.tomorrowStart)
        assertEquals(Instant.parse("2027-01-01T16:00:00Z").toEpochMilli(), window.dayAfterStart)
    }

    @Test fun `DST windows use 23 and 25 hour days`() {
        val zone = ZoneId.of("America/New_York")
        val spring = TodayDateWindow.from(Instant.parse("2026-03-08T12:00:00Z"), zone)
        val autumn = TodayDateWindow.from(Instant.parse("2026-11-01T12:00:00Z"), zone)
        assertEquals(23 * 3_600_000L, spring.tomorrowStart - spring.start)
        assertEquals(25 * 3_600_000L, autumn.tomorrowStart - autumn.start)
    }

    @Test fun `minute ticks keep query date while midnight and zone changes replace it`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val instant = Instant.parse("2026-11-17T15:59:00Z")
        val window = TodayDateWindow.from(instant, zone)
        assertEquals(window, TodayDateWindow.from(instant.plusSeconds(30), zone))
        assertNotEquals(window, TodayDateWindow.from(instant.plusSeconds(60), zone))
        assertNotEquals(window, TodayDateWindow.from(instant, ZoneId.of("UTC")))
    }
}
