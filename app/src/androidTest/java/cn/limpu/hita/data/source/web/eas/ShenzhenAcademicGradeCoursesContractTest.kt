package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenGradeStatus
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 成绩页面的公布、已选与个人提前成绩需要通过同一公共入口合并。 */
@RunWith(AndroidJUnit4::class)
class ShenzhenAcademicGradeCoursesContractTest : ShenzhenAcademicContractHarness() {
    @Test
    fun gradeCoursesLookUpIdentityAndMergeEarlyScoresWithoutReplacingPublishedScores() {
        webResponse = { request ->
            when (request.path) {
                PUBLISHED -> json(PUBLISHED_BODY)
                SELECTED -> json(SELECTED_BODY)
                IDENTITY -> json("""{"XH":"2024000001","PYLX":"2"}""")
                STUDENT_RECORD -> json("""{"content":[{"id":"local-record"}]}""")
                EARLY -> json("""{"pageInfo":{"list":[
                    {"rwh":"2026-2027-1-CS101-001","kcmc":"程序设计","zzzscj":"99"},
                    {"rwh":"2026-2027-1-CS102-001","kcmc":"接口别名课程","zzzscj":"88.5"}
                ]}}""")
                else -> error("Unexpected grade-course request ${request.path}")
            }
        }
        val token = webToken()

        val courses = success { source.getShenzhenGradeCourses(token, term) }
        awaitWorkerCompletion()

        assertEquals(2, courses.size)
        val published = courses.single { it.taskId == "RW-P" }
        assertEquals(ShenzhenGradeStatus.PUBLISHED, published.status)
        assertEquals("GRADE-P", published.recordId)
        assertEquals(91.0, requireNotNull(published.myScore), 0.0)
        val early = courses.single { it.taskId == "RW-E" }
        assertEquals(ShenzhenGradeStatus.EARLY, early.status)
        assertEquals(88.5, requireNotNull(early.myScore), 0.0)
        assertEquals("2024000001", token.stuId)
        assertEquals("local-record", token.id)
        assertEquals(EASToken.TYPE.GRAD, token.stutype)
        assertSame(token, refreshedToken.get())
        assertEquals(listOf(PUBLISHED, SELECTED, IDENTITY, STUDENT_RECORD, EARLY), webRequests.map { it.path })
        assertTrue(apiRequests.isEmpty())

        val publishedRequest = webRequests.first()
        assertEquals(web.url("/cjgl/grcjcx").toString(), publishedRequest.getHeader("Referer"))
        assertEquals(term.yearCode, jsonBody(publishedRequest).getString("xn"))
        assertEquals(term.termCode, jsonBody(publishedRequest).getString("xq"))
        assertEquals("1", jsonBody(publishedRequest).getString("pylx"))
        assertEquals(1000, jsonBody(publishedRequest).getInt("pageSize"))
        val selectedRequest = webRequests[1]
        assertEquals(web.url("/Xsxk/query/1").toString(), selectedRequest.getHeader("Referer"))
        assertEquals(term.getCode(), form(selectedRequest)["p_xnxq"])
        assertEquals("yixuan", form(selectedRequest)["p_xkfsdm"])
        assertEquals("1", form(selectedRequest)["p_pylx"])
        assertEquals("2024000001", jsonBody(webRequests[3]).getString("xjidorxh"))
        val earlyRequest = webRequests.last()
        assertEquals(web.url("/cjgl/cjzhtjcx/cjcx").toString(), earlyRequest.getHeader("Referer"))
        assertEquals("2024000001", jsonBody(earlyRequest).getString("xh"))
        assertEquals("local-record", jsonBody(earlyRequest).getString("xjid"))
        assertEquals("2", jsonBody(earlyRequest).getString("pylx"))
        assertEquals(200, jsonBody(earlyRequest).getInt("pageSize"))
        assertTrue(webRequests.all { it.method == "POST" && it.getHeader("RoleCode") == "01" })
    }

    @Test
    fun unavailableOptionalEarlyScoresKeepPublishedAndSelectedCourses() {
        for (status in listOf(500, 401)) {
            webRequests.clear()
            webResponse = { request ->
                when (request.path) {
                    PUBLISHED -> json(PUBLISHED_BODY)
                    SELECTED -> json(SELECTED_BODY)
                    IDENTITY -> json("""{"XH":"2024000001","ID":"local-record","PYLX":"1"}""")
                    EARLY -> json("{}", status)
                    else -> error("Unexpected optional-score request ${request.path}")
                }
            }

            val courses = success { source.getShenzhenGradeCourses(webToken(), term) }
            awaitWorkerCompletion()

            assertEquals(2, courses.size)
            assertEquals(91.0, requireNotNull(courses.single { it.taskId == "RW-P" }.myScore), 0.0)
            val selected = courses.single { it.taskId == "RW-E" }
            assertEquals(ShenzhenGradeStatus.SELECTED, selected.status)
            assertNull(selected.myScore)
            assertEquals(listOf(PUBLISHED, SELECTED, IDENTITY, EARLY), webRequests.map { it.path })
        }
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun requiredGradeCourseResponsesRejectExpiredSessions() {
        for (failedPath in listOf(PUBLISHED, SELECTED, IDENTITY, STUDENT_RECORD)) {
            webRequests.clear()
            webResponse = { request ->
                if (request.path == failedPath) json("{}", 401) else when (request.path) {
                    PUBLISHED -> json(PUBLISHED_BODY)
                    SELECTED -> json(SELECTED_BODY)
                    IDENTITY -> json("""{"XH":"2024000001","PYLX":"1"}""")
                    else -> error("Unexpected request after required-score failure ${request.path}")
                }
            }

            val state = awaitState { source.getShenzhenGradeCourses(webToken(), term) }
            awaitWorkerCompletion()

            assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
            assertNull(state.data)
            // The old method requests selected courses before checking both first responses.
            val expected = when (failedPath) {
                PUBLISHED, SELECTED -> listOf(PUBLISHED, SELECTED)
                IDENTITY -> listOf(PUBLISHED, SELECTED, IDENTITY)
                else -> listOf(PUBLISHED, SELECTED, IDENTITY, STUDENT_RECORD)
            }
            assertEquals(expected, webRequests.map { it.path })
        }
        assertTrue(apiRequests.isEmpty())
    }

    private companion object {
        const val PUBLISHED = "/cjgl/grcjcx/grcjcx"
        const val SELECTED = "/Xsxk/queryYxkc?sf_request_type=ajax"
        const val IDENTITY = "/UserManager/queryxsxx"
        const val STUDENT_RECORD = "/cjgl/cjzhtjcx/cjcx/getXs"
        const val EARLY = "/cjgl/cjzhtjcx/cjcx/queryZxcjPage"
        const val PUBLISHED_BODY = """{"content":{"list":[{
            "id":"GRADE-P","rwid":"RW-P","rwh":"2026-2027-1-CS101-001",
            "kcdm":"CS101","kcmc":"程序设计","xscj":"91"
        }]}}"""
        const val SELECTED_BODY = """{"yxkcList":[
            {"rwid":"RW-P","rwh":"2026-2027-1-CS101-001","kcdm":"CS101","kcmc":"程序设计"},
            {"rwid":"RW-E","rwh":"2026-2027-1-CS102-001","kcdm":"CS102","kcmc":"数据结构"}
        ]}"""
    }
}
