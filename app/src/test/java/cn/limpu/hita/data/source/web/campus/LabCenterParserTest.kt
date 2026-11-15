package cn.limpu.hita.data.source.web.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实验中心课表页与课表数组的解析。
 *
 * 样本全部脱敏：课程名、教师、学号都是占位符，且解析结果里本来就不保存学号。
 */
class LabCenterParserTest {

    // ---- 课表页 HTML ----

    @Test
    fun `selected term comes from the option marked selected`() {
        val term = LabCenterHtmlParser.selectedTerm(
            """
            <select id="termId" name="termId" class="easyui-combobox">
              <option value="2001">2024-2025学年第一学期</option>
              <option value="2013" selected="selected">2025-2026学年第一学期</option>
            </select>
            """.trimIndent()
        )
        assertEquals("2013", term?.id)
        assertEquals("2025-2026学年第一学期", term?.name)
    }

    @Test
    fun `selected term falls back to the last option when nothing is marked`() {
        // 文档实测：当前学期排在学期下拉框的最后一项。
        val term = LabCenterHtmlParser.selectedTerm(
            """
            <select id='termId'>
              <option value='2001'>旧学期</option>
              <option value='2013'>2025-2026学年第一学期</option>
            </select>
            """.trimIndent()
        )
        assertEquals("2013", term?.id)
    }

    @Test
    fun `selected week reads the marked option only`() {
        val html = """
            <select id="week">
              <option value="1">第1周</option>
              <option value="5" selected>第5周</option>
              <option value="6">第6周</option>
            </select>
        """.trimIndent()
        assertEquals(5, LabCenterHtmlParser.selectedWeek(html))
    }

    @Test
    fun `selected week is null when nothing is marked`() {
        val html = """
            <select id="week">
              <option value="1">第1周</option>
            </select>
        """.trimIndent()
        assertNull(LabCenterHtmlParser.selectedWeek(html))
    }

    @Test
    fun `missing select blocks yield null instead of throwing`() {
        assertNull(LabCenterHtmlParser.selectedTerm("<html><body>nothing</body></html>"))
        assertNull(LabCenterHtmlParser.selectedWeek(""))
    }

    @Test
    fun `login page is detected and not treated as a schedule page`() {
        val loginHtml = """
            <html><head><title>登录</title></head>
            <body>
              <form action="/hitszlims/webapp/login" method="post">
                <input name="j_username" />
              </form>
            </body></html>
        """.trimIndent()
        assertTrue(LabCenterHtmlParser.looksLikeLoginPage(loginHtml))
        assertFalse(LabCenterHtmlParser.looksLikeSchedulePage(loginHtml))
    }

    @Test
    fun `schedule page markers are detected`() {
        assertTrue(
            LabCenterHtmlParser.looksLikeSchedulePage(
                """<select id="termId"></select><script>load('myScheduleInfoAjax')</script>"""
            )
        )
        assertFalse(LabCenterHtmlParser.looksLikeSchedulePage("<html>nothing</html>"))
    }

    @Test
    fun `option labels strip tags and entities`() {
        val html = """<select id="termId"><option value="1"><b>2025</b>&nbsp;秋季</option></select>"""
        assertEquals(listOf("2025 秋季"), LabCenterHtmlParser.optionLabels(html, "termId"))
    }

    // ---- 课表数组 ----

    @Test
    fun `empty schedule yields no sessions`() {
        assertEquals(emptyList<Any>(), LabCenterScheduleParser.parseSessions(emptyList()))
        assertEquals(emptyList<Any>(), LabCenterScheduleParser.parseRows("[]").let(LabCenterScheduleParser::parseSessions))
    }

    @Test
    fun `schedule rows expand weeks and sort by week weekday section`() {
        val rows = LabCenterScheduleParser.parseRows(
            """
            [
              ["SC-01-01","物理实验","实验中心",null,3,3,4,"5,7",7,"张老师",null,"A101","1班",18,"0000001"],
              ["SC-02-01","化学实验","实验中心",null,1,1,2,"6",6,"李老师",null,"B201","2班",20,"0000002"]
            ]
            """.trimIndent()
        )
        val sessions = LabCenterScheduleParser.parseSessions(rows)
        assertEquals(3, sessions.size)
        assertEquals(listOf(5, 6, 7), sessions.map { it.week })
        assertEquals("化学实验", sessions[1].courseName)
        assertEquals("B201", sessions[1].room)
        assertEquals(listOf(5, 7), sessions.filter { it.courseName == "物理实验" }.map { it.week })
        // id 唯一，可直接给 LazyColumn 当 key。
        assertEquals(sessions.size, sessions.map { it.id }.toSet().size)
    }

