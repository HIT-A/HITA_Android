package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.TermItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenzhenWebAcademicParserTest {
    private val term = TermItem("2025-2026", "2025-2026", "2", "春季").apply {
        name = "2026春季"
    }

    @Test
    fun `week dates produce the first teaching day`() {
        val date = ShenzhenWebAcademicParser.parseStartDate(
            """{"code":0,"content":[{"xqj":"2","rq":"2026-03-10"},{"xqj":"1","rq":"2026-03-09"}]}"""
        )

        assertEquals("2026-03-09", date.toString())
    }

    @Test
    fun `week dates accept new portal date field aliases`() {
        val currentDate = ShenzhenWebAcademicParser.parseStartDate(
            """{"code":200,"content":[
                {"XQJ":2,"DQRQ":"2026-09-08T00:00:00.000+08:00"},
                {"XQJ":1,"DQRQ":"2026-09-07T00:00:00.000+08:00"}
            ]}"""
        )
        val alternateDate = ShenzhenWebAcademicParser.parseStartDate(
            """{"content":[{"xqj":1,"rq1":"2026-09-07"}]}"""
        )

        assertEquals("2026-09-07", currentDate.toString())
        assertEquals("2026-09-07", alternateDate.toString())
    }

    @Test
    fun `period response preserves official start and end times`() {
        val periods = ShenzhenWebAcademicParser.parseScheduleStructure(
            """{"code":0,"content":[
                {"ks":"2","kssj":"9:25","jssj":"10:15"},
                {"ks":"1","kssj":"8:30","jssj":"9:20"}
            ]}"""
        ).orEmpty()

        assertEquals(2, periods.size)
        assertEquals(8, periods.first().from.hour)
        assertEquals(30, periods.first().from.minute)
        assertEquals(10, periods.last().to.hour)
        assertEquals(15, periods.last().to.minute)
    }

    @Test
    fun `course selection response exposes official periods in Shenzhen local time`() {
        val periods = ShenzhenWebAcademicParser.parseScheduleStructure(
            """{"kbjclist":[
                {"XJ":2,"KSSJ":"2026-06-01T01:25:00.000+00:00","JSSJ":"2026-06-01T02:15:00.000+00:00"},
                {"XJ":1,"KSSJ":"2026-06-01T00:30:00.000+00:00","JSSJ":"2026-06-01T01:20:00.000+00:00"}
            ]}"""
        ).orEmpty()

        assertEquals(2, periods.size)
        assertEquals(8, periods.first().from.hour)
        assertEquals(30, periods.first().from.minute)
        assertEquals(10, periods.last().to.hour)
        assertEquals(15, periods.last().to.minute)
    }

    @Test
    fun `selected metadata enriches web timetable`() {
        val subjects = ShenzhenWebAcademicParser.parseSelectedSubjects(
            """{"yxkcList":[{
                "rwh":"2025-2026-2-COMP2054-003","kcdm":"COMP2054","kcmc":"机器学习",
                "dgjsmc":"史瑶","xf":"3.0","kcxzmc":"必修","kkyxmc":"计算机学院"
            }]}"""
        ).orEmpty()
        val courses = ShenzhenWebAcademicParser.parseTimetable(
            """[{
                "RWH":"2025-2026-2-COMP2054-003","KSJC":5,"JSJC":6,
                "KEY":"xq1_jc3","ZC":"0110100000000000000000000000000000",
                "SKSJ":"机器学习\n[史瑶]\n[1,2,4周][A309]\n第5-6节"
            }]""",
            subjects
        ).orEmpty()

        assertEquals(1, subjects.size)
        assertEquals(1, courses.size)
        assertEquals("COMP2054", courses.single().code)
        assertEquals("机器学习", courses.single().name)
        assertEquals("史瑶", courses.single().teacher)
        assertEquals("A309", courses.single().classroom)
        assertEquals(listOf(1, 2, 4), courses.single().weeks)
        assertEquals(1, courses.single().dow)
        assertEquals(5, courses.single().begin)
        assertEquals(2, courses.single().last)
    }

    @Test
    fun `timetable parser accepts nested data wrapper used by minor schedules`() {
        val courses = ShenzhenWebAcademicParser.parseTimetable(
            """{"data":{"rows":[{
                "RWH":"2025-2026-2-MINOR100-001","KSJC":1,"JSJC":2,
                "KEY":"xq2_jc1","ZC":"111111111111111111111111111111111",
                "SKSJ":"辅修课程\n[张老师]\n[A101]\n第1-2节"
            }]}}""",
            emptyList()
        ).orEmpty()

        assertEquals(1, courses.size)
        assertEquals("辅修课程", courses.single().name)
        assertEquals(2, courses.single().dow)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32), courses.single().weeks)
    }

    @Test
    fun `web exam timestamp is converted to Shenzhen calendar date`() {
        val exams = ShenzhenWebAcademicParser.parseExams(
            """{"total":1,"list":[{
                "KCMC":"形式语言与自动机","KSRQ":"2026-05-28T16:00:00.000+00:00",
                "KSJTSJ":"19:00-21:00","KSSJDMC":"期末","JXLMC":"教学楼V","CDMC":"T5507"
            }]}""",
            term
        ).orEmpty()

        assertEquals(1, exams.size)
        assertEquals("2026-05-29", exams.single().examDate)
        assertEquals("教学楼V T5507", exams.single().examLocation)
        assertEquals(term.id, exams.single().termId)
    }

    @Test
    fun `building array accepts official uppercase fields`() {
        val buildings = ShenzhenWebAcademicParser.parseBuildings(
            """[{"MC":"教学楼V","DM":"17"},{"MC":"主楼","DM":"19"}]"""
        ).orEmpty()

        assertEquals(2, buildings.size)
        assertEquals("17", buildings.first().id)
        assertTrue(buildings.first().name.orEmpty().contains("教学楼"))
    }

}
