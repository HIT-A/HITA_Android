package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenzhenCreditProgressParserTest {
    @Test
    fun `identity current term and plan context are parsed`() {
        val identity = ShenzhenCreditProgressParser.parseIdentity(
            """{"XH":"2024000000","ID":"record-demo","PYLX":"本科","NJMC":"2024级"}"""
        )
        val term = ShenzhenCreditProgressParser.parseCurrentTerm(
            """{"XN":"2025-2026","XQ":"2"}"""
        )
        val plan = ShenzhenCreditProgressParser.parsePlanContext(
            """{"FAH":"plan-demo","BGID":"change-demo"}"""
        )

        requireNotNull(identity)
        requireNotNull(plan)
        assertEquals("2024000000", identity.studentNumber)
        assertEquals("record-demo", identity.studentRecordId)
        assertEquals("1", identity.studentType)
        assertEquals("2024", identity.grade)
        assertEquals("2025-20262", term)
        assertEquals("plan-demo", plan.planId)
        assertEquals(
            "plain-plan-demo",
            ShenzhenCreditProgressParser.parsePlanContext("plain-plan-demo")?.planId
        )
        assertEquals(
            "quoted-plan-demo",
            ShenzhenCreditProgressParser.parsePlanContext("\"quoted-plan-demo\"")?.planId
        )
        assertEquals(
            56,
            ShenzhenCreditProgressParser.parseCourseRecordTotal(
                """{"R":56,"SFDQXQ":"0","XN":"2025-2026","XQ":"2"}"""
            )
        )
    }

    @Test
    fun `official summary categories groups and earned courses are combined`() {
        val progress = ShenzhenCreditProgressParser.parseProgress(
            summaryBody = """{"code":200,"content":{
                "ywcxf":"89.5","wwcxf":"73.0","ywcms":"40","wwcms":"27",
                "yqmsxf":{"YQMS":"67","ZYRS":"609","XFJ":"90.367","PM":"56","YQXF":"162.5"}
            }}""",
            categoriesBody = """{"content":{"xflbyq":[{
                "kclbdm":"29","kclbmc":"跨专业发展课程",
                "yqwcxf":"10.0","ywcxf":"4.0","wwcxf":"6.0","ywcxs":"64"
            },{
                "kcxzdm":"03","kclbdm":"04","kcxzmc":"任选","kclbmc":"文理通识",
                "skyymc":"英文","yqwcxf":"8.0","ywcxf":"9.0","wwcxf":"0",
                "ywcxs":"160","moochdxf":"1","moocsjhdxf":"3"
            }]}}""",
            groupsBody = """{"content":[{
                "kzid":"ROOT","fkzid":"-1","title":"无","sftg":true,
                "children":[{
                    "kzid":"TRACK","fkzid":"ROOT","title":"计算机轨道",
                    "yqxdms":1,"wc_ms":2,"wc_xf":6.5,"sftg":true,"children":[]
                }]
            }]}""",
            groupCourseBodies = mapOf(
                "TRACK" to """{"content":{"list":[{
                    "kcdm":"COMP2012","kcmc":"计算机设计与实践","xf":"3","kcxzmc":"必修",
                    "tjkkxnxq":"第二学年夏季","sfyxkc":"0"
                }]}}"""
            ),
            courseRecordBodies = listOf(
                """{"content":{"pages":1,"list":[{
                    "xnxq":"2026春季","kcdm":"COMP2052","kcmc":"数据结构与算法",
                    "xf":"3.5","xscj":"93","jsxm":"测试教师","rwh":"task-demo"
                }]}}"""
            ),
            currentTerm = "2025-20262"
        )

        requireNotNull(progress)
        assertEquals(162.5, progress.requiredCredits, 0.001)
        assertEquals(89.5, progress.completedCredits, 0.001)
        assertEquals(73.0, progress.remainingCredits, 0.001)
        assertEquals(67, progress.requiredCourses)
        assertEquals(2, progress.categories.size)
        assertFalse(progress.categories.first().passed)
        assertTrue(progress.categories.last().passed)
        assertEquals(64, progress.categories.first().completedHours)
        assertEquals("文理通识", progress.categories.last().name)
        assertEquals("任选", progress.categories.last().courseNature)
        assertEquals("英文", progress.categories.last().teachingLanguage)
        assertTrue(progress.categories.last().includesMooc)
        assertEquals(1.0, progress.categories.last().creditedMoocCredits, 0.001)
        assertEquals(3.0, progress.categories.last().earnedMoocCredits, 0.001)
        val track = progress.groups.single { it.id == "TRACK" }
        assertEquals(1, track.depth)
        assertEquals("COMP2012", track.courses.single().courseCode)
        assertEquals("必修", track.courses.single().courseNature)
        assertFalse(track.courses.single().completed)
        assertEquals("COMP2052", progress.courseRecords.single().courseCode)
    }

    @Test
    fun `earned course page count defaults safely`() {
        assertEquals(
            3,
            ShenzhenCreditProgressParser.parseCourseRecordPageCount(
                """{"content":{"pages":3}}"""
            )
        )
        assertEquals(1, ShenzhenCreditProgressParser.parseCourseRecordPageCount("{}"))
    }

    @Test
    fun `category requirements fall back to summary response`() {
        val progress = ShenzhenCreditProgressParser.parseProgress(
            summaryBody = """{"code":200,"content":{
                "ywcxf":"89.5","wwcxf":"73.0",
                "yqmsxf":{"YQXF":"162.5"},
                "xflbyq":[{
                    "kclbdm":"29","kclbmc":"跨专业发展课程",
                    "yqwcxf":"10.0","ywcxf":"4.0","wwcxf":"6.0","ywcxs":"64"
                }]
            }}""",
            categoriesBody = """{"code":200,"content":{}}""",
            groupsBody = """{"content":[]}""",
            courseRecordBodies = emptyList(),
            currentTerm = "2025-20262"
        )

        requireNotNull(progress)
        assertEquals(1, progress.categories.size)
        assertEquals("跨专业发展课程", progress.categories.single().name)
        assertEquals(10.0, progress.categories.single().requiredCredits, 0.001)
        assertEquals(64, progress.categories.single().completedHours)
    }

    @Test
    fun `missing optional summary still keeps category and group data`() {
        val progress = ShenzhenCreditProgressParser.parseProgress(
            summaryBody = "",
            categoriesBody = """{"content":{"xflbyq":[{
                "kclbmc":"专业选修","yqwcxf":"12","ywcxf":"3"
            }]}}""",
            groupsBody = """{"content":[{
                "kzid":"GROUP","title":"专业方向","yqxdxf":"6","wc_xf":"3"
            }]}""",
            courseRecordBodies = emptyList(),
            currentTerm = "2025-20262",
            allowMissingSummary = true
        )

        requireNotNull(progress)
        assertEquals(0.0, progress.requiredCredits, 0.001)
        assertEquals("专业选修", progress.categories.single().name)
        assertEquals("GROUP", progress.groups.single().id)
    }
}
