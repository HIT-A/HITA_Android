package cn.limpu.hita.data.source.web.eas.graduate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraduateTimetableProtocolTest {
    @Test fun termsUseAuthoritativeCurrentAndRejectDuplicates() {
        val terms = GraduateTimetableProtocol.terms(
            """{"XN":"2026-2027","XQ":"1","XNXQ":"2026秋季"}""",
            optionsBody = """[
                {"XN":"2025-2026","XQ":"2","XNMC":"2025-2026","XQMC":"春季"},
                {"XN":"2026-2027","XQ":"1","XNMC":"2026-2027","XQMC":"秋季"}
            ]""",
        )
        assertEquals(listOf("2025-2026-2", "2026-2027-1"), terms.map { it.id })
        assertEquals(listOf(false, true), terms.map { it.isCurrent })
        assertEquals("2026-2027秋季", terms.last().name)
    }

    @Test fun publicationUnknownIsNotAnEmptyTimetable() {
        assertEquals(GraduateTimetableProtocol.TimetablePublication.AVAILABLE, GraduateTimetableProtocol.publication("1"))
        assertEquals(GraduateTimetableProtocol.TimetablePublication.UNPUBLISHED, GraduateTimetableProtocol.publication("0"))
        assertEquals(GraduateTimetableProtocol.TimetablePublication.EVALUATION_REQUIRED, GraduateTimetableProtocol.publication("2"))
        val failure = runCatching { GraduateTimetableProtocol.publication("true") }.exceptionOrNull()
        assertTrue(failure is GraduateInvalidResponseException)
    }

    @Test fun runtimeFieldsRequireExactlyOneGraduateValue() {
        val html = "<script>var p_pylx='2'; var p_sfgldjr='1'; var p_sfredis='1'; var p_sfsyxkgwc='0';</script>"
        val fields = GraduateTimetableProtocol.runtimeFields(html)
        assertEquals("2", fields["p_pylx"])
        assertEquals("1", fields["p_sfredis"])
        assertTrue(runCatching {
            GraduateTimetableProtocol.runtimeFields("<script>var p_pylx='2';</script>")
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            GraduateTimetableProtocol.runtimeFields(html.replace("p_pylx='2'", "p_pylx='1'"))
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun scheduleIsSortedConsecutiveAndConvertedToBeijingTime() {
        val schedule = GraduateTimetableProtocol.schedule(
            """{"kbjclist":[
                {"XN":"2026-2027","XQ":"1","XJ":2,"KSSJ":"2024-11-01T01:00:00.000+00:00","JSSJ":"2024-11-01T01:50:00.000+00:00"},
                {"XN":"2026-2027","XQ":"1","XJ":1,"KSSJ":"2024-11-01T00:00:00.000+00:00","JSSJ":"2024-11-01T00:50:00.000+00:00"}
            ]}""",
            "2026-2027",
            "1",
        )
        assertEquals(listOf(8 to 0, 9 to 0), schedule.map { it.from.hour to it.from.minute })
        assertTrue(runCatching {
            GraduateTimetableProtocol.schedule(
                """{"kbjclist":[{"XN":"2026-2027","XQ":"2","XJ":1,"KSSJ":"08:00","JSSJ":"08:50"}]}""",
                "2026-2027",
                "1",
            )
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun calendarDerivesOneStartAndRejectsDisagreement() {
        val start = GraduateTimetableProtocol.termStartDate(
            """{"xlList":[
                {"XNXQ":"2026-20271","ZC":1,"RQ":"2026-08-31","MON":"2026-08-31"},
                {"XNXQ":"2026-20271","ZC":2,"RQ":"2026-09-07","MON":"2026-09-07"}
            ]}""",
            "2026-2027",
            "1",
        )
        assertEquals("2026-08-31", start.toString())
        assertTrue(runCatching {
            GraduateTimetableProtocol.termStartDate(
                """{"xlList":[
                    {"XNXQ":"2026-20271","ZC":1,"RQ":"2026-08-31","MON":"2026-08-31"},
                    {"XNXQ":"2026-20271","ZC":2,"RQ":"2026-09-08","MON":"2026-09-08"}
                ]}""",
                "2026-2027",
                "1",
            )
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun bitsetAndWeekRangesKeepDiscreteWeeks() {
        val bitset = CharArray(34) { '0' }.also { it[2] = '1'; it[9] = '1'; it[33] = '1' }.concatToString()
        assertEquals(listOf(2, 9, 33), GraduateTimetableProtocol.weeks(bitset))
        assertEquals(listOf(1, 3, 5), GraduateTimetableProtocol.parseWeekRanges("1-5单"))
        assertEquals(listOf(2, 4, 6), GraduateTimetableProtocol.parseWeekRanges("2-6双"))
        assertTrue(runCatching { GraduateTimetableProtocol.weeks("0".repeat(34)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun timetableMapsRealPeriodsNotGridAndMergesWeeks() {
        val weekOne = CharArray(34) { '0' }.also { it[1] = '1' }.concatToString()
        val weekThree = CharArray(34) { '0' }.also { it[3] = '1' }.concatToString()
        val result = GraduateTimetableProtocol.timetable(
            rowsBody = """[
                {"RWH":"TASK-ONE","KEY":"xq2_jc3","KSJC":5,"JSJC":6,"ZC":"$weekOne","SKSJ":"合成课程<br>[合成教师]<br>[1周][A101]<br>第5-6节"},
                {"RWH":"TASK-ONE","KEY":"xq2_jc4","KSJC":5,"JSJC":6,"ZC":"$weekThree","SKSJ":"合成课程<br>[合成教师]<br>[3周][A101]<br>第5-6节"},
                {"KEY":"bz","KSJC":1,"JSJC":1,"ZC":"$weekOne"}
            ]""",
            selectedBody = selectedBody(),
            calendarBody = """{"xlList":[{"XNXQ":"2026-20271","ZC":1,"RQ":"2026-08-31","MON":"2026-08-31"}]}""",
            year = "2026-2027",
            term = "1",
        )
        val course = result.courses.single()
        assertEquals("合成课程", course.name)
        assertEquals("SYN-101", course.code)
        assertEquals(2, course.dow)
        assertEquals(5, course.begin)
        assertEquals(2, course.last)
        assertEquals(listOf(1, 3), course.weeks)
        assertEquals("A101", course.classroom)
        assertEquals("合成教师", course.teacher)
        assertEquals("14:00", course.startTime)
        assertEquals("15:45", course.endTime)
        assertEquals("2026-08-31", result.startDate.toString())
    }

    @Test fun selectedCoursesRejectWrongTermAndKeepDynamicCategories() {
        val selected = GraduateTimetableProtocol.selectedCourses(selectedBody(), "2026-2027", "1")
        assertEquals(listOf("dynamic-mode"), selected.categories.map { it.id })
        assertEquals("TASK-ONE", selected.rows.single().taskNumber)
        assertTrue(runCatching {
            GraduateTimetableProtocol.selectedCourses(selectedBody(), "2026-2027", "2")
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun pkjgmxParsesWeeksWeekdayPeriodAndLocation() {
        val meetings = GraduateTimetableProtocol.parsePkjgmx("<p>2-4周,星期二第5-6节 合成教室</p>")
        val meeting = meetings.single()
        assertEquals(listOf(2, 3, 4), meeting.weeks)
        assertEquals(2, meeting.weekday)
        assertEquals(5, meeting.beginPeriod)
        assertEquals(6, meeting.endPeriod)
        assertEquals("合成教室", meeting.location)
    }

    @Test fun paginationRequiresCompletePageAndStableTotal() {
        val first = GraduateTimetableProtocol.schoolCourses(
            """{"rwList":{"list":[{"rwh":"A","kcmc":"课程甲","pylx":"2"}],"total":2,"pageNum":1,"pageSize":1}}""",
            page = 1,
            pageSize = 1,
        )
        assertTrue(first.hasMore)
        assertTrue(runCatching {
            GraduateTimetableProtocol.schoolCourses(
                """{"rwList":{"list":[],"total":2,"pageNum":2,"pageSize":1}}""",
                page = 2,
                pageSize = 1,
            )
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            GraduateTimetableProtocol.schoolCourses(
                """{"rwList":{"list":[{"rwh":"A","kcmc":"本科课程","pylx":"1"}],"total":1,"pageNum":1,"pageSize":1}}""",
                page = 1,
                pageSize = 1,
            )
        }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun selectedBody() = """{
        "yxkcList":[{"rwh":"TASK-ONE","kcdm":"SYN-101","kcmc":"合成课程","dgjsmc":"合成教师","pkjgmx":"<p>1-4周,星期二第5-6节 A101</p>","xkfsdm":"dynamic-mode"}],
        "xkgzszList":[{"xkfsdm":"dynamic-mode","xkfsmc":"合成类别"}],
        "xsxkPage":{"p_xn":"2026-2027","p_xq":"1","p_xnxq":"2026-20271"},
        "kbjclist":[
            {"XN":"2026-2027","XQ":"1","XJ":1,"KSSJ":"08:00","JSSJ":"08:50"},
            {"XN":"2026-2027","XQ":"1","XJ":2,"KSSJ":"08:55","JSSJ":"09:45"},
            {"XN":"2026-2027","XQ":"1","XJ":3,"KSSJ":"10:05","JSSJ":"10:55"},
            {"XN":"2026-2027","XQ":"1","XJ":4,"KSSJ":"11:00","JSSJ":"11:50"},
            {"XN":"2026-2027","XQ":"1","XJ":5,"KSSJ":"14:00","JSSJ":"14:50"},
            {"XN":"2026-2027","XQ":"1","XJ":6,"KSSJ":"14:55","JSSJ":"15:45"}
        ]
    }"""
}
