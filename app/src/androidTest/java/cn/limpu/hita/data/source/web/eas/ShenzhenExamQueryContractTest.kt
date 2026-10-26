package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpu.component.data.DataState
import java.util.Calendar
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShenzhenExamQueryContractTest : ExamQueryContractHarness() {
    @Test
    fun apiEagerRequestKeepsBearerPriorityFirstPageFieldsAndIndependentCookieBehavior() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = apiToken().apply { webCookies["SESSION"] = "local-web-session" }
        apiResponse = {
            hold(entered, release)
            json("""{"code":200,"content":{"total":200,"pageSize":20,"list":[
                {"KCMC":"课程乙","KSRQ":"原日期","KSJTSJ":"原时间","KSSJDMC":"原类型","JXLMC":"楼乙","JXCDMC":"101","XNXQMC":"远端学期","XIAOQUBMC":"远端校区"},
                7,{},
                {"KCMC":"课程乙","KSRQ":"重复日期","KSJTSJ":"重复时间"}
            ]}}""").addHeader("Set-Cookie", "api-cookie=replaced; Path=/")
        }

        val state = eagerExamResult(entered, release) { source.getExamItems(token, term) }

        assertExams(state,
            expectedExam("课程乙", "原日期", "原时间", "原类型", "楼乙 101", "远端学期", term.id, "远端校区"),
            expectedExam("", "", "", "", " ", "", term.id, ""),
            expectedExam("课程乙", "重复日期", "重复时间", "", " ", "", term.id, ""),
        )
        val request = apiRequests.single()
        assertEquals("POST", request.method)
        assertEquals(API_EXAM_PATH, request.path)
        assertEquals("bearer local-api-token", request.getHeader("authorization"))
        assertEquals("06", request.getHeader("rolecode"))
        assertEquals("cn", request.getHeader("_lang"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = jsonBody(request)
        assertEquals(setOf("type", "xn", "xq", "kssjddm", "kcdmorkcmc", "pageNum", "pageSize"), body.keys().asSequence().toSet())
        assertEquals("json", body.getString("type"))
        assertEquals(term.yearCode, body.getString("xn"))
        assertEquals(term.termCode, body.getString("xq"))
        assertEquals("", body.getString("kssjddm"))
        assertEquals("", body.getString("kcdmorkcmc"))
        assertEquals(1, body.getInt("pageNum"))
        assertEquals(20, body.getInt("pageSize"))
        assertEquals("local-api-cookie", token.cookies["api-cookie"])
        assertFalse(request.getHeader("Cookie").orEmpty().contains("local-web-session"))
        assertEquals(0, refreshCount.get())
        assertEquals(0, credentialLookups.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiNullTermUsesCalendarYearAndSemesterTwoWithoutTermLookup() {
        val yearBefore = Calendar.getInstance().get(Calendar.YEAR)
        apiResponse = { json("""{"code":200,"content":{"list":[{"KCMC":"甲"}]}}""") }

        val state = awaitState { source.getExamItems(apiToken(), null) }

        val yearAfter = Calendar.getInstance().get(Calendar.YEAR)
        val request = apiRequests.single()
        val body = jsonBody(request)
        assertTrue(body.getString("xn") in setOf("$yearBefore-${yearBefore + 1}", "$yearAfter-${yearAfter + 1}"))
        assertEquals("2", body.getString("xq"))
        assertExams(state, expectedExam("甲", "", "", "", " ", "", body.getString("xn") + "-2", ""))
        assertEquals(API_EXAM_PATH, request.path)
    }

    @Test
    fun apiMissingContentAndEmptyListRemainSuccessfulEmpty() {
        listOf("""{"code":200}""", """{"code":200,"content":{"list":[]}}""").forEach { body ->
            apiResponse = { json(body) }
            assertExams(awaitState { source.getExamItems(apiToken(), term) })
        }
        assertEquals(2, apiRequests.size)
    }

    @Test
    fun apiExpiredResponsesReturnNotLoggedInWithoutReloginOrWebFallback() {
        val replies = listOf(
            { json("{}", 401) },
            { json("{}", 403) },
            { json("""{"code":401,"message":"unauthorized"}""") },
            { html("<html><title>登录</title><form action='/login'><input type='password' name='password'></form></html>") },
        )
        replies.forEach { reply ->
            apiResponse = { reply() }
            val state = awaitState { source.getExamItems(apiToken().apply { webCookies["SESSION"] = "local-web-session" }, term) }
            assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
            assertEquals("深圳教务会话已失效", state.message)
            assertNull(state.data)
        }
        assertEquals(4, apiRequests.size)
        assertEquals(0, refreshCount.get())
        assertEquals(0, credentialLookups.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiBusinessAndHttpFailuresKeepTheirMessages() {
        apiResponse = { json("""{"code":500,"msg":"考试服务暂不可用"}""") }
        val business = awaitState { source.getExamItems(apiToken(), term) }
        assertEquals(DataState.STATE.FETCH_FAILED, business.state)
        assertEquals("考试服务暂不可用", business.message)

        apiResponse = { json("""{"code":500}""") }
        assertEquals("查询失败", awaitState { source.getExamItems(apiToken(), term) }.message)

        apiResponse = { json("{}", 503) }
        val http = awaitState { source.getExamItems(apiToken(), term) }
        assertEquals(DataState.STATE.FETCH_FAILED, http.state)
        assertEquals("网络请求失败 HTTP 503", http.message)
        assertEquals(3, apiRequests.size)
    }

    @Test
    fun apiMalformedJsonAndTransportFailureReturnFailureWithAnExceptionMessage() {
        val replies = listOf(
            { json("{\"invalid\":") },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        replies.forEach { reply ->
            apiResponse = { reply() }
            val state = awaitState { source.getExamItems(apiToken(), term) }
            assertEquals(DataState.STATE.FETCH_FAILED, state.state)
            assertTrue(!state.message.isNullOrBlank())
            assertNull(state.data)
        }
        assertEquals(2, apiRequests.size)
    }

    @Test
    fun apiCookieOnlySessionOmitsBearerAndDoesNotSwitchToWeb() {
        val token = apiToken().apply { accessToken = null }
        apiResponse = { json("""{"code":200}""") }

        assertExams(awaitState { source.getExamItems(token, term) })

        val request = apiRequests.single()
        assertNull(request.getHeader("authorization"))
        assertNull(request.getHeader("rolecode"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("api-cookie=local-api-cookie"))
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun webEagerRequestKeepsFormCookiesCallbackAndItsOwnFilteringRules() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = webToken().apply { cookies["api-cookie"] = "local-api-cookie" }
        webResponse = {
            hold(entered, release)
            json("""{"list":[
                {"kcmc":"课程甲","ksrq":"2026-11-02T16:00:00.000+00:00","ksjtsj":"09:00-11:00","jxlmc":"一号楼","cdmc":"一号楼","zwh":"12"},
                {"KCMC":"缺时间","KSRQ":"2026-11-03"},
                {"KCMC":"无效日期","KSRQ":"invalid","KSJTSJ":"09:00-11:00"},
                7,
                {"KCMC":"课程乙","KSRQ":"2026-11-03","KSJTSJ":"13:00-15:00","KSSJDMC":"期中","JXLMC":"楼乙","JXCDMC":"201"}
            ]}""").addHeader("Set-Cookie", "exam-web=updated; Path=/")
        }

        val state = eagerExamResult(entered, release) { source.getExamItems(token, term) }

        assertExams(state,
            expectedExam(type = "考试", location = "一号楼 座位 12", campus = "深圳校区"),
            expectedExam(name = "课程乙", time = "13:00-15:00", type = "期中", location = "楼乙 201", campus = "深圳校区"),
        )
        val request = webRequests.single()
        assertEquals(WEB_EXAM_PATH, request.path)
        assertEquals("POST", request.method)
        assertEquals(mapOf("pxn" to term.yearCode, "pxq" to term.termCode, "pageNum" to "1", "pageSize" to "500"), form(request))
        assertEquals(web.url("/kscxtj/queryXsksByxh").toString(), request.getHeader("Referer"))
        assertEquals(web.url("/").toString().trimEnd('/'), request.getHeader("Origin"))
        assertEquals("01", request.getHeader("RoleCode"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("SESSION=local-web-session"))
        assertFalse(request.getHeader("Cookie").orEmpty().contains("api-cookie"))
        assertEquals("updated", token.webCookies["exam-web"])
        assertFalse(token.cookies.containsKey("exam-web"))
        assertSame(token, refreshedToken.get())
        assertEquals(1, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webNullTermFailsWithoutAnyRequest() {
        val state = awaitState { source.getExamItems(webToken(), null) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("请选择学期后再查询考试安排", state.message)
        assertNull(state.data)
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
        assertEquals(0, refreshCount.get())
    }

    @Test
    fun webEmptyArrayIsSuccessfulAndExpiredSessionIsNotLoggedIn() {
        webResponse = { json("[]") }
        assertExams(awaitState { source.getExamItems(webToken(), term) })

        webResponse = { json("{}", 401) }
        val state = awaitState { source.getExamItems(webToken(), term) }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
        assertEquals("深圳 Web 会话已失效", state.message)
        assertEquals(2, refreshCount.get())
        assertEquals(2, webRequests.size)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webHttpAndMalformedBodyKeepSpecificParseFailureMessage() {
        val replies = listOf({ json("[]", 503) }, { json("{\"invalid\":") })
        replies.forEach { reply ->
            webResponse = { reply() }
            val state = awaitState { source.getExamItems(webToken(), term) }
            assertEquals(DataState.STATE.FETCH_FAILED, state.state)
            assertEquals("考试数据解析失败", state.message)
            assertNull(state.data)
        }
    }

    @Test
    fun webTransportAndCallbackCancellationRemainTerminalFailures() {
        webResponse = { json("invalid-gzip").setHeader("Content-Encoding", "gzip") }
        val transport = awaitState { source.getExamItems(webToken(), term) }
        assertEquals(DataState.STATE.FETCH_FAILED, transport.state)
        assertTrue(!transport.message.isNullOrBlank())
        assertEquals(0, refreshCount.get())

        val callbackFailure = CancellationException("legacy exam callback cancelled")
        val callbackSource = EASWebSource(
            onTokenRefreshed = { throw callbackFailure },
            webHostOverride = web.url("/").toString(),
            examApiHostOverride = api.url("/").toString(),
        )
        webResponse = { json("[]") }
        val callback = awaitState { callbackSource.getExamItems(webToken(), term) }
        assertEquals(DataState.STATE.FETCH_FAILED, callback.state)
        assertEquals(callbackFailure.message, callback.message)
        assertNull(callback.data)
        assertEquals(2, webRequests.size)
    }
}
