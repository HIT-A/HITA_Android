package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import com.limpu.component.data.DataState
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.URLDecoder
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 固定深圳课表入口的请求与业务结果，供拆分类接入前后运行同一组回归。
 * 响应均为合成夹具；API 与 Web 使用不同端口，禁止请求真实教务。
 */
@RunWith(AndroidJUnit4::class)
class ShenzhenTimetableContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val api = MockWebServer()
    private val web = MockWebServer()
    private val apiRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val webRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val unexpectedRequests = CopyOnWriteArrayList<String>()
    private val refreshedToken = AtomicReference<EASToken>()
    private var apiResponse: (RecordedRequest) -> MockResponse = { unexpected("API", it) }
    private var webResponse: (RecordedRequest) -> MockResponse = { unexpected("Web", it) }
    private lateinit var source: EASWebSource
    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")

    @Before
    fun setUp() {
        api.dispatcher = dispatcher(apiRequests) { apiResponse(it) }
        web.dispatcher = dispatcher(webRequests) { webResponse(it) }
        val loopback = InetAddress.getByName("127.0.0.1")
        api.start(loopback, 0)
        web.start(loopback, 0)
        source = EASWebSource(
            onTokenRefreshed = { refreshedToken.set(it) },
            apiHostOverride = api.url("/").toString(),
            webHostOverride = web.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        try {
            api.shutdown()
        } finally {
            web.shutdown()
        }
        assertTrue("未配置的请求：" + unexpectedRequests, unexpectedRequests.isEmpty())
    }

    @Test
    fun subjectsPreferApiWhenBothSessionsExist() {
        apiResponse = { json(fixture("selected.json")) }
        val token = apiToken().apply { webCookies["SESSION"] = "expired-local-web" }

        val subjects = success { source.getSubjectsOfTerm(token, term) }

        val subject = subjects.single()
        assertEquals("CS1001", subject.code)
        assertEquals("数据结构", subject.name)
        assertEquals("测试教师甲", subject.teacher)
        assertEquals("测试学院", subject.school)
        assertEquals("selection-local-1", subject.key)
        assertEquals(3f, subject.credit, 0f)
        assertTrue(webRequests.isEmpty())
        val request = apiRequests.single()
        assertEquals("/app/Xsxk/queryYxkc?_lang=zh_CN", request.path)
        assertEquals("POST", request.method)
        assertEquals("bearer local-api-token", request.getHeader("authorization"))
        assertEquals("01", request.getHeader("rolecode"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("api-cookie=local-api-cookie"))
        assertTrue(!request.getHeader("Cookie").orEmpty().contains("SESSION"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("2026-2027", body.getString("p_xn"))
        assertEquals("1", body.getString("p_xq"))
        assertEquals(term.getCode(), body.getString("p_xnxq"))
        assertEquals("yixuan", body.getString("p_xkfsdm"))
    }

    @Test
    fun subjectsUseWebFallbackAndPreserveCookieRefreshCallback() {
        webResponse = {
            json(fixture("selected.json")).addHeader("Set-Cookie", "SESSION=renewed-local-web; Path=/")
        }
        val token = webToken()

        val subjects = success { source.getSubjectsOfTerm(token, term) }

        assertEquals("CS1001", subjects.single().code)
        assertEquals("数据结构", subjects.single().name)
        assertEquals("测试教师甲", subjects.single().teacher)
        assertEquals("renewed-local-web", token.webCookies["SESSION"])
        assertSame(token, refreshedToken.get())
        assertTrue(apiRequests.isEmpty())
        assertSelectedRequest(webRequests.single())
    }

    @Test
    fun webSubjectsDistinguishHttpFailureFromEmptySuccess() {
        webResponse = { json("""{"code":500,"message":"upstream unavailable"}""", 500) }
        assertEquals(
            DataState.STATE.FETCH_FAILED,
            awaitState { source.getSubjectsOfTerm(webToken(), term) }.state,
        )
        webResponse = { json("""{"yxkcList":[]}""") }
        assertTrue(success { source.getSubjectsOfTerm(webToken(), term) }.isEmpty())
    }

    @Test
    fun webSubjectsRecognizeHttpAndHtmlAuthenticationFailures() {
        for (status in listOf(401, 200)) {
            webResponse = {
                json("""<html><form action="/authentication/login"><input name="password"></form></html>""", status)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
            }
            assertEquals(
                DataState.STATE.NOT_LOGGED_IN,
                awaitState { source.getSubjectsOfTerm(webToken(), term) }.state,
            )
        }
        assertEquals(2, webRequests.size)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun apiTimetableUsesOverviewAndPreservesItsMissingWeeks() {
        apiResponse = { json(fixture("overview.json")) }
        val token = apiToken().apply { webCookies["SESSION"] = "expired-local-web" }

        val courses = success { source.getTimetableOfTerm(term, token) }

        assertEquals(
            listOf(CourseView("数据结构", "CS1001", "测试教师甲", "B101", 1, 1, 2, emptyList())),
            courses.map(::courseView),
        )
        assertEquals("/app/kbrcbyapp/querykbrczong", apiRequests.single().path)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun weekFallbackSplitsAdjacentPeriodsByTheirOverlappingWeeks() {
        weekFallback { week ->
            when (week) {
                1 -> matrix(listOf(row(1, 2), row(3, 4)))
                2 -> matrix(listOf(row(1, 2)))
                else -> matrix(emptyList())
            }
        }

        val courses = success { source.getTimetableOfTerm(term, apiToken()) }

        assertEquals(
            listOf(
                CourseView("数据结构", "CS1001", "测试教师甲", "B101", 1, 1, 4, listOf(1)),
                CourseView("数据结构", "CS1001", "测试教师甲", "B101", 1, 1, 2, listOf(2)),
            ),
            courses.map(::courseView).sortedBy { it.weeks.first() },
        )
        assertEquals(25, apiRequests.count { it.path == "/app/Kbcx/query" })
        assertEquals(27, apiRequests.size)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun weekFallbackEnrichesTruncatedNamesFromTheMatchingDaySchedule() {
        weekFallback { week ->
            if (week == 1) {
                matrix(listOf(row(1, 2, "数据结...\n[B101]")), listOf("2026-09-07"))
            } else {
                matrix(emptyList())
            }
        }
        val fallback = apiResponse
        apiResponse = { request ->
            if (request.path == "/app/kbrcbyapp/querykbrcbyday") {
                json("""{"content":[{"KCMC":"数据结构","CDMC":"B101","KSJC":1,"JSJC":2,"DJ":1,"XQJ":1}]}""")
            } else {
                fallback(request)
            }
        }

        val courses = success { source.getTimetableOfTerm(term, apiToken()) }

        assertEquals("数据结构", courses.single().name)
        assertEquals("B101", courses.single().classroom)
        val request = apiRequests.single { it.path == "/app/kbrcbyapp/querykbrcbyday" }
        assertEquals("2026-09-07", JSONObject(request.body.readUtf8()).getString("nyr"))
    }

    @Test
    fun emptyApiTimetableKeepsItsExistingFailureState() {
        weekFallback { matrix(emptyList()) }

        val result = awaitState { source.getTimetableOfTerm(term, apiToken()) }

        assertEquals(DataState.STATE.FETCH_FAILED, result.state)
        assertEquals("未获取到课表数据", result.message)
        assertEquals(25, apiRequests.count { it.path == "/app/Kbcx/query" })
    }

    @Test
    fun webTimetableUsesSelectedMetadataAndPreservesWeeksAndPeriods() {
        webResponse = { request ->
            when (request.path) {
                "/xszykb/queryxszykbzong" -> json(fixture("web-timetable.json"))
                SELECTED_PATH -> json(fixture("selected.json"))
                else -> unexpected("Web", request)
            }
        }

        val courses = success { source.getTimetableOfTerm(term, webToken()) }

        assertEquals(
            listOf(CourseView("数据结构", "CS1001", "测试教师甲", "B101", 1, 5, 2, listOf(1, 2, 4))),
            courses.map(::courseView),
        )
        val request = webRequests.first()
        assertEquals("/xszykb/queryxszykbzong", request.path)
        assertEquals(web.url("/xszykb/queryxszykb").toString(), request.getHeader("Referer"))
        assertEquals(mapOf("xn" to "2026-2027", "xq" to "1"), form(request))
        assertSelectedRequest(webRequests.last())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun emptyWebTimetableKeepsItsExistingSuccessState() {
        webResponse = { request ->
            when (request.path) {
                "/xszykb/queryxszykbzong" -> json("[]")
                SELECTED_PATH -> json("""{"yxkcList":[]}""")
                else -> unexpected("Web", request)
            }
        }

        assertTrue(success { source.getTimetableOfTerm(term, webToken()) }.isEmpty())
    }

    @Test
    fun apiStartDateUsesTheFirstTeachingWeekWhileBothSessionsExist() {
        apiResponse = { json(fixture("first-week.json")) }
        val token = apiToken().apply { webCookies["SESSION"] = "expired-local-web" }

        val date = success { source.getStartDate(token, term) }

        assertEquals(listOf(2026, 9, 7), dateParts(date))
        assertEquals("/app/Kbcx/query", apiRequests.single().path)
        assertEquals("1", JSONObject(apiRequests.single().body.readUtf8()).getString("zc"))
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun webStartDateUsesTheEarliestDateAndPlanningReferer() {
        webResponse = {
            json("""{"content":[{"RQ":"2026-09-08"},{"RQ":"2026-09-07"}]}""")
        }

        val date = success { source.getStartDate(webToken(), term) }

        assertEquals(listOf(2026, 9, 7), dateParts(date))
        val request = webRequests.single()
        assertEquals("/component/queryRlZcSj", request.path)
        assertEquals(mapOf("xn" to "2026-2027", "xq" to "1", "djz" to "1"), form(request))
        assertEquals(web.url("/Xsxk/query/1").toString(), request.getHeader("Referer"))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun planningStartDateUsesWebEvenWhenAnApiSessionIsAvailable() {
        webResponse = { json("""{"content":[{"RQ":"2026-09-07"}]}""") }
        val token = apiToken().apply { webCookies["SESSION"] = "local-web-session" }

        val date = success { source.getShenzhenCoursePlanningStartDate(token, term) }

        assertEquals(listOf(2026, 9, 7), dateParts(date))
        assertEquals("/component/queryRlZcSj", webRequests.single().path)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun apiStructurePreservesUndergraduatePeriodsAndSourcePriority() {
        apiResponse = { json(fixture("first-week.json")) }
        val token = apiToken().apply { webCookies["SESSION"] = "expired-local-web" }

        val schedule = success { source.getScheduleStructure(term, true, token) }

        assertEquals(listOf(listOf(8, 30, 9, 20), listOf(9, 25, 10, 15)), schedule.map(::period))
        assertEquals("/app/Kbcx/query", apiRequests.single().path)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiStructurePreservesGraduateFallbackWhenTheRequestFails() {
        apiResponse = { json("""{"code":500}""", 500) }
        val token = apiToken().apply { stutype = EASToken.TYPE.GRAD }

        val schedule = success { source.getScheduleStructure(term, null, token) }

        assertEquals(12, schedule.size)
        assertEquals(listOf(8, 0, 8, 50), period(schedule.first()))
        assertEquals(listOf(21, 40, 22, 30), period(schedule.last()))
    }

    @Test
    fun webStructurePreservesPeriodOrderAndDefaultReferer() {
        webResponse = { json(fixture("periods.json")) }

        val schedule = success { source.getScheduleStructure(term, true, webToken()) }

        assertEquals(listOf(listOf(8, 30, 9, 20), listOf(9, 25, 10, 15)), schedule.map(::period))
        val request = webRequests.single()
        assertEquals("/component/queryKbjg", request.path)
        assertEquals(mapOf("xn" to "2026-2027", "xq" to "1", "pylx" to "1"), form(request))
        assertEquals(web.url("/authentication/main").toString(), request.getHeader("Referer"))
    }

    @Test
    fun planningStructurePrefersPeriodsIncludedWithSelectedCourses() {
        webResponse = {
            json(
                JSONObject(fixture("selected.json"))
                    .put("kbjclist", JSONObject(fixture("periods.json")).getJSONArray("content"))
                    .toString()
            )
        }

        val schedule = success { source.getShenzhenCoursePlanningScheduleStructure(webToken(), term) }

        assertEquals(2, schedule.size)
        assertEquals(listOf(8, 30, 9, 20), period(schedule.first()))
        assertSelectedRequest(webRequests.single())
    }

    @Test
    fun planningStructureFallsBackToPeriodEndpointWithItsOwnReferer() {
        webResponse = { request ->
            when (request.path) {
                SELECTED_PATH -> json(fixture("selected.json"))
                "/component/queryKbjg" -> json(fixture("periods.json"))
                else -> unexpected("Web", request)
            }
        }

        val schedule = success { source.getShenzhenCoursePlanningScheduleStructure(webToken(), term) }

        assertEquals(2, schedule.size)
        assertSelectedRequest(webRequests.first())
        assertEquals(web.url("/Xsxk/query/1").toString(), webRequests.last().getHeader("Referer"))
    }

    @Test
    fun selectedCourseCatalogPreservesTaskAndSelectionIdentity() {
        webResponse = { json(fixture("selected.json")) }

        val courses = success { source.getShenzhenSelectedCourses(webToken(), term) }

        val course = courses.single()
        assertEquals("CS1001", course.courseCode)
        assertEquals("数据结构", course.courseName)
        assertEquals("测试教师甲", course.teacher)
        assertEquals("2026-2027-1-CS1001-001", course.taskId)
        assertEquals("selection-local-1", course.selectionRequestId)
        assertSelectedRequest(webRequests.single())
    }

    @Test
    fun planningEntriesRequireWebSessionWithoutSendingRequests() {
        val token = apiToken()

        assertEquals(
            DataState.STATE.NOT_LOGGED_IN,
            awaitState { source.getShenzhenCoursePlanningStartDate(token, term) }.state,
        )
        assertEquals(
            DataState.STATE.NOT_LOGGED_IN,
            awaitState { source.getShenzhenCoursePlanningScheduleStructure(token, term) }.state,
        )
        assertEquals(
            DataState.STATE.NOT_LOGGED_IN,
            awaitState { source.getShenzhenSelectedCourses(token, term) }.state,
        )
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
    }

    private fun weekFallback(week: (Int) -> String) {
        apiResponse = { request ->
            when (request.path) {
                "/app/kbrcbyapp/querykbrczong" -> json("""{"content":[]}""")
                "/app/Xsxk/queryYxkc?_lang=zh_CN" -> json(fixture("selected.json"))
                "/app/Kbcx/query" -> json(week(JSONObject(request.body.readUtf8()).getInt("zc")))
                else -> unexpected("API", request)
            }
        }
    }

    private fun matrix(rows: List<JSONObject>, dates: List<String> = emptyList()): String =
        JSONObject().put("code", 200).put(
            "content",
            JSONArray().put(
                JSONObject().put("kcxxList", JSONArray(rows)).put(
                    "rqList",
                    JSONArray(dates.map { JSONObject().put("RQ", it) }),
                )
            ),
        ).toString()

    private fun row(begin: Int, end: Int, name: String = "数据结构\n[B101]") =
        JSONObject().put("XQJ", 1).put("KSJC", begin).put("JSJC", end)
            .put("KCDM", "CS1001").put("KBXX", name).put("JSXM", "测试教师甲")

    private fun assertSelectedRequest(request: RecordedRequest) {
        assertEquals(SELECTED_PATH, request.path)
        assertEquals("POST", request.method)
        assertEquals(web.url("/Xsxk/query/1").toString(), request.getHeader("Referer"))
        assertEquals("01", request.getHeader("RoleCode"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("SESSION=local-web-session"))
        assertTrue(!request.getHeader("Cookie").orEmpty().contains("api-cookie"))
        val fields = form(request)
        assertEquals("2026-2027", fields["p_xn"])
        assertEquals("1", fields["p_xq"])
        assertEquals(term.getCode(), fields["p_xnxq"])
        assertEquals("1", fields["p_pylx"])
        assertEquals("yixuan", fields["p_xkfsdm"])
        assertEquals("200", fields["pageSize"])
        assertTrue(fields.containsKey("p_kcdm_cxrw_zckc"))
    }

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split("&").associate { pair ->
            val parts = pair.split("=", limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

    private fun fixture(name: String): String =
        instrumentation.context.assets.open("shenzhen/timetable/" + name).bufferedReader().use { it.readText() }

    private fun apiToken() = EASToken().apply {
        accessToken = "local-api-token"
        cookies["api-cookie"] = "local-api-cookie"
    }

    private fun webToken() = EASToken().apply {
        webCookies["SESSION"] = "local-web-session"
    }

    private fun json(body: String, status: Int = 200): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private fun unexpected(server: String, request: RecordedRequest): MockResponse {
        unexpectedRequests.add(server + " " + request.path)
        return json("""{"code":500,"message":"unconfigured local response"}""", 500)
    }

    private fun dispatcher(
        requests: MutableList<RecordedRequest>,
        respond: (RecordedRequest) -> MockResponse,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            return respond(request)
        }
    }

    private fun <T> success(block: () -> LiveData<DataState<T>>): T {
        val result = awaitState(block)
        assertEquals(result.message, DataState.STATE.SUCCESS, result.state)
        return requireNotNull(result.data)
    }

    private fun <T> awaitState(block: () -> LiveData<DataState<T>>): DataState<T> {
        val done = CountDownLatch(1)
        val result = AtomicReference<DataState<T>>()
        val observer = Observer<DataState<T>> { state ->
            if (state.state != DataState.STATE.NOTHING && state.state != DataState.STATE.LOADING) {
                result.set(state)
                done.countDown()
            }
        }
        lateinit var liveData: LiveData<DataState<T>>
        instrumentation.runOnMainSync {
            liveData = block()
            liveData.observeForever(observer)
        }
        return try {
            assertTrue("等待课表结果超时", done.await(20, TimeUnit.SECONDS))
            requireNotNull(result.get())
        } finally {
            instrumentation.runOnMainSync { liveData.removeObserver(observer) }
        }
    }

    private fun period(value: TimePeriodInDay) =
        listOf(value.from.hour, value.from.minute, value.to.hour, value.to.minute)

    private fun dateParts(value: Calendar) =
        listOf(value.get(Calendar.YEAR), value.get(Calendar.MONTH) + 1, value.get(Calendar.DAY_OF_MONTH))

    private fun courseView(value: CourseItem) = CourseView(
        value.name, value.code, value.teacher, value.classroom, value.dow, value.begin, value.last, value.weeks,
    )

    private data class CourseView(
        val name: String?,
        val code: String?,
        val teacher: String?,
        val classroom: String?,
        val weekday: Int,
        val begin: Int,
        val periods: Int,
        val weeks: List<Int>,
    )

    companion object {
        private const val SELECTED_PATH = "/Xsxk/queryYxkc?sf_request_type=ajax"
    }
}
