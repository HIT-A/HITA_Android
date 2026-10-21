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

    @Test
    fun `decodes named and numeric entities while preserving course breaks`() {
        val body = """[{"JCMC":"第1-2节","MON":"&nbsp;信号&amp;系统◇张&#160;老师[1-2周]A&#xA0;楼[必修]<br/>电路[3周]B楼[选修]"}]"""
        val courses = BenbuGraduateScheduleParser.parseTimetable(body)
        assertEquals(2, courses.size)
        assertEquals("信号&系统", courses[0].name)
        assertEquals("张 老师", courses[0].teacher)
        assertEquals("A 楼", courses[0].classroom)
        assertEquals("电路", courses[1].name)
        assertEquals(listOf(3), courses[1].weeks)
    }

    @Test
    fun `decodes only once and keeps encoded angle brackets`() {
        val body = """[{"JCMC":"1-2","MON":"信号&lt;基础&gt;&amp;nbsp;[1周]A楼[必修]"}]"""
        assertEquals("信号<基础>&nbsp;", BenbuGraduateScheduleParser.parseTimetable(body).single().name)
    }
}
