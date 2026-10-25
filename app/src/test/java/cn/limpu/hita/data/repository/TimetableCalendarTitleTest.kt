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
    @Test fun weekendInsideSemesterIsNotVacation() {
        assertEquals(19, TimetableCalendarTitle.resolve(listOf(table()), millis("2027-01-10"), periods()).week)
    }
}
