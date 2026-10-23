package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertEquals
import org.junit.Test

class BenbuExperimentNameTest {
    private fun detail(teacher: String, week: Int, room: String) =
        "$teacher [${week}周第5，6节] 上课形式[线下]$room，<span class=\"red\">13:00-15:00</span>"

    private fun parse(cell: String) = BenbuScheduleParser.parseExperimentHtml(
        """<table class="dataTable">
            <tr><th colspan="9">实验课表</th></tr>
            <tr><th colspan="9">星期</th></tr>
            <tr><td>下午</td><td>第5，6节</td><td>$cell</td>
                <td></td><td></td><td></td><td></td><td></td><td></td></tr>
        </table>"""
    )

    @Test fun shortCourseNamesKeepTheirOwnTeachersAndWeeks() {
        val courses = parse("数值分析<br>${detail("教师甲", 8, "A101")}<br>操作系统<br>${detail("教师乙", 9, "B202")}")
        assertEquals(listOf("数值分析", "操作系统"), courses.map { it.name })
        assertEquals(listOf("教师甲", "教师乙"), courses.map { it.teacher })
        assertEquals(listOf(listOf(8), listOf(9)), courses.map { it.weeks })
        assertEquals(listOf("A101", "B202"), courses.map { it.classroom })
        assertEquals(listOf("13:00", "13:00"), courses.map { it.startTime })
    }

    @Test fun courseBoundaryDoesNotDependOnNameLength() {
        val names = listOf("数值分析", "操作系统", "电路", "数据库", "计算机网络", "计算机组成原理")
        val courses = parse(names.mapIndexed { index, name -> "$name<br>${detail("教师甲", index + 1, "A101")}" }.joinToString("<br>"))
        assertEquals(names, courses.map { it.name })
        assertEquals((1..names.size).map { listOf(it) }, courses.map { it.weeks })
    }

    @Test fun multipleSessionsUnderOneCourseAreRetained() {
        val courses = parse("操作系统<br>${detail("教师甲", 8, "A101")}<br>${detail("教师乙", 9, "B202")}")
        assertEquals(listOf("操作系统", "操作系统"), courses.map { it.name })
        assertEquals(listOf("教师甲", "教师乙"), courses.map { it.teacher })
    }

    @Test fun combinedLinesDoNotInheritPreviousCourseName() {
        val courses = parse("数值分析 教师甲 [8周第5，6节] 上课形式[线下]A101，13:00-15:00<br>操作系统 教师乙 [9周第5，6节] 上课形式[线下]B202，15:00-17:00")
        assertEquals(listOf("数值分析", "操作系统"), courses.map { it.name })
        assertEquals(listOf("教师甲", "教师乙"), courses.map { it.teacher })
        assertEquals(listOf("13:00", "15:00"), courses.map { it.startTime })
    }

    @Test fun namesContainingScheduleWordsAndHtmlEntitiesAreNotDetails() {
        val courses = parse("数值分析<br>${detail("教师甲", 8, "A101")}<br><a>周期信号实验</a><br>${detail("教师乙", 9, "B202")}<BR/>A&amp;B<br>教师丙&nbsp;[10周第5，6节] 上课形式[线下]C303，13:00-15:00")
        assertEquals(listOf("数值分析", "周期信号实验", "A&B"), courses.map { it.name })
        assertEquals(listOf("教师甲", "教师乙", "教师丙"), courses.map { it.teacher })
    }
}
