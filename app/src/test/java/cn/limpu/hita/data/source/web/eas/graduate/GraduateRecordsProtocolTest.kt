package cn.limpu.hita.data.source.web.eas.graduate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraduateRecordsProtocolTest {
    @Test fun gradesUsePublishedTextAndNumberNotHiddenFinal() {
        val rows = listOf(
            GraduateJson.objectOf(com.google.gson.JsonParser().parse("""{
                "kcmc":"合成课程","kcdm":"SYN-1","xnxqmc":"2026-2027秋季",
                "xscj":"优秀","xszscj":"95","zzzscj":"12","xf":"3.5","xs":"56",
                "kcxz":"必修","kclb":"专业课","yxmc":"合成学院","khfs":"考试","bkcx":"1","cjid":"9"
            }""")),
        )
        val score = GraduateRecordsProtocol.grades(rows, "2026-2027-1").single()
        assertEquals("优秀", score.finalScoresText)
        assertEquals(95.0, score.numericScore)
        assertEquals(95, score.finalScores)
        assertEquals(3.5f, score.credits)
        assertEquals(56, score.hours)
        assertEquals("必修", score.courseProperty)
    }

    @Test fun examsKeepUnknownClockAndRejectUndergraduateRows() {
        val rows = listOf(
            GraduateJson.objectOf(com.google.gson.JsonParser().parse("""{
                "KCMC":"合成课程","KCDM":"SYN-1","PYLX":"2","KSRQ2":"2026-12-01",
                "KSJC":3,"JSJC":4,"JXLMC":"合成楼","CDMC":"101","KSSJDMC":"期末"
            }""")),
            GraduateJson.objectOf(com.google.gson.JsonParser().parse("""{
                "KCMC":"合成课程","KCDM":"SYN-1","PYLX":"2","KSRQ2":"2026-12-02",
                "KSJTSJ":"09:00-11:00","KSSJDMC":"补考"
            }""")),
        )
        val exams = GraduateRecordsProtocol.exams(rows, "2026-2027-1", "2026-2027秋季")
        assertEquals("第3-4节", exams[0].examTime)
        assertEquals("合成楼 101", exams[0].examLocation)
        assertEquals("09:00-11:00", exams[1].examTime)
        assertTrue(runCatching {
            GraduateRecordsProtocol.exams(
                listOf(GraduateJson.objectOf(com.google.gson.JsonParser().parse("""{"KCMC":"本科","KCDM":"1","PYLX":"1"}"""))),
                "2026-2027-1",
                "2026-2027秋季",
            )
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun paginationRejectsTruncatedSecondPage() {
        val collector = GraduateRecordsProtocol.PageCollector()
        assertFalse(collector.consume(
            """{"code":200,"content":{"list":[{"kcmc":"甲"}],"total":2,"pageNum":1,"pageSize":1}}""",
            requestedPage = 1,
            enveloped = true,
        ))
        assertTrue(runCatching {
            collector.consume(
                """{"code":200,"content":{"list":[],"total":2,"pageNum":2,"pageSize":1}}""",
                requestedPage = 2,
                enveloped = true,
            )
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            collector.consume(
                """{"code":200,"content":{"list":[{"kcmc":"乙"}],"total":3,"pageNum":2,"pageSize":1}}""",
                requestedPage = 2,
                enveloped = true,
            )
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun paginationCompletesWithStableTotal() {
        val collector = GraduateRecordsProtocol.PageCollector()
        assertFalse(collector.consume(
            """{"list":[{"DM":"1"}],"total":2,"pageNum":1,"pageSize":1}""",
            requestedPage = 1,
            enveloped = false,
        ))
        assertTrue(collector.consume(
            """{"list":[{"DM":"2"}],"total":2,"pageNum":2,"pageSize":1}""",
            requestedPage = 2,
            enveloped = false,
        ))
        assertEquals(listOf("1", "2"), collector.completedRows().map { GraduateJson.text(it, "DM") })
    }

    @Test fun classroomOccupancyKeepsExamAndBorrowCombination() {
        val cells = GraduateRecordsProtocol.occupancyCells(
            """[{"CDDM":"R1","XQJ":2,"XJ":3,"PKBJ":"考","JYBJ":"借","PKJYBJ":"1","PKSB":"0","JYSB":"0"}]"""
        )
        assertEquals(listOf(Triple(2, 3, "考、借")), cells["R1"])
    }

    @Test fun failedOccupancyMarkerFailsInsteadOfEmptyRoom() {
        val error = runCatching {
            GraduateRecordsProtocol.occupancyCells(
                """[{"CDDM":"R1","XQJ":1,"XJ":1,"PKBJ":"","JYBJ":"","PKJYBJ":"0","PKSB":"1","JYSB":"0"}]"""
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun transportDistinguishesLoginTemplateFromTransientFailure() {
        val loginHtml = "<html><body><form><input type='password'>统一身份认证</form></body></html>"
        assertTrue(GraduateJsoupTransport.isAuthenticationFailure(200, "https://webvpn.hitwh.edu.cn/x", loginHtml))
        assertFalse(GraduateJsoupTransport.isAuthenticationFailure(503, "https://webvpn.hitwh.edu.cn/x", loginHtml))
        assertFalse(GraduateJsoupTransport.isAuthenticationFailure(
            200,
            "https://webvpn.hitwh.edu.cn/x",
            "<html><body><script>var relogin='/login'</script><table><tr><td>课程</td></tr></table></body></html>",
        ))
        assertTrue(GraduateJsoupTransport.isAuthenticationFailure(302, "https://webvpn.hitwh.edu.cn/x", ""))
    }
}
