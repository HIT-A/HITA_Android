package cn.limpu.hita.data.source.web.eas

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The independently executed legacy contracts remain unchanged; these exercise the new API. */
@RunWith(AndroidJUnit4::class)
class ExamSuspendContractTest : ExamQueryContractHarness() {
    @Test
    fun shenzhenApiOnceMatchesLegacyRawFieldsDuplicatesBearerPriorityAndFirstPage() = runBlocking {
        apiResponse = {
            json("""{"code":200,"content":{"total":200,"pageSize":20,"list":[
                {"KCMC":"甲","KSRQ":"原日期","KSJTSJ":"原时间","KSSJDMC":"原类型","JXLMC":"楼","JXCDMC":"101","XNXQMC":"远端学期","XIAOQUBMC":"远端校区"},
                7,{}, {"KCMC":"甲","KSRQ":"重复日期"}
            ]}}""").addHeader("Set-Cookie", "api-cookie=replaced; Path=/")
        }
        val state = matchingLegacy(source) { apiToken().apply { webCookies["SESSION"] = "local-web-session" } }

        assertExams(state,
            expectedExam("甲", "原日期", "原时间", "原类型", "楼 101", "远端学期", term.id, "远端校区"),
            expectedExam("", "", "", "", " ", "", term.id, ""),
            expectedExam("甲", "重复日期", "", "", " ", "", term.id, ""),
        )
        assertEquals(listOf(API_EXAM_PATH, API_EXAM_PATH), apiRequests.map { it.path })
        apiRequests.forEach {
            assertEquals("POST", it.method)
            assertEquals("bearer local-api-token", it.getHeader("authorization"))
            assertEquals(1, jsonBody(it).getInt("pageNum"))
            assertEquals(20, jsonBody(it).getInt("pageSize"))
        }
        assertEquals(0, refreshCount.get())
        assertEquals(0, credentialLookups.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun shenzhenApiOnceMatchesNullTermAndCookieOnlyRouting() = runBlocking {
        apiResponse = { json("""{"code":200,"content":{"list":[{"KCMC":"甲"}]}}""") }
        val state = matchingLegacy(source, queryTerm = null) { apiToken().apply { accessToken = null } }

        val body = jsonBody(apiRequests.last())
        assertEquals("2", body.getString("xq"))
        assertExams(state, expectedExam("甲", "", "", "", " ", "", body.getString("xn") + "-2", ""))
        assertEquals(2, apiRequests.size)
        apiRequests.forEach { assertNull(it.getHeader("authorization")); assertNull(it.getHeader("rolecode")) }
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun shenzhenApiOnceMatchesLegacyEmptyAuthBusinessHttpJsonAndTransportFailures() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { json("""{"code":200}""") },
            { json("""{"code":200,"content":{"list":[]}}""") },
            { json("{}", 401) },
            { json("""{"code":401}""") },
            { json("""{"code":500,"msg":"考试服务暂不可用"}""") },
            { json("""{"code":500}""") },
            { json("{}", 503) },
            { json("{\"invalid\":") },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        responses.forEach { response ->
            apiResponse = { response() }
            matchingLegacy(source, token = ::apiToken)
        }
        assertEquals(18, apiRequests.size)
        assertEquals(0, refreshCount.get())
        assertEquals(0, credentialLookups.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun shenzhenWebOnceMatchesLegacyFilteringLocationDateAndCookieCallback() = runBlocking {
        webResponse = {
            json("""{"list":[
                {"kcmc":"课程甲","ksrq":"2026-11-02T16:00:00.000+00:00","ksjtsj":"09:00-11:00","jxlmc":"一号楼","cdmc":"一号楼","zwh":"12"},
                {"KCMC":"缺时间","KSRQ":"2026-11-03"}, 7,
                {"KCMC":"无效日期","KSRQ":"invalid","KSJTSJ":"09:00-11:00"}
            ]}""").addHeader("Set-Cookie", "exam-web=updated; Path=/")
        }
        val state = matchingLegacy(source, token = ::webToken)

        assertExams(state, expectedExam(type = "考试", location = "一号楼 座位 12", campus = "深圳校区"))
        assertEquals(listOf(WEB_EXAM_PATH, WEB_EXAM_PATH), webRequests.map { it.path })
        webRequests.forEach {
            assertEquals("POST", it.method)
            assertEquals(mapOf("pxn" to term.yearCode, "pxq" to term.termCode, "pageNum" to "1", "pageSize" to "500"), form(it))
            assertEquals(web.url("/kscxtj/queryXsksByxh").toString(), it.getHeader("Referer"))
        }
        assertEquals(2, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun shenzhenWebOnceMatchesLegacyNullTermWithoutHttp() = runBlocking {
        val state = matchingLegacy(source, queryTerm = null, token = ::webToken)
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("请选择学期后再查询考试安排", state.message)
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
        assertEquals(0, refreshCount.get())
    }

    @Test
    fun shenzhenWebOnceMatchesLegacyEmptyAuthHttpParseAndTransportMappings() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { json("[]") },
            { json("{}", 401) },
            { json("[]", 503) },
            { json("{\"invalid\":") },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        responses.forEach { response ->
            webResponse = { response() }
            matchingLegacy(source, token = ::webToken)
        }
        assertEquals(10, webRequests.size)
        assertEquals(8, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun benbuOnceMatchesLegacyCategoryOrderFieldsAndUnchangedCookies() = runBlocking {
        apiResponse = { html(examHtml()).addHeader("Set-Cookie", "JSESSIONID=replaced; Path=/") }
        val state = matchingLegacy(benbuExamSource()) { legacyToken(EASToken.Campus.BENBU) }

        assertExams(state, expectedExam(), expectedExam(type = "期中"), expectedExam(type = "补考"))
        assertEquals(PERIODS + PERIODS, apiRequests.map { it.requestUrl?.queryParameter("kssjd") })
        apiRequests.zip(PERIODS + PERIODS).forEach { (request, period) -> assertBenbuRequest(request, period) }
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun benbuOnceMatchesLegacyNullTermWithoutHttp() = runBlocking {
        val state = matchingLegacy(benbuExamSource(), queryTerm = null) { legacyToken(EASToken.Campus.BENBU) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("学期参数为空", state.message)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun benbuOnceMatchesLegacyPartialFailureWarningAndUnsupportedMime() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { html("invalid-gzip").setHeader("Content-Encoding", "gzip") },
            { json("{}") },
            { html("<h1>维护中</h1>") },
            { html("unavailable", 503) },
        )
        responses.forEach { failed ->
            apiResponse = { request ->
                when (request.requestUrl?.queryParameter("kssjd")) {
                    "01" -> failed()
                    "02" -> html(examHtml())
                    else -> html("<h1>维护中</h1>")
                }
            }
            val state = matchingLegacy(benbuExamSource()) { legacyToken(EASToken.Campus.BENBU) }
            assertExams(state, expectedExam(type = "期中"))
            assertEquals("期末、补考考试查询失败，请刷新重试", state.message)
        }
        assertEquals(24, apiRequests.size)
    }

    @Test
    fun benbuOnceMatchesLegacyEmptyVersusAllFailedAndAuthenticationPrefix() = runBlocking {
        val service = benbuExamSource()
        apiResponse = { html("<p>暂无考试安排</p>") }
        assertExams(matchingLegacy(service) { legacyToken(EASToken.Campus.BENBU) })
        apiResponse = { html("unavailable", 503) }
        val failed = matchingLegacy(service) { legacyToken(EASToken.Campus.BENBU) }
        assertEquals(DataState.STATE.FETCH_FAILED, failed.state)
        assertEquals("期末、期中、补考考试查询失败，请刷新重试", failed.message)

        apiResponse = { request ->
            if (request.requestUrl?.queryParameter("kssjd") == "01") html(examHtml()) else html("expired", 403)
        }
        val auth = matchingLegacy(service) { legacyToken(EASToken.Campus.BENBU) }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, auth.state)
        assertEquals("登录已失效，请重新登录", auth.message)
        assertEquals(listOf(fields(expectedExam())), auth.data?.map(::fields))
        assertEquals(listOf("01", "02", "01", "02"), apiRequests.takeLast(4).map { it.requestUrl?.queryParameter("kssjd") })
    }

    @Test
    fun weihaiOnceMatchesLegacyCategoryOrderParserAndUnchangedCookies() = runBlocking {
        apiResponse = { html(examHtml()).addHeader("Set-Cookie", "JSESSIONID=replaced; Path=/") }
        val state = matchingLegacy(weihaiExamSource()) { legacyToken(EASToken.Campus.WEIHAI) }

        assertExams(state, expectedExam(campus = "威海校区"), expectedExam(type = "期中", campus = "威海校区"), expectedExam(type = "补考", campus = "威海校区"))
        assertEquals(PERIODS + PERIODS, apiRequests.map { form(it)["kssjd"] })
        apiRequests.zip(PERIODS + PERIODS).forEach { (request, period) -> assertWeihaiRequest(request, period) }
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun weihaiOnceMatchesLegacyNullTermDefaultAndParserQuirks() = runBlocking {
        apiResponse = { request ->
            if (form(request)["kssjd"] != "01") html("<p>暂无考试安排</p>") else html("""<table class="bot_line">
                <tr><td>not-number</td><td>跳过</td><td>C</td><td>L</td><td>S</td><td>T</td></tr>
                <tr><td>02</td><td></td><td>C</td><td>L</td><td>S</td><td>时间另行通知</td></tr>
                <tr><td>3</td><td>有日期无时间</td><td>C</td><td>L</td><td>S</td><td>2026年11月03日</td></tr>
                <tr><td>4</td><td>历史宽松日期</td><td>C</td><td>L</td><td>S</td><td>2026年99月99日25:99-30:88</td></tr>
                </table>""")
        }
        val state = matchingLegacy(weihaiExamSource(), queryTerm = null) { legacyToken(EASToken.Campus.WEIHAI) }

        assertExams(state,
            expectedExam("", "", "时间另行通知", location = "L", termName = null, termId = null, campus = "威海校区"),
            expectedExam("有日期无时间", time = null, location = "L", termName = null, termId = null, campus = "威海校区"),
            expectedExam("历史宽松日期", "2026-99-99", "25:99-30:88", location = "L", termName = null, termId = null, campus = "威海校区"),
        )
        apiRequests.zip(PERIODS + PERIODS).forEach { (request, period) -> assertWeihaiRequest(request, period, "2025-20262") }
    }

    @Test
    fun weihaiOnceMatchesLegacySkippedHttpLoginHtmlEmptyAndTransportAbort() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { html("<p>暂无考试安排</p>") },
            { html("expired", 401) },
            { html("unavailable", 503) },
            { html("<html><title>登录</title><form action='/login'><input name='password'></form></html>") },
            { html("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        responses.forEachIndexed { index, response ->
            val countBefore = apiRequests.size
            apiResponse = { request -> if (form(request)["kssjd"] == "01") html(examHtml()) else response() }
            val state = matchingLegacy(weihaiExamSource()) { legacyToken(EASToken.Campus.WEIHAI) }
            if (index == responses.lastIndex) {
                assertEquals(DataState.STATE.FETCH_FAILED, state.state)
                assertNull(state.data)
                assertEquals(4, apiRequests.size - countBefore)
            } else {
                assertExams(state, expectedExam(campus = "威海校区"))
                assertNull(state.message)
                assertEquals(6, apiRequests.size - countBefore)
            }
        }
    }

    @Test
    fun shenzhenApiCancellationLeavesMainResponsiveAndDoesNotDeliverResult() {
        cancelledRequest(source, apiToken(), useWeb = false, expected = listOf(API_EXAM_PATH)) { entered, release ->
            apiResponse = { hold(entered, release); json("""{"code":200,"content":{"list":[]}}""") }
        }
    }

    @Test
    fun shenzhenWebCancellationLeavesMainResponsiveAndDoesNotDeliverResult() {
        cancelledRequest(source, webToken(), useWeb = true, expected = listOf(WEB_EXAM_PATH)) { entered, release ->
            webResponse = { hold(entered, release); json("[]") }
        }
    }

    @Test
    fun benbuCancellationLeavesMainResponsiveAndStopsBeforeSecondCategory() {
        cancelledRequest(benbuExamSource(), legacyToken(EASToken.Campus.BENBU), useWeb = false, expected = listOf("01")) { entered, release ->
            apiResponse = { hold(entered, release); html(examHtml()) }
        }
    }

    @Test
    fun benbuCancellationAfterSuccessfulPrefixStopsBeforeResitWithoutPartialResult() {
        cancelledRequest(benbuExamSource(), legacyToken(EASToken.Campus.BENBU), useWeb = false, expected = listOf("01", "02")) { entered, release ->
            apiResponse = { request ->
                if (request.requestUrl?.queryParameter("kssjd") == "02") hold(entered, release)
                html(examHtml())
            }
        }
    }

    @Test
    fun weihaiCancellationLeavesMainResponsiveAndStopsBeforeSecondCategory() {
        cancelledRequest(weihaiExamSource(), legacyToken(EASToken.Campus.WEIHAI), useWeb = false, expected = listOf("01")) { entered, release ->
            apiResponse = { hold(entered, release); html(examHtml()) }
        }
    }

    @Test
    fun weihaiCancellationAfterSuccessfulPrefixStopsBeforeResitWithoutPartialResult() {
        cancelledRequest(weihaiExamSource(), legacyToken(EASToken.Campus.WEIHAI), useWeb = false, expected = listOf("01", "02")) { entered, release ->
            apiResponse = { request ->
                if (form(request)["kssjd"] == "02") hold(entered, release)
                html(examHtml())
            }
        }
    }

    @Test
    fun callbackCancellationEscapesOnceWithoutBecomingBusinessFailure() = runBlocking {
        val sentinel = CancellationException("synthetic exam callback cancellation")
        val service = EASWebSource(
            onTokenRefreshed = { throw sentinel },
            webHostOverride = web.url("/").toString(),
            examApiHostOverride = api.url("/").toString(),
        )
        webResponse = { json("[]") }
        var returned: DataState<List<ExamItem>>? = null
        var caught: CancellationException? = null
        try {
            returned = service.getExamItemsOnce(webToken(), term)
        } catch (error: CancellationException) {
            caught = error
        }
        assertNull(returned)
        assertSame(sentinel, caught)
        assertEquals(listOf(WEB_EXAM_PATH), webRequests.map { it.path })
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun benbuCancellableAggregatorPropagatesOriginalCategoryCancellation() {
        val sentinel = CancellationException("synthetic exam category cancellation")
        val requested = mutableListOf<String>()
        var returned: DataState<List<ExamItem>>? = null
        var caught: CancellationException? = null
        try {
            returned = BenbuExamQuery.loadCancellable(checkCancelled = {}) { code, _ ->
                requested += code
                if (code == "02") throw sentinel
                listOf(expectedExam())
            }
        } catch (error: CancellationException) {
            caught = error
        }
        assertNull(returned)
        assertSame(sentinel, caught)
        assertEquals(listOf("01", "02"), requested)
        assertTrue(apiRequests.isEmpty())
    }

    private suspend fun matchingLegacy(
        service: EASService,
        queryTerm: TermItem? = term,
        token: () -> EASToken,
    ): DataState<List<ExamItem>> {
        val legacyToken = token()
        val onceToken = token()
        val apiBefore = apiRequests.size
        val webBefore = webRequests.size
        val legacy = awaitState { service.getExamItems(legacyToken, queryTerm) }
        val apiAfterLegacy = apiRequests.size
        val webAfterLegacy = webRequests.size
        val once = withContext(Dispatchers.Main) { service.getExamItemsOnce(onceToken, queryTerm) }
        assertEquals(legacy.state, once.state)
        assertEquals(legacy.message, once.message)
        assertEquals(legacy.data?.map(::fields), once.data?.map(::fields))
        assertEquals(legacy.listAction, once.listAction)
        assertEquals(legacy.fromCache, once.fromCache)
        assertEquals(legacy.stateRetried, once.stateRetried)
        assertEquals(legacyToken.cookies, onceToken.cookies)
        assertEquals(legacyToken.webCookies, onceToken.webCookies)
        assertEquals(apiRequests.subList(apiBefore, apiAfterLegacy).map(::requestFields), apiRequests.drop(apiAfterLegacy).map(::requestFields))
        assertEquals(webRequests.subList(webBefore, webAfterLegacy).map(::requestFields), webRequests.drop(webAfterLegacy).map(::requestFields))
        return once
    }

    private fun requestFields(request: RecordedRequest) = listOf(
        request.method, request.path, request.body.clone().readUtf8(),
        request.getHeader("Authorization"), request.getHeader("RoleCode"), request.getHeader("_lang"),
        request.getHeader("Cookie"), request.getHeader("Referer"), request.getHeader("Origin"),
        request.getHeader("Content-Type"), request.getHeader("Accept"), request.getHeader("User-Agent"),
    )

    private fun cancelledRequest(
        service: EASService,
        token: EASToken,
        useWeb: Boolean,
        expected: List<String>,
        configure: (CountDownLatch, CountDownLatch) -> Unit,
    ): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mainResponsive = CountDownLatch(1)
        val returned = AtomicReference<DataState<List<ExamItem>>>()
        val caught = AtomicReference<CancellationException>()
        configure(entered, release)
        val query = launch(Dispatchers.Main) {
            try {
                returned.set(service.getExamItemsOnce(token, term))
            } catch (error: CancellationException) {
                caught.set(error)
                throw error
            }
        }
        try {
            assertTrue("No exam request arrived", entered.await(20, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { mainResponsive.countDown() }
            assertTrue("Exam HTTP blocked the main thread", mainResponsive.await(20, TimeUnit.SECONDS))
            query.cancel(CancellationException("cancel held exam request"))
        } finally {
            query.cancel()
            release.countDown()
            withTimeout(20_000) { query.join() }
        }
        assertNull("Cancelled query must not deliver a DataState", returned.get())
        assertNotNull("Cancellation must propagate to caller", caught.get())
        val requests = if (useWeb) webRequests else apiRequests
        val actual = requests.map { request ->
            when (token.campus) {
                EASToken.Campus.BENBU -> requireNotNull(request.requestUrl?.queryParameter("kssjd"))
                EASToken.Campus.WEIHAI -> requireNotNull(form(request)["kssjd"])
                else -> requireNotNull(request.path)
            }
        }
        assertEquals("No next category after cancellation", expected, actual)
        assertFalse(query.isActive)
    }
}
