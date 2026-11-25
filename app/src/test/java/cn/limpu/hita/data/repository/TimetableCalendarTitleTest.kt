package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.web.eas.AcademicCalendarPeriod
import org.junit.Assert.*
import org.junit.Test
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

class TimetableCalendarTitleTest {
    private fun day(s: String) = LocalDate.parse(s).toEpochDay()
    private fun millis(s: String) = LocalDate.parse(s).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    private fun table(campus: String = "BENBU") = Timetable().apply {
        code = "$campus:2026-20271"
        startTime = Timestamp(millis("2026-08-31"))
        endTime = Timestamp(millis("2026-12-20"))
    }
    private fun periods(campus: String = "BENBU") = listOf(
        AcademicCalendarPeriod(day("2026-08-31"), day("2027-01-10"), false, campus),
        AcademicCalendarPeriod(day("2027-01-11"), day("2027-02-21"), true, campus),
    )
    @Test fun lastClassDoesNotEndSemesterForAnyCampus() {
        for (campus in listOf("BENBU", "SHENZHEN", "WEIHAI")) {
            assertEquals(19, TimetableCalendarTitle.resolve(listOf(table(campus)), millis("2027-01-04"), periods(campus)).week)
        }
    }
    @Test fun vacationHasExactBoundaries() {
        val t = listOf(table())
        assertFalse(TimetableCalendarTitle.resolve(t, millis("2027-01-10"), periods()).vacation)
        assertTrue(TimetableCalendarTitle.resolve(t, millis("2027-01-11"), periods()).vacation)
        assertTrue(TimetableCalendarTitle.resolve(t, millis("2027-02-21"), periods()).vacation)
        assertFalse(TimetableCalendarTitle.resolve(t, millis("2027-02-22"), periods()).vacation)
    }
    @Test fun noCrossCampusOrManualTimetableVacationAssumption() {
        assertFalse(TimetableCalendarTitle.resolve(listOf(table("SHENZHEN")), millis("2027-01-11"), periods()).vacation)
        assertFalse(TimetableCalendarTitle.resolve(listOf(table().apply { code = null }), millis("2027-01-11"), periods()).vacation)
        assertFalse(TimetableCalendarTitle.resolve(listOf(table()), millis("2027-01-11"), emptyList()).vacation)
    }
    @Test fun mixedCampusGroupRequiresAllCalendarsToConfirmVacation() {
        val group = listOf(table(), table("WEIHAI"))
        assertFalse(TimetableCalendarTitle.resolve(group, millis("2027-01-11"), periods()).vacation)
        assertTrue(TimetableCalendarTitle.resolve(group, millis("2027-01-11"), periods() + periods("WEIHAI")).vacation)
    }
    @Test fun personalTimetablePastItsEndStaysOutsideTerm() {
        // 表尾之外仍按「不在学期内」处理：这是空表长度为 20 周后依旧存在的边界。
        val t = Timetable().apply {
            code = null
            startTime = Timestamp(millis("2026-11-23"))
            endTime = Timestamp(millis("2026-12-06"))
        }
        assertEquals(2, TimetableCalendarTitle.resolve(listOf(t), millis("2026-11-30"), emptyList()).week)
        assertNull(TimetableCalendarTitle.resolve(listOf(t), millis("2026-12-07"), emptyList()).week)
    }

    @Test fun mondayBoundaryDropsSecondsAndMillis() {
        // 周起点必须沉到 00:00:00.000：残留秒会让「表起点」晚于页面周一 0 点，
        // 同一张表因此在时间表被判「非所选学期」而今日显示第 1 周。
        // 断言按设备默认时区构造，避免测试依赖运行环境的时区设置。
        val zone = ZoneId.systemDefault()
        val noon = LocalDate.parse("2026-11-23").atTime(12, 0).atZone(zone).toInstant().toEpochMilli() + 44_556L
        val expected = LocalDate.parse("2026-11-23").atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, cn.limpu.hita.utils.TimeTools.getMonday(noon).timeInMillis)
    }

    @Test fun secondPrecisionOnTableStartStillCountsFirstWeek() {
        val t = Timetable().apply {
            code = null
            startTime = Timestamp(millis("2026-11-23") + 44_556L)
            endTime = Timestamp(millis("2027-04-12"))
        }
        assertEquals(1, TimetableCalendarTitle.resolve(listOf(t), millis("2026-11-23"), emptyList()).week)
    }

    @Test fun weekendInsideSemesterIsNotVacation() {
        assertEquals(19, TimetableCalendarTitle.resolve(listOf(table()), millis("2027-01-10"), periods()).week)
    }
}
