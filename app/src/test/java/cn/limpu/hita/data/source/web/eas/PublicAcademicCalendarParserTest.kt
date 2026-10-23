package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PublicAcademicCalendarParserTest {
    private fun fixture(name: String) = javaClass.getResource("/calendar/$name")!!.readText()
    private fun day(date: String) = LocalDate.parse(date).toEpochDay()

    @Test fun realBenbuCalendarHandlesSplitMonthsAndWinterBreak() {
        val periods = PublicAcademicCalendarParser.parse(fixture("benbu-autumn-2026.html"))
        assertEquals(listOf(
            AcademicCalendarPeriod(day("2026-08-31"), day("2027-01-10"), false),
            AcademicCalendarPeriod(day("2027-01-11"), day("2027-02-21"), true),
        ), periods)
    }
    @Test fun realShenzhenCalendarIncludesAllNineteenWeeks() {
        assertEquals(listOf(AcademicCalendarPeriod(day("2026-08-31"), day("2027-01-10"), false, "SHENZHEN")),
            PublicAcademicCalendarParser.parseShenzhen(fixture("shenzhen-autumn-2026.html")))
    }
    @Test fun realWeihaiPdfTextIncludesExplicitWinterDates() {
        val periods = PublicAcademicCalendarParser.parseWeihaiText(fixture("weihai-autumn-2026.txt"))
        assertTrue(periods.contains(AcademicCalendarPeriod(day("2026-08-31"), day("2027-01-10"), false, "WEIHAI")))
        assertTrue(periods.contains(AcademicCalendarPeriod(day("2027-01-11"), day("2027-02-21"), true, "WEIHAI")))
        assertEquals(2, periods.size)
    }
    @Test fun missingVacationLabelDoesNotInventVacation() {
        assertTrue(PublicAcademicCalendarParser.parse(fixture("benbu-autumn-2026.html").replace("winter vacation", "未公布")).isEmpty())
    }
    @Test fun springTableWithoutVacationDoesNotLoseTeachingRange() {
        val html = "<title>Academic Year Spring Semester Calendar</title>" +
            fixture("benbu-autumn-2026.html").replace("winter vacation", "未公布")
        assertEquals(listOf(AcademicCalendarPeriod(day("2026-08-31"), day("2027-01-10"), false)),
            PublicAcademicCalendarParser.parse(html))
    }
    @Test fun malformedDatesAndMissingWeeksFailClosed() {
        assertTrue(PublicAcademicCalendarParser.parseWeihaiText("2027年2月30日——2027年3月8日 寒假").isEmpty())
        assertTrue(PublicAcademicCalendarParser.parseShenzhen(fixture("shenzhen-autumn-2026.html").replace("第18周", "未公布")).isEmpty())
        assertTrue(PublicAcademicCalendarParser.parse("<html>统一认证登录</html>").isEmpty())
    }
}
