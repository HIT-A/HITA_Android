package cn.limpu.hita.data.model.timetable

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Timestamp
import java.util.Calendar

class TimetableWeekNumberTest {
    @Test
    fun `week number uses floor weeks from normalized term monday`() {
        val timetable = Timetable().apply {
            startTime = Timestamp(millis(2026, Calendar.FEBRUARY, 23, 9, 30, 45, 250))
            endTime = Timestamp(millis(2026, Calendar.JUNE, 28, 23, 59, 0, 0))
        }

        assertEquals(1, timetable.getWeekNumber(millis(2026, Calendar.FEBRUARY, 23)))
        assertEquals(1, timetable.getWeekNumber(millis(2026, Calendar.MARCH, 1)))
        assertEquals(2, timetable.getWeekNumber(millis(2026, Calendar.MARCH, 2)))
        assertEquals(15, timetable.getWeekNumber(millis(2026, Calendar.JUNE, 1)))
    }

    @Test
    fun `week number returns holiday outside timetable range`() {
        val timetable = Timetable().apply {
            startTime = Timestamp(millis(2026, Calendar.FEBRUARY, 23))
            endTime = Timestamp(millis(2026, Calendar.JUNE, 28, 23, 59, 0, 0))
        }

        assertEquals(-1, timetable.getWeekNumber(millis(2026, Calendar.FEBRUARY, 16)))
        assertEquals(-1, timetable.getWeekNumber(millis(2026, Calendar.JUNE, 29)))
    }

    @Test
    fun `week number ignores device time zone`() {
        // 课表时间戳是校区墙钟。设备在海外或 CI 用 UTC 时，周日按默认时区会被
        // 回退到前一周一，周次少算一周；这里显式切时区把契约钉死。
        val shanghai = millis("2026-11-23")
        val timetable = Timetable().apply {
            startTime = Timestamp(shanghai)
            endTime = Timestamp(millis("2026-12-06"))
        }
        val monday = millis("2026-11-30")
        val sunday = millis("2026-12-06")
        val afterEnd = millis("2026-12-07")

        val original = java.util.TimeZone.getDefault()
        try {
            for (zoneId in listOf("UTC", "America/New_York", "Asia/Shanghai")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zoneId))
                assertEquals("$zoneId monday", 2, timetable.getWeekNumber(monday))
                assertEquals("$zoneId sunday", 2, timetable.getWeekNumber(sunday))
                assertEquals("$zoneId after end", -1, timetable.getWeekNumber(afterEnd))
            }
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    private fun millis(s: String): Long =
        java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
        millisecond: Int = 0,
    ): Long {
        // 课表时间戳是校区墙钟，必须按中国时区构造；用默认时区会让测试语义
        // 随运行环境（本地与 CI 的 UTC）改变。
        return Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, millisecond)
        }.timeInMillis
    }
}
