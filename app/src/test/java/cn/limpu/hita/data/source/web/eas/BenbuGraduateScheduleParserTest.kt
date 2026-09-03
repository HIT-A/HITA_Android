package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenbuGraduateScheduleParserTest {
    @Test
    fun `parses graduate semester response`() {
        val terms = BenbuGraduateScheduleParser.parseTerms("[{\"xnxq\":\"2025-20261\",\"current\":true}]")
        assertEquals("2025-2026-1", terms.single().id)
        assertTrue(terms.single().isCurrent)
    }

    @Test
    fun `parses graduate timetable rows`() {
        val courses = BenbuGraduateScheduleParser.parseTimetable("[{\"JCMC\":\"第1-2节\",\"MON\":\"高等数学◇张老师[1-16周]教学楼A[必修]\",\"TUES\":\"\"}]")
        assertEquals(1, courses.size)
        assertEquals("高等数学", courses.single().name)
        assertEquals("张老师", courses.single().teacher)
        assertEquals(1, courses.single().dow)
        assertEquals(1, courses.single().begin)
        assertEquals(2, courses.single().last)
        assertEquals((1..16).toList(), courses.single().weeks)
    }
}
