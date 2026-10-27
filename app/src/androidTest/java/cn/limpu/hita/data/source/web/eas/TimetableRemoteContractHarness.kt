package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.net.InetAddress
import java.net.URLDecoder
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before

/** Synthetic HTTP fixtures only. The legacy harness also compiles against the frozen pre-Q3 source. */
abstract class TimetableRemoteContractHarness {
    protected val server = MockWebServer()
    protected val requests = CopyOnWriteArrayList<RecordedRequest>()
    protected val unexpected = CopyOnWriteArrayList<String>()
    protected val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
    protected var response: (RecordedRequest) -> MockResponse = { defaultResponse(it) }
    private val releases = CopyOnWriteArrayList<CountDownLatch>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var fixtureHost: String

    @Before
    fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return response(request)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // Resolve the fixture host on the instrumentation worker, never in a main-thread query lambda.
        fixtureHost = server.url("/").toString()
    }

    @After
    fun stopServer() {
        releases.forEach { it.countDown() }
        server.shutdown()
        assertTrue("Unexpected fixture requests: $unexpected", unexpected.isEmpty())
    }

    protected fun gate() = CountDownLatch(1).also { releases += it }

    protected fun legacySource(campus: EASToken.Campus): EASService = when (campus) {
        EASToken.Campus.BENBU -> BenbuEASWebSource().also {
            // Reflection changes only hosts, so the same regression exercises the actual old cache.
            replaceHost(it, "hostName")
            replaceHost(it, "experimentHostName")
            replaceHost(it, "electronicExpHostName")
        }
        EASToken.Campus.WEIHAI -> WeihaiEASWebSource().also { replaceHost(it, "hostName") }
        EASToken.Campus.SHENZHEN -> shenzhenSource()
    }

    protected fun shenzhenSource() = EASWebSource(
        onTokenRefreshed = {},
        apiHostOverride = fixtureHost,
        webHostOverride = fixtureHost,
    )

    private fun replaceHost(source: Any, name: String) {
        source.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .set(source, fixtureHost.trimEnd('/'))
    }

    protected fun token(campus: EASToken.Campus = EASToken.Campus.BENBU, owner: String = "student-a") = EASToken().apply {
        this.campus = campus
        username = owner
        stuId = owner
        cookies["JSESSIONID"] = owner
        if (campus == EASToken.Campus.SHENZHEN) accessToken = "local-api"
    }

    protected fun webToken() = token(EASToken.Campus.SHENZHEN).apply {
        accessToken = null
        webCookies["SESSION"] = "local-web"
    }

    protected fun <T> awaitLegacy(query: () -> LiveData<DataState<T>>): DataState<T> {
        val done = CountDownLatch(1)
        val state = AtomicReference<DataState<T>>()
        lateinit var source: LiveData<DataState<T>>
        val observer = Observer<DataState<T>> { value ->
            if (value != null && value.state != DataState.STATE.NOTHING && value.state != DataState.STATE.LOADING) {
                state.set(value)
                done.countDown()
            }
        }
        instrumentation.runOnMainSync { source = query(); source.observeForever(observer) }
        try {
            assertTrue("Legacy query must return a terminal state", done.await(20, TimeUnit.SECONDS))
            return requireNotNull(state.get())
        } finally {
            instrumentation.runOnMainSync { source.removeObserver(observer) }
        }
    }

    protected fun <T> success(state: DataState<T>): T {
        assertEquals(state.message, DataState.STATE.SUCCESS, state.state)
        return requireNotNull(state.data)
    }

    protected fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    protected fun html(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    protected fun asset(name: String): String = instrumentation.context.assets
        .open("shenzhen/timetable/$name").bufferedReader().use { it.readText() }

    protected fun form(request: RecordedRequest): Map<String, String> = request.body.clone().readUtf8()
        .split('&').filter { it.isNotEmpty() }.associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }

    protected fun dateParts(date: Calendar) = listOf(date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH))
    protected fun period(period: TimePeriodInDay) = listOf(period.from.hour, period.from.minute, period.to.hour, period.to.minute)

    protected fun regularHtml(name: String = "普通课程", adjacent: Boolean = false): String = """
        <table class="addlist_01">
          <tr><th></th><th></th><th>星期一</th></tr>
          <tr><td></td><td>第1-2节</td><td>$name<br/>教师甲[1-2]周正心12</td></tr>
          ${if (adjacent) "<tr><td></td><td>第3-4节</td><td>$name<br/>教师甲[1-2]周正心12</td></tr>" else ""}
        </table>
    """.trimIndent()

    protected fun experimentHtml() = """
        <table class="dataTable">
          <tr><th>实验课表</th></tr><tr><th>星期</th></tr>
          <tr><td>下午</td><td>第5，6节</td><td>物理实验<br/>教师乙 [2周第5，6节] 上课形式[线下]L101，13:00-15:00</td>${"<td></td>".repeat(6)}</tr>
        </table>
    """.trimIndent()

    protected fun electronicJson() = """{"code":0,"data":[
      {"subjectName":"电子实验","classDate":"2026-09-08","startTime":"13:00","endTime":"15:00","teacher":"教师丙","address":"电机楼301","labsName":"电路室"},
      {"subjectName":"电子实验","classDate":"2026-09-08","startTime":"16:00","endTime":"","teacher":"教师丙","address":"电机楼301","labsName":"电路室"},
      {"subjectName":"坏记录","classDate":"invalid","startTime":"13:00"}
    ]}"""

    protected fun calendarHtml() = """<table data-year-month="2026-09"><tr><th>周次</th><th>星期一</th></tr><tr><td>1</td><td>2026-09-07</td></tr></table>"""

    protected fun defaultResponse(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
        "/kbcx/queryGrkb" -> html(regularHtml())
        "/loginsysj/loginSygl" -> html("<html>local experiment login</html>")
        "/xskb/queryXszkb" -> html("<table class=dataTable></table>")
        "/api/stu/viewCKKB" -> json("""{"code":0,"data":[]}""")
        "/xlcx/queryXlcx" -> html(calendarHtml())
        else -> {
            unexpected += request.path.orEmpty()
            html("unexpected fixture path", 500)
        }
    }

    protected fun assertRegular(course: CourseItem, last: Int = 2) {
        assertEquals("普通课程", course.name)
        assertEquals("教师甲", course.teacher)
        assertEquals("正心12", course.classroom)
        assertEquals(listOf(1, 2), course.weeks)
        assertEquals(1, course.dow)
        assertEquals(1, course.begin)
        assertEquals(last, course.last)
    }
}
