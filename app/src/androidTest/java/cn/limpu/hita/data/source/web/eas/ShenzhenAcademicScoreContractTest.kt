package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ScoreSummaryScope
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 公共成绩入口的旧实现基线；只使用合成响应与本地服务器。 */
@RunWith(AndroidJUnit4::class)
class ShenzhenAcademicScoreContractTest : ShenzhenAcademicContractHarness() {
    @Test
    fun apiScoresPreferApiWithBothSessionsAndPreserveNormalRequest() {
        apiResponse = { json(API_SCORES) }

        val item = success {
            source.getPersonalScores(term, bothSessions(), EASService.TestType.NORMAL)
        }.single()

        assertApiScore(item)
        assertTrue(webRequests.isEmpty())
        val request = apiRequests.single()
        assertEquals(API_SCORES_PATH, request.path)
        assertEquals("POST", request.method)
        assertEquals("bearer local-api-token", request.getHeader("authorization"))
        assertEquals("06", request.getHeader("rolecode"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("api-cookie=local-api-cookie"))
        assertFalse(request.getHeader("Cookie").orEmpty().contains("SESSION"))
        assertScoreTermBody(jsonBody(request), "qm")
    }

    @Test
    fun apiResitUsesMidtermFlagOnApiEndpoint() {
        apiResponse = { json(API_SCORES) }
        val token = bothSessions()

        assertApiScore(success { source.getPersonalScores(term, token, EASService.TestType.RESIT) }.single())

        assertScoreTermBody(jsonBody(apiRequests.single()), "qz")
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiFailuresAndExpiredSessionsNeverSwitchToAvailableWebSession() {
        val cases = listOf(
            Triple(401, "{}", DataState.STATE.NOT_LOGGED_IN),
            Triple(200, """{"code":2005,"message":"session ended"}""", DataState.STATE.NOT_LOGGED_IN),
            Triple(200, "not-json", DataState.STATE.FETCH_FAILED),
            Triple(200, "{}", DataState.STATE.FETCH_FAILED),
            Triple(500, """{"code":500,"message":"upstream failed"}""", DataState.STATE.FETCH_FAILED),
        )
        cases.forEach { (status, body, expected) ->
            apiResponse = { json(body, status) }

            val state = awaitState {
                source.getPersonalScores(term, bothSessions(), EASService.TestType.NORMAL)
            }

            assertEquals("HTTP $status: $body", expected, state.state)
            assertNull(state.data)
        }
        assertEquals(cases.size, apiRequests.size)
        assertTrue(apiRequests.all { it.path == API_SCORES_PATH })
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiEmptyScoreArrayIsSuccessful() {
        apiResponse = { json("""{"content":[]}""") }

        assertTrue(success {
            source.getPersonalScores(term, apiToken(), EASService.TestType.NORMAL)
        }.isEmpty())

        assertEquals(1, apiRequests.size)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun webNormalScoresUseWebJsonParametersAndRefreshCookies() {
        webResponse = {
            json(WEB_SCORES).addHeader("Set-Cookie", "SESSION=renewed-local-web; Path=/")
        }
        val token = webToken()

        val item = success {
            source.getPersonalScores(term, token, EASService.TestType.NORMAL)
        }.single()

        assertEquals("CS2002", item.courseCode)
        assertEquals("概率论", item.courseName)
        assertEquals("86.5", item.finalScoresText)
        assertEquals(86, item.finalScores)
        assertEquals(2.5f, item.credits, 0f)
        assertEquals("测试学院", item.schoolName)
        assertEquals("2026-2027 秋季", item.termName)
        assertEquals("renewed-local-web", token.webCookies["SESSION"])
        assertSame(token, refreshedToken.get())
        assertTrue(apiRequests.isEmpty())
        val request = webRequests.single()
        assertEquals(WEB_SCORES_PATH, request.path)
        assertEquals("POST", request.method)
        assertEquals(web.url("/cjgl/grcjcx").toString(), request.getHeader("Referer"))
        assertEquals("01", request.getHeader("RoleCode"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("SESSION=local-web-session"))
        val body = jsonBody(request)
        assertEquals(term.yearCode, body.getString("xn"))
        assertEquals(term.termCode, body.getString("xq"))
        assertEquals("1", body.getString("pylx"))
        assertEquals(1, body.getInt("current"))
        assertEquals(1000, body.getInt("pageSize"))
        assertEquals("-1", body.getString("cxbj"))
        assertTrue(body.isNull("kcmc"))
        assertTrue(body.isNull("xscjlb"))
        assertTrue(body.isNull("sffx"))
    }

    @Test
    fun webResitUsesMidtermEndpointWithoutNormalOnlyFilters() {
        webResponse = { json(WEB_SCORES) }
        val token = webToken().apply { stutype = EASToken.TYPE.GRAD }

        assertEquals("CS2002", success {
            source.getPersonalScores(term, token, EASService.TestType.RESIT)
        }.single().courseCode)

        val request = webRequests.single()
        assertEquals("/cjgl/grcjcx/qzcjcx", request.path)
        assertEquals(web.url("/cjgl/grcjcx").toString(), request.getHeader("Referer"))
        val body = jsonBody(request)
        assertEquals(term.yearCode, body.getString("xn"))
        assertEquals(term.termCode, body.getString("xq"))
        assertEquals("2", body.getString("pylx"))
        assertEquals("", body.getString("kcmc"))
        assertEquals(1, body.getInt("current"))
        assertEquals(1000, body.getInt("pageSize"))
        assertFalse(body.has("cxbj"))
        assertFalse(body.has("xscjlb"))
        assertFalse(body.has("sffx"))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webEmptyScoreListIsSuccessful() {
        webResponse = { json("""{"code":0,"content":{"list":[]}}""") }

        assertTrue(success {
            source.getPersonalScores(term, webToken(), EASService.TestType.NORMAL)
        }.isEmpty())

        assertEquals(1, webRequests.size)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webScoresDistinguishBusinessMalformedAndHttpFailures() {
        val cases = listOf(
            Triple(200, """{"code":500,"msg":"synthetic score rejection"}""", "synthetic score rejection"),
            Triple(200, "not-json", "非 JSON"),
            Triple(500, WEB_SCORES, "HTTP 500"),
        )
        cases.forEach { (status, body, message) ->
            webResponse = { json(body, status) }

            val state = awaitState {
                source.getPersonalScores(term, webToken(), EASService.TestType.NORMAL)
            }

            assertEquals(DataState.STATE.FETCH_FAILED, state.state)
            assertTrue(state.message.orEmpty(), state.message.orEmpty().contains(message))
            assertNull(state.data)
        }
        assertEquals(cases.size, webRequests.size)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webScoresRecognizeHttpJsonAndHtmlAuthenticationFailures() {
        val cases = listOf(
            403 to "{}",
            200 to """{"code":401,"message":"synthetic session ended"}""",
            200 to """<html><form action="/authentication/login"><input name="password"></form></html>""",
        )
        cases.forEach { (status, body) ->
            webResponse = { json(body, status) }

            val state = awaitState {
                source.getPersonalScores(term, webToken(), EASService.TestType.NORMAL)
            }

            assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
            assertEquals("深圳 Web 会话已失效", state.message)
            assertNull(state.data)
        }
        assertEquals(cases.size, webRequests.size)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun apiSummaryPublishesListThenCumulativeThenOfficialSelectedTermSummary() {
        val cumulativeRequested = CountDownLatch(1)
        val releaseCumulative = CountDownLatch(1)
        val officialRequested = CountDownLatch(1)
        val releaseOfficial = CountDownLatch(1)
        apiResponse = { request ->
            when (request.path) {
                API_SCORES_PATH -> json(API_SCORES_WITH_INLINE_SUMMARY)
                API_SUMMARY_PATH -> {
                    cumulativeRequested.countDown()
                    assertGate(releaseCumulative)
                    json(LEGACY_SUMMARY)
                }
                else -> error("未配置 API ${request.path}")
            }
        }
        webResponse = { request ->
            assertEquals(OFFICIAL_SUMMARY_PATH, request.path)
            officialRequested.countDown()
            assertGate(releaseOfficial)
            json(OFFICIAL_SUMMARY)
        }
        try {
            observe { source.getPersonalScoresWithSummary(term, bothSessions(), EASService.TestType.NORMAL) }.use { observed ->
                assertGate(cumulativeRequested)
                val initial = observed.await { it.data?.summary?.weightedAverage == "78.5" }
                assertEquals(DataState.STATE.SUCCESS, initial.state)
                assertApiScore(requireNotNull(initial.data).items.single())
                assertEquals(ScoreSummaryScope.UNKNOWN, initial.data?.summary?.scope)
                assertEquals("2.7", initial.data?.summary?.gpa)
                assertTrue(webRequests.isEmpty())

                releaseCumulative.countDown()
                assertGate(officialRequested)
                val cumulative = observed.await { it.data?.summary?.scope == ScoreSummaryScope.CUMULATIVE }
                assertEquals("81.25", cumulative.data?.summary?.weightedAverage)
                assertEquals("", cumulative.data?.summary?.gpa)
                assertEquals("7", cumulative.data?.summary?.rank)
                assertEquals("60", cumulative.data?.summary?.total)
                assertApiScore(requireNotNull(cumulative.data).items.single())

                releaseOfficial.countDown()
                val final = observed.await { it.data?.summary?.scope == ScoreSummaryScope.SELECTED_TERM }
                assertEquals(DataState.STATE.SUCCESS, final.state)
                assertEquals("90.5", final.data?.summary?.weightedAverage)
                assertEquals("3.8", final.data?.summary?.gpa)
                assertEquals("2", final.data?.summary?.rank)
                assertEquals("61", final.data?.summary?.total)
                assertEquals("24", final.data?.summary?.earnedCredits)
                assertApiScore(requireNotNull(final.data).items.single())
            }
        } finally {
            releaseCumulative.countDown()
            releaseOfficial.countDown()
        }
        assertEquals(listOf(API_SCORES_PATH, API_SUMMARY_PATH), apiRequests.map { it.path })
        val cumulativeRequest = apiRequests.last()
        assertEquals("06", cumulativeRequest.getHeader("rolecode"))
        assertEquals("-1-1", jsonBody(cumulativeRequest).getString("ksxnxq"))
        assertEquals("-1-1", jsonBody(cumulativeRequest).getString("jsxnxq"))
        assertEquals("1", jsonBody(cumulativeRequest).getString("pylx"))
        assertOfficialSummaryRequest()
    }

    @Test
    fun expiredOfficialSummaryPreservesApiItemsAndCumulativeScope() {
        apiResponse = { request ->
            when (request.path) {
                API_SCORES_PATH -> json(API_SCORES)
                API_SUMMARY_PATH -> json(LEGACY_SUMMARY)
                else -> error("未配置 API ${request.path}")
            }
        }
        webResponse = { request ->
            assertEquals(OFFICIAL_SUMMARY_PATH, request.path)
            json("""{"code":2005,"message":"synthetic session ended"}""")
        }

        observe { source.getPersonalScoresWithSummary(term, bothSessions(), EASService.TestType.NORMAL) }.use { observed ->
            val state = observed.await { it.message == "深圳 Web 会话已失效" }
            assertEquals(DataState.STATE.SUCCESS, state.state)
            assertApiScore(requireNotNull(state.data).items.single())
            assertEquals(ScoreSummaryScope.CUMULATIVE, state.data?.summary?.scope)
            assertEquals("81.25", state.data?.summary?.weightedAverage)
            assertEquals("", state.data?.summary?.gpa)
        }
        assertEquals(listOf(API_SCORES_PATH, API_SUMMARY_PATH), apiRequests.map { it.path })
        assertOfficialSummaryRequest()
    }

    @Test
    fun unavailableOfficialSummaryKeepsCumulativeResultAfterQueryFinishes() {
        val cases = listOf(500 to "{}", 200 to "not-json", 200 to "{}")
        apiResponse = { request ->
            when (request.path) {
                API_SCORES_PATH -> json(API_SCORES)
                API_SUMMARY_PATH -> json(LEGACY_SUMMARY)
                else -> error("未配置 API ${request.path}")
            }
        }
        cases.forEach { (status, body) ->
            webResponse = { request ->
                assertEquals(OFFICIAL_SUMMARY_PATH, request.path)
                json(body, status)
            }

            observe { source.getPersonalScoresWithSummary(term, bothSessions(), EASService.TestType.NORMAL) }.use { observed ->
                observed.await { it.data?.summary?.scope == ScoreSummaryScope.CUMULATIVE }
                awaitWorkerCompletion()

                val final = observed.values.last()
                assertEquals("HTTP $status: $body", DataState.STATE.SUCCESS, final.state)
                assertApiScore(requireNotNull(final.data).items.single())
                assertEquals(ScoreSummaryScope.CUMULATIVE, final.data?.summary?.scope)
                assertEquals("81.25", final.data?.summary?.weightedAverage)
                assertEquals("", final.data?.summary?.gpa)
                assertNull(final.message)
            }
        }
        assertEquals(cases.size * 2, apiRequests.size)
        assertEquals(List(cases.size) { OFFICIAL_SUMMARY_PATH }, webRequests.map { it.path })
    }

    @Test
    fun unavailableLegacySummaryDoesNotBlockOfficialEnrichment() {
        apiResponse = { request ->
            when (request.path) {
                API_SCORES_PATH -> json(API_SCORES)
                API_SUMMARY_PATH -> json("not-json")
                else -> error("未配置 API ${request.path}")
            }
        }
        webResponse = { request ->
            assertEquals(OFFICIAL_SUMMARY_PATH, request.path)
            json(OFFICIAL_SUMMARY)
        }

        observe { source.getPersonalScoresWithSummary(term, bothSessions(), EASService.TestType.RESIT) }.use { observed ->
            val state = observed.await { it.data?.summary?.scope == ScoreSummaryScope.SELECTED_TERM }
            assertEquals(DataState.STATE.SUCCESS, state.state)
            assertApiScore(requireNotNull(state.data).items.single())
            assertEquals("3.8", state.data?.summary?.gpa)
        }
        assertScoreTermBody(jsonBody(apiRequests.first()), "qz")
        assertOfficialSummaryRequest()
    }

    @Test
    fun webOnlySummaryPublishesListBeforeOfficialSelectedTermSummary() {
        val officialRequested = CountDownLatch(1)
        val releaseOfficial = CountDownLatch(1)
        webResponse = { request ->
            if (request.path == WEB_SCORES_PATH) {
                json(WEB_SCORES)
            } else {
                assertEquals(OFFICIAL_SUMMARY_PATH, request.path)
                officialRequested.countDown()
                assertGate(releaseOfficial)
                json(OFFICIAL_SUMMARY)
            }
        }
        try {
            observe { source.getPersonalScoresWithSummary(term, webToken(), EASService.TestType.NORMAL) }.use { observed ->
                assertGate(officialRequested)
                val initial = observed.await { it.state == DataState.STATE.SUCCESS && it.data?.summary == null }
                assertEquals("CS2002", requireNotNull(initial.data).items.single().courseCode)
                assertNull(initial.data?.summary)

                releaseOfficial.countDown()
                val final = observed.await { it.data?.summary?.scope == ScoreSummaryScope.SELECTED_TERM }
                assertEquals("CS2002", requireNotNull(final.data).items.single().courseCode)
                assertEquals("3.8", final.data?.summary?.gpa)
            }
        } finally {
            releaseOfficial.countDown()
        }
        assertTrue(apiRequests.isEmpty())
        assertEquals(listOf(WEB_SCORES_PATH, OFFICIAL_SUMMARY_PATH), webRequests.map { it.path })
        assertOfficialSummaryRequest()
    }

    @Test
    fun expiredWebOnlyOfficialSummaryKeepsSuccessfulScoreList() {
        webResponse = { request ->
            when (request.path) {
                "/cjgl/grcjcx/qzcjcx" -> json(WEB_SCORES)
                OFFICIAL_SUMMARY_PATH -> json("{}", 401)
                else -> error("未配置 Web ${request.path}")
            }
        }

        observe { source.getPersonalScoresWithSummary(term, webToken(), EASService.TestType.RESIT) }.use { observed ->
            val state = observed.await { it.message == "深圳 Web 会话已失效" }
            assertEquals(DataState.STATE.SUCCESS, state.state)
            assertEquals("CS2002", requireNotNull(state.data).items.single().courseCode)
            assertNull(state.data?.summary)
        }
        assertTrue(apiRequests.isEmpty())
        assertEquals(listOf("/cjgl/grcjcx/qzcjcx", OFFICIAL_SUMMARY_PATH), webRequests.map { it.path })
        assertOfficialSummaryRequest()
    }

    private fun bothSessions() = apiToken().apply {
        webCookies["SESSION"] = "local-web-session"
    }

    private fun assertApiScore(item: CourseScoreItem) {
        assertEquals("CS1001", item.courseCode)
        assertEquals("数据结构", item.courseName)
        assertEquals(3.5f, item.credits, 0f)
        assertEquals("87", item.finalScoresText)
        assertEquals(87, item.finalScores)
        assertEquals("必修", item.courseProperty)
        assertEquals("专业基础", item.courseCategory)
        assertEquals("考试", item.assessMethod)
        assertEquals(term.yearCode + term.termCode, item.termName)
    }

    private fun assertScoreTermBody(body: JSONObject, flag: String) {
        assertEquals(term.yearCode, body.getString("xn"))
        assertEquals(term.termCode, body.getString("xq"))
        assertEquals(flag, body.getString("qzqmFlag"))
        assertEquals("json", body.getString("type"))
    }

    private fun assertOfficialSummaryRequest() {
        val request = webRequests.single { it.path == OFFICIAL_SUMMARY_PATH }
        assertEquals(web.url("/cjgl/grcjcx").toString(), request.getHeader("Referer"))
        val body = jsonBody(request)
        assertEquals(term.yearCode, body.getString("xn"))
        assertEquals(term.termCode, body.getString("xq"))
        assertEquals("1", body.getString("pylx"))
    }

    private fun assertGate(latch: CountDownLatch) {
        assertTrue("等待成绩请求同步点超时", latch.await(15, TimeUnit.SECONDS))
    }

    companion object {
        private const val API_SCORES_PATH = "/app/cjgl/xscjList?_lang=zh_CN"
        private const val API_SUMMARY_PATH = "/app/cjgl/xfj"
        private const val WEB_SCORES_PATH = "/cjgl/grcjcx/grcjcx"
        private const val OFFICIAL_SUMMARY_PATH = "/cjgl/grcjcx/getgpa"
        private const val API_SCORES = """{"content":[{"kcdm":"CS1001","kcmc":"数据结构","xf":"3.5","zf":" 87 ","kcxz":"必修","kclbmc":"专业基础","khfs":"考试"}]}"""
        private const val API_SCORES_WITH_INLINE_SUMMARY = """{"content":[{"kcdm":"CS1001","kcmc":"数据结构","xf":"3.5","zf":" 87 ","kcxz":"必修","kclbmc":"专业基础","khfs":"考试"}],"summary":{"xfjd":"78.5","gpa":"2.7","pm":"12","pmrs":"60"}}"""
        private const val WEB_SCORES = """{"code":0,"content":{"list":[{"kcdm":"CS2002","kcmc":"概率论","xf":"2.5","xscj":"86.5","yxmc":"测试学院","xnxqmc":"2026-2027 秋季"}]}}"""
        private const val LEGACY_SUMMARY = """{"content":{"xfj":{"XFJ":"81.25","RANK":"7","ZYZRS":"60"}}}"""
        private const val OFFICIAL_SUMMARY = """{"PJXFJ":"90.5","GPA":"3.8","PJXFJ_PM":"2","ZRS":"61","HDXF":"24","TGKC":"8"}"""
    }
}
