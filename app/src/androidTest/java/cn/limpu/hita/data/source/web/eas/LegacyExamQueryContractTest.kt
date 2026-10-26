package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyExamQueryContractTest : ExamQueryContractHarness() {
    @Test
    fun benbuEagerRequestHasNoInitialStateAndReadsAllCategoriesInOrder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = legacyToken(EASToken.Campus.BENBU)
        apiResponse = { request ->
            if (request.requestUrl?.queryParameter("kssjd") == "01") hold(entered, release)
            html(examHtml()).addHeader("Set-Cookie", "JSESSIONID=replaced; Path=/")
        }

        val state = eagerExamResult(entered, release) { benbuExamSource().getExamItems(token, term) }

        assertExams(state, expectedExam(), expectedExam(type = "期中"), expectedExam(type = "补考"))
        assertNull(state.message)
        assertEquals(PERIODS, apiRequests.map { it.requestUrl?.queryParameter("kssjd") })
        apiRequests.zip(PERIODS).forEach { (request, period) -> assertBenbuRequest(request, period) }
        assertEquals("local-exam-session", token.cookies["JSESSIONID"])
        assertEquals(0, legacyCookieCallbacks.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun benbuNullTermFailsWithoutAnyRequest() {
        val state = awaitState { benbuExamSource().getExamItems(legacyToken(EASToken.Campus.BENBU), null) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("学期参数为空", state.message)
        assertNull(state.data)
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun benbuNetworkAndParseFailuresKeepSuccessfulCategoryAndOrderedWarning() {
        apiResponse = { request ->
            when (request.requestUrl?.queryParameter("kssjd")) {
                "01" -> html("invalid-gzip").setHeader("Content-Encoding", "gzip")
                "02" -> html(examHtml())
                "03" -> html("<html><h1>系统维护中</h1></html>")
                else -> throw AssertionError("Unexpected request ${request.path}")
            }
        }

        val state = awaitState { benbuExamSource().getExamItems(legacyToken(EASToken.Campus.BENBU), term) }

        assertExams(state, expectedExam(type = "期中"))
        assertEquals("期末、补考考试查询失败，请刷新重试", state.message)
        assertEquals(PERIODS, apiRequests.map { it.requestUrl?.queryParameter("kssjd") })
    }

    @Test
    fun benbuValidEmptyCategoriesDifferFromEmptyWithFailureAndAllHttpFailures() {
        val service = benbuExamSource()
        apiResponse = { html("<p>暂无考试安排</p>") }
        val empty = awaitState { service.getExamItems(legacyToken(EASToken.Campus.BENBU), term) }
        assertExams(empty)
        assertNull(empty.message)

        apiResponse = { request -> html("<p>暂无考试安排</p>", if (request.requestUrl?.queryParameter("kssjd") == "02") 503 else 200) }
        val mixed = awaitState { service.getExamItems(legacyToken(EASToken.Campus.BENBU), term) }
        assertEquals(DataState.STATE.FETCH_FAILED, mixed.state)
        assertTrue(requireNotNull(mixed.data).isEmpty())
        assertEquals("期中考试查询失败，请刷新重试", mixed.message)

        apiResponse = { html("<p>unavailable</p>", 503) }
        val failed = awaitState { service.getExamItems(legacyToken(EASToken.Campus.BENBU), term) }
        assertEquals(DataState.STATE.FETCH_FAILED, failed.state)
        assertTrue(requireNotNull(failed.data).isEmpty())
        assertEquals("期末、期中、补考考试查询失败，请刷新重试", failed.message)
        assertEquals(PERIODS + PERIODS + PERIODS, apiRequests.map { it.requestUrl?.queryParameter("kssjd") })
    }

    @Test
    fun benbuMidRequestAuthenticationFailurePreservesEarlierRowsAndStopsResit() {
        val replies = listOf(
            { html("<p>expired</p>", 403) },
            { html("<html><title>统一身份认证登录</title><form action='/login'><input name='password'></form></html>") },
        )
        replies.forEach { expired ->
            apiResponse = { request ->
                if (request.requestUrl?.queryParameter("kssjd") == "01") html(examHtml()) else expired()
            }
            val state = awaitState { benbuExamSource().getExamItems(legacyToken(EASToken.Campus.BENBU), term) }
            assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
            assertEquals("登录已失效，请重新登录", state.message)
            assertEquals(listOf(fields(expectedExam())), requireNotNull(state.data).map(::fields))
        }
        assertEquals(listOf("01", "02", "01", "02"), apiRequests.map { it.requestUrl?.queryParameter("kssjd") })
    }

    @Test
    fun benbuUnsupportedMimeIsACategoryFailureRatherThanAQueryAbort() {
        apiResponse = { request ->
            when (request.requestUrl?.queryParameter("kssjd")) {
                "01" -> json("{}")
                "02" -> html(examHtml())
                "03" -> html("<p>暂无考试安排</p>")
                else -> throw AssertionError("Unexpected request ${request.path}")
            }
        }

        val state = awaitState { benbuExamSource().getExamItems(legacyToken(EASToken.Campus.BENBU), term) }

        assertExams(state, expectedExam(type = "期中"))
        assertEquals("期末考试查询失败，请刷新重试", state.message)
        assertEquals(3, apiRequests.size)
    }

    @Test
    fun benbuLegacyAggregatorTreatsCancellationExceptionAsAnOrdinaryCategoryFailure() {
        val requested = mutableListOf<String>()
        val state = BenbuExamQuery.load { code, _ ->
            requested += code
            if (code == "02") throw CancellationException("legacy category cancellation")
            if (code == "01") listOf(expectedExam()) else emptyList()
        }

        assertExams(state, expectedExam())
        assertEquals("期中考试查询失败，请刷新重试", state.message)
        assertEquals(PERIODS, requested)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun weihaiEagerRequestPostsLoadingThenOneResultAndPreservesCategoriesAndCookies() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = legacyToken(EASToken.Campus.WEIHAI)
        apiResponse = { request ->
            if (form(request)["kssjd"] == "01") hold(entered, release)
            html(examHtml()).addHeader("Set-Cookie", "JSESSIONID=replaced; Path=/")
        }

        val state = eagerExamResult(entered, release, loading = true) { weihaiExamSource().getExamItems(token, term) }

        assertExams(state,
            expectedExam(campus = "威海校区"),
            expectedExam(type = "期中", campus = "威海校区"),
            expectedExam(type = "补考", campus = "威海校区"),
        )
        assertNull(state.message)
        assertEquals(PERIODS, apiRequests.map { form(it)["kssjd"] })
        apiRequests.zip(PERIODS).forEach { (request, period) -> assertWeihaiRequest(request, period) }
        assertEquals("local-exam-session", token.cookies["JSESSIONID"])
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun weihaiNullTermKeepsHistoricalDefaultCodeAndNullResultTermFields() {
        apiResponse = { request -> if (form(request)["kssjd"] == "01") html(examHtml()) else html("<p>暂无考试安排</p>") }

        val state = awaitState { weihaiExamSource().getExamItems(legacyToken(EASToken.Campus.WEIHAI), null) }

        assertExams(state, expectedExam(termName = null, termId = null, campus = "威海校区"))
        apiRequests.zip(PERIODS).forEach { (request, period) -> assertWeihaiRequest(request, period, "2025-20262") }
    }

    @Test
    fun weihaiNon200AndLoginHtmlRemainSuccessfulEmptyWithoutAuthenticationMapping() {
        apiResponse = { request ->
            when (form(request)["kssjd"]) {
                "01" -> html("<p>unauthorized</p>", 401)
                "02" -> html("<p>unavailable</p>", 503)
                "03" -> html("<html><title>登录</title><form action='/login'><input name='password'></form></html>")
                else -> throw AssertionError("Unexpected request ${request.path}")
            }
        }

        val state = awaitState { weihaiExamSource().getExamItems(legacyToken(EASToken.Campus.WEIHAI), term) }

        assertExams(state)
        assertNull(state.message)
        assertEquals(PERIODS, apiRequests.map { form(it)["kssjd"] })
    }

    @Test
    fun weihaiSkippedHttpCategoryKeepsOtherRowsWithoutFailureWarning() {
        apiResponse = { request ->
            if (form(request)["kssjd"] == "02") html("unavailable", 503) else html(examHtml())
        }

        val state = awaitState { weihaiExamSource().getExamItems(legacyToken(EASToken.Campus.WEIHAI), term) }

        assertExams(state, expectedExam(campus = "威海校区"), expectedExam(type = "补考", campus = "威海校区"))
        assertNull(state.message)
        assertEquals(3, apiRequests.size)
    }

    @Test
    fun weihaiTransportFailureDiscardsEarlierRowsAndStopsRemainingCategory() {
        apiResponse = { request ->
            if (form(request)["kssjd"] == "01") html(examHtml()) else html("invalid-gzip").setHeader("Content-Encoding", "gzip")
        }

        val state = awaitState { weihaiExamSource().getExamItems(legacyToken(EASToken.Campus.WEIHAI), term) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertTrue(!state.message.isNullOrBlank())
        assertNull(state.data)
        assertEquals(listOf("01", "02"), apiRequests.map { form(it)["kssjd"] })
    }

    @Test
    fun weihaiParserKeepsHistoricalDateFallbacksAndNumericRowRules() {
        apiResponse = { request ->
            if (form(request)["kssjd"] != "01") html("<p>暂无考试安排</p>") else html("""<table class="bot_line">
                <tr><td>not-number</td><td>跳过</td><td>C</td><td>L</td><td>S</td><td>T</td></tr>
                <tr><td>1</td><td>太短</td></tr>
                <tr><td>02</td><td></td><td>C</td><td>L</td><td>S</td><td>时间另行通知</td></tr>
                <tr><td>3</td><td>有日期无时间</td><td>C</td><td>L</td><td>S</td><td>2026年11月03日</td></tr>
                <tr><td>4</td><td>历史宽松日期</td><td>C</td><td>L</td><td>S</td><td>2026年99月99日25:99-30:88</td></tr>
                </table>""")
        }

        val state = awaitState { weihaiExamSource().getExamItems(legacyToken(EASToken.Campus.WEIHAI), term) }

        assertExams(state,
            expectedExam(name = "", date = "", time = "时间另行通知", location = "L", campus = "威海校区"),
            expectedExam(name = "有日期无时间", time = null, location = "L", campus = "威海校区"),
            expectedExam(name = "历史宽松日期", date = "2026-99-99", time = "25:99-30:88", location = "L", campus = "威海校区"),
        )
    }

    @Test
    fun weihaiAllValidEmptyCategoriesReturnSuccessfulEmpty() {
        apiResponse = { html("<table class='bot_line'><tr><td>暂无考试安排</td></tr></table>") }

        val state = awaitState { weihaiExamSource().getExamItems(legacyToken(EASToken.Campus.WEIHAI), term) }

        assertExams(state)
        assertNull(state.message)
        assertEquals(PERIODS, apiRequests.map { form(it)["kssjd"] })
    }
}
