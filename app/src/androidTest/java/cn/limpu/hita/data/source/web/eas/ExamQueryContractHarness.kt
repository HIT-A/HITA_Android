package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before

/** All exam HTTP is local; unrelated API/Web requests fail through the existing dispatcher. */
abstract class ExamQueryContractHarness : ShenzhenAcademicContractHarness() {
    protected val legacyCookieCallbacks = AtomicInteger()
    protected val credentialLookups = AtomicInteger()

    @Before
    fun configureExamOnlyHosts() {
        source = EASWebSource(
            onTokenRefreshed = { refreshedToken.set(it); refreshCount.incrementAndGet() },
            credentialProvider = { _, _ -> credentialLookups.incrementAndGet(); null },
            apiHostOverride = api.url("/unrelated-api").toString(),
            webHostOverride = web.url("/").toString(),
            examApiHostOverride = api.url("/").toString(),
        )
    }

    protected fun benbuExamSource() = BenbuEASWebSource(
        teachingBuildingsHostOverride = null,
        onCookiesUpdated = { legacyCookieCallbacks.incrementAndGet() },
        examHostOverride = api.url("/").toString(),
    )

    protected fun weihaiExamSource() = WeihaiEASWebSource(
        teachingBuildingsHostOverride = null,
        onCookiesUpdated = { legacyCookieCallbacks.incrementAndGet() },
        examHostOverride = api.url("/").toString(),
    )

    protected fun legacyToken(campus: EASToken.Campus) = EASToken().apply {
        this.campus = campus
        cookies["JSESSIONID"] = "local-exam-session"
        cookies["HIT"] = "local-hit-cookie"
    }

    protected fun html(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    protected fun examHtml(
        name: String = "课程甲",
        time: String = "2026年11月03日(第10周 星期二)09:00-11:00",
        location: String = "一号楼101",
    ) = """<table class="bot_line">
        <tr><th>序号</th><th>课程名称</th><th>课程代码</th><th>考试地点</th><th>座位</th><th>考试时间</th></tr>
        <tr><td>1</td><td>$name</td><td>CODE-A</td><td>$location</td><td>12</td><td>$time</td></tr>
        </table>""".trimIndent()

    protected fun expectedExam(
        name: String? = "课程甲",
        date: String? = "2026-11-03",
        time: String? = "09:00-11:00",
        type: String? = "期末",
        location: String? = "一号楼101",
        termName: String? = term.name,
        termId: String? = term.id,
        campus: String? = "本部",
    ) = ExamItem().apply {
        courseName = name
        examDate = date
        examTime = time
        examType = type
        examLocation = location
        this.termName = termName
        this.termId = termId
        campusName = campus
    }

    protected fun assertExams(state: DataState<List<ExamItem>>, vararg expected: ExamItem) {
        assertEquals(state.message, DataState.STATE.SUCCESS, state.state)
        assertEquals(expected.map(::fields), requireNotNull(state.data).map(::fields))
    }

    protected fun fields(item: ExamItem) = listOf(
        item.memoId, item.courseName, item.examDate, item.examTime, item.examType,
        item.examLocation, item.termName, item.termId, item.campusName,
    )

    protected fun assertLegacyHeaders(request: RecordedRequest) {
        assertTrue(request.getHeader("Cookie").orEmpty().contains("JSESSIONID=local-exam-session"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("HIT=local-hit-cookie"))
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", request.getHeader("Accept"))
        assertTrue(request.getHeader("User-Agent").orEmpty().contains("Macintosh"))
    }

    protected fun assertBenbuRequest(request: RecordedRequest, period: String) {
        assertEquals("GET", request.method)
        assertEquals(BENBU_EXAM_PATH, request.requestUrl?.encodedPath)
        assertEquals(setOf("xnxq", "kssjd"), request.requestUrl?.queryParameterNames)
        assertEquals(term.getCode(), request.requestUrl?.queryParameter("xnxq"))
        assertEquals(period, request.requestUrl?.queryParameter("kssjd"))
        assertEquals("", request.body.clone().readUtf8())
        assertLegacyHeaders(request)
    }

    protected fun assertWeihaiRequest(request: RecordedRequest, period: String, termCode: String = term.getCode()) {
        assertEquals("POST", request.method)
        assertEquals(WEIHAI_EXAM_PATH, request.path)
        assertEquals(mapOf("xnxq" to termCode, "kssjd" to period), form(request))
        assertEquals("https://webvpn.hitwh.edu.cn", request.getHeader("Origin"))
        assertEquals(api.url(WEIHAI_EXAM_PATH).toString(), request.getHeader("Referer"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertLegacyHeaders(request)
    }

    protected fun hold(entered: CountDownLatch, release: CountDownLatch) {
        entered.countDown()
        assertTrue("Held exam response was not released", release.await(20, TimeUnit.SECONDS))
    }

    protected fun eagerExamResult(
        entered: CountDownLatch,
        release: CountDownLatch,
        loading: Boolean = false,
        query: () -> LiveData<DataState<List<ExamItem>>>,
    ): DataState<List<ExamItem>> {
        lateinit var live: LiveData<DataState<List<ExamItem>>>
        try {
            instrumentation.runOnMainSync {
                live = query()
                assertNull("No campus sets a synchronous initial exam value", live.value)
                assertTrue(!live.hasObservers())
            }
            assertTrue("Exam query did not start before observation", entered.await(20, TimeUnit.SECONDS))
            return observe { live }.use { observed ->
                if (loading) {
                    val initial = observed.await { it.state == DataState.STATE.LOADING }
                    assertNull(initial.data)
                    assertNull(initial.message)
                } else {
                    assertTrue(observed.values.isEmpty())
                }
                release.countDown()
                val state = observed.await { it.state != DataState.STATE.NOTHING && it.state != DataState.STATE.LOADING }
                assertEquals(if (loading) listOf(DataState.STATE.LOADING, state.state) else listOf(state.state), observed.values.map { it.state })
                state
            }
        } finally {
            release.countDown()
        }
    }

    protected companion object {
        const val API_EXAM_PATH = "/incoSpringBoot/app/kscx/queryKsxxByXs"
        const val WEB_EXAM_PATH = "/kscxtj/queryXsksByxhList"
        const val BENBU_EXAM_PATH = "/kscx/queryKcForXs1"
        const val WEIHAI_EXAM_PATH = "/kscx/queryKcForXs"
        val PERIODS = listOf("01", "02", "03")
    }
}
