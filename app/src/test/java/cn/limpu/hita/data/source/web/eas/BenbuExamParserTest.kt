package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.TermItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenbuExamParserTest {
    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
    private val headers = listOf("序号", "课程名称", "课程代码", "考试地点", "座位号", "考试具体时间", "考试时间段")
    private val row = listOf("1", "数学分析", "MA101", "正心21", "15", "2026年10月29日(第9周 星期四)10:00-11:30", "期中")

    private fun table(labels: List<String> = headers, rows: List<List<String>> = listOf(row)): String =
        "<table class='bot_line'><tr>" + labels.joinToString("") { "<th>$it</th>" } +
            "</tr>" + rows.joinToString("") { values -> "<tr>" + values.joinToString("") { "<td>$it</td>" } + "</tr>" } + "</table>"

    @Test fun `documented seven column midterm is preserved`() {
        val exam = BenbuExamParser.parse(table(), "期中", term).single()
        assertEquals("数学分析", exam.courseName)
        assertEquals("2026-10-29", exam.examDate)
        assertEquals("10:00-11:30", exam.examTime)
        assertEquals("正心21", exam.examLocation)
        assertEquals("期中", exam.examType)
        assertEquals(term.id, exam.termId)
        assertEquals(term.name, exam.termName)
    }

    @Test fun `six column midterm without type is not dropped`() {
        val exam = BenbuExamParser.parse(table(headers.take(6), listOf(row.take(6))), "期中", term).single()
        assertEquals("数学分析", exam.courseName)
        assertEquals("期中", exam.examType)
        assertEquals("10:00-11:30", exam.examTime)
    }

    @Test fun `final and resit use requested category when column is absent`() {
        for (type in listOf("期末", "补考")) {
            assertEquals(type, BenbuExamParser.parse(table(headers.take(6), listOf(row.take(6))), type, term).single().examType)
        }
    }

    @Test fun `visible row category takes priority over query fallback`() {
        assertEquals("期中", BenbuExamParser.parse(table(), "期末", term).single().examType)
    }

    @Test fun `columns can move and date can be separate from time`() {
        val html = table(listOf("课程名称", "考试日期", "考试时间", "考试类型", "考试教室"),
            listOf(listOf("大学物理", "2026-11-03", "9:00 – 11:00", "期末", "格物301")))
        val exam = BenbuExamParser.parse(html, "期中", term).single()
        assertEquals("大学物理", exam.courseName)
        assertEquals("2026-11-03", exam.examDate)
        assertEquals("09:00-11:00", exam.examTime)
        assertEquals("格物301", exam.examLocation)
        assertEquals("期末", exam.examType)
    }

    @Test fun `unrelated table before exam table is ignored`() {
        val html = "<table class='bot_line'><tr><td>说明</td></tr><tr><td>请选择学期</td></tr></table>" + table()
        assertEquals("数学分析", BenbuExamParser.parse(html, "期中", term).single().courseName)
    }

    @Test fun `layout table containing exam table is not selected as data table`() {
        val html = "<table><tr><td>" + table() + "</td></tr></table>"
        assertEquals(1, BenbuExamParser.parse(html, "期中", term).size)
    }

    @Test fun `td headers and introductory row do not hide the first exam`() {
        val html = table().replace("<th>", "<td>").replace("</th>", "</td>")
            .replace("<table class='bot_line'>", "<table class='bot_line'><tr><td colspan='7'>考试安排</td></tr>")
        assertEquals(1, BenbuExamParser.parse(html, "期中", term).size)
    }

    @Test fun `headerless legacy table keeps first data row`() {
        val html = "<table class='bot_line'><tr>" + row.take(6).joinToString("") { "<td>$it</td>" } + "</tr></table>"
        assertEquals("数学分析", BenbuExamParser.parse(html, "期中", term).single().courseName)
    }

    @Test fun `single digit date and full width time punctuation are normalized`() {
        val values = row.toMutableList().apply { this[5] = "2026年 5月 9日（第9周）9：00 ～ 11：00" }
        val exam = BenbuExamParser.parse(table(rows = listOf(values)), "期中", term).single()
        assertEquals("2026-05-09", exam.examDate)
        assertEquals("09:00-11:00", exam.examTime)
    }

    @Test fun `slash date and time separated by whitespace are normalized`() {
        val values = row.toMutableList().apply { this[5] = "2026/10/29 9:00 至 11:30" }
        val exam = BenbuExamParser.parse(table(rows = listOf(values)), "期中", term).single()
        assertEquals("2026-10-29", exam.examDate)
        assertEquals("09:00-11:30", exam.examTime)
    }

    @Test fun `unpublished time keeps course without inventing an exam date`() {
        val values = row.toMutableList().apply { this[5] = "待定" }
        val exam = BenbuExamParser.parse(table(rows = listOf(values)), "期中", term).single()
        assertEquals("数学分析", exam.courseName)
        assertEquals("", exam.examDate)
        assertEquals("", exam.examTime)
    }

    @Test fun `invalid dates and clock values are not turned into valid dates`() {
        val values = row.toMutableList().apply { this[5] = "2026年02月30日 25:00-26:00" }
        val exam = BenbuExamParser.parse(table(rows = listOf(values)), "期中", term).single()
        assertEquals("", exam.examDate)
        assertEquals("", exam.examTime)
    }

    @Test fun `known empty table and empty message are valid empty results`() {
        assertTrue(BenbuExamParser.parse(table(rows = emptyList()), "期中", term).isEmpty())
        assertTrue(BenbuExamParser.parse(table(rows = listOf(listOf("暂无考试安排"))), "期中", term).isEmpty())
        assertTrue(BenbuExamParser.parse("<p>未查询到相关数据</p>", "期中", term).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unrecognized response is not a successful empty exam list`() {
        BenbuExamParser.parse("<html><h1>系统维护中</h1></html>", "期中", term)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `login response is not a successful empty exam list`() {
        BenbuExamParser.parse("<form action='/login'><input name='password'/></form>", "期中", term)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated data row is reported rather than silently dropped`() {
        BenbuExamParser.parse(table(rows = listOf(listOf("1", "数学分析"))), "期中", term)
    }

    @Test fun `multiple exams for one course remain separate`() {
        val second = row.toMutableList().apply { this[0] = "2"; this[5] = "2026年12月29日 10:00-11:30"; this[6] = "期末" }
        val exams = BenbuExamParser.parse(table(rows = listOf(row, second)), "期中", term)
        assertEquals(listOf("2026-10-29", "2026-12-29"), exams.map { it.examDate })
        assertEquals(listOf("期中", "期末"), exams.map { it.examType })
    }
}