    @Test
    fun `paikao rows and theory rows are excluded`() {
        val rows = LabCenterScheduleParser.parseRows(
            """
            [
              ["SC-01-01-paikao","排考","实验中心",null,2,1,2,"8",8,"张老师",null,"A101","1班",18,"0000001"],
              ["SC-01-02","[理]实验理论","实验中心",null,2,3,4,"8",8,"张老师",null,"A101","1班",18,"0000001"],
              ["SC-01-03","真实实验","实验中心",null,2,5,6,"8",8,"张老师",null,"A101","1班",18,"0000001"]
            ]
            """.trimIndent()
        )
        val sessions = LabCenterScheduleParser.parseSessions(rows)
        assertEquals(1, sessions.size)
        assertEquals("真实实验", sessions.single().courseName)
    }

    @Test
    fun `duplicate rows collapse to one session`() {
        val row = """["SC-01-01","物理实验","实验中心",null,3,3,4,"5",5,"张老师",null,"A101","1班",18,"0000001"]"""
        val rows = LabCenterScheduleParser.parseRows("[$row,$row]")
        assertEquals(1, LabCenterScheduleParser.parseSessions(rows).size)
    }

    @Test
    fun `rows with short length or invalid weekday are skipped`() {
        val rows = LabCenterScheduleParser.parseRows(
            """
            [
              ["SC-01-01","缺列","实验中心",null,3,3,4],
              ["SC-01-02","星期越界","实验中心",null,9,3,4,"5",5,"张老师",null,"A101","1班",18],
              ["SC-01-03","正常","实验中心",null,4,1,2,"5",5,"张老师",null,"A101","1班",18]
            ]
            """.trimIndent()
        )
        val sessions = LabCenterScheduleParser.parseSessions(rows)
        assertEquals(1, sessions.size)
        assertEquals("正常", sessions.single().courseName)
    }

    @Test
    fun `end section is never smaller than start section`() {
        val rows = LabCenterScheduleParser.parseRows(
            """[["SC-01-01","实验","实验中心",null,3,5,2,"5",5,"张老师",null,"A101","1班",18]]"""
        )
        val session = LabCenterScheduleParser.parseSessions(rows).single()
        assertEquals(5, session.startSection)
        assertEquals(5, session.endSection)
    }

    @Test
    fun `numeric values returned as numbers or strings are both accepted`() {
        val rows = LabCenterScheduleParser.parseRows(
            """[["SC-01-01","实验","实验中心",null,"3","5",6,"5",5,"张老师",null,"A101","1班",18]]"""
        )
        val session = LabCenterScheduleParser.parseSessions(rows).single()
        assertEquals(3, session.weekday)
        assertEquals(5, session.startSection)
        assertEquals(6, session.endSection)
    }

    @Test
    fun `rows that are not arrays are skipped`() {
        val rows = LabCenterScheduleParser.parseRows(
            """[[ "x" ], "not-a-row", ["SC-01-01","实验","实验中心",null,3,5,6,"5",5,"张老师",null,"A101","1班",18]]"""
        )
        assertEquals(2, rows.size)
        assertEquals(1, LabCenterScheduleParser.parseSessions(rows).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `object envelope is rejected`() {
        LabCenterScheduleParser.parseRows("""{"code":0,"msg":"ok","data":[]}""")
    }

    // ---- 周次 ----

    @Test
    fun `weeks support single values lists ranges and mixes`() {
        assertEquals(listOf(11), LabCenterScheduleParser.parseWeeks("11"))
        assertEquals(listOf(3, 6, 8, 10, 14), LabCenterScheduleParser.parseWeeks("3,6,8,10,14"))
        assertEquals((10..17).toList(), LabCenterScheduleParser.parseWeeks("10-17"))
        assertEquals(listOf(1, 2, 3, 4, 6), LabCenterScheduleParser.parseWeeks("1-4,6"))
    }

    @Test
    fun `weeks accept full width separators and week suffix`() {
        assertEquals(listOf(3, 4, 5, 8), LabCenterScheduleParser.parseWeeks("3－5，8周"))
    }

    @Test
    fun `weeks outside one to thirty three are dropped`() {
        assertEquals(listOf(1, 33), LabCenterScheduleParser.parseWeeks("0,1,33,34"))
    }

    @Test
    fun `reversed ranges and garbage produce no weeks`() {
        assertEquals(emptyList<Int>(), LabCenterScheduleParser.parseWeeks("17-10"))
        assertEquals(emptyList<Int>(), LabCenterScheduleParser.parseWeeks("abc"))
        assertEquals(emptyList<Int>(), LabCenterScheduleParser.parseWeeks(""))
    }
}
