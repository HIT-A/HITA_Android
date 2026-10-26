package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before

/** Local HTTP contracts for the original Benbu/Weihai classroom entry points. */
abstract class LegacyEmptyClassroomContractHarness : ShenzhenAcademicContractHarness() {
    protected val legacyCookieCallbacks = AtomicInteger()
    protected var classroomResponse: (RecordedRequest) -> MockResponse = {
        throw AssertionError("Unconfigured classroom request ${it.path}")
    }
    protected var probeResponse: (RecordedRequest) -> MockResponse = {
        throw AssertionError("Unconfigured classroom building probe ${it.path}")
    }

    @Before
    fun routeOnlyClassroomRequests() {
        apiResponse = { request ->
            when (request.path) {
                BENBU_CLASSROOM_PATH, WEIHAI_CLASSROOM_PATH -> classroomResponse(request)
                BUILDING_PROBE_PATH -> probeResponse(request)
                else -> throw AssertionError("Unexpected local classroom path ${request.path}")
            }
        }
    }

    protected fun benbuClassroomSource() = BenbuEASWebSource(
        teachingBuildingsHostOverride = api.url("/unrelated-building").toString(),
        onCookiesUpdated = { legacyCookieCallbacks.incrementAndGet() },
        examHostOverride = api.url("/unrelated-exam").toString(),
        classroomHostOverride = api.url("/").toString(),
    )

    protected fun weihaiClassroomSource() = WeihaiEASWebSource(
        teachingBuildingsHostOverride = api.url("/unrelated-building").toString(),
        onCookiesUpdated = { legacyCookieCallbacks.incrementAndGet() },
        examHostOverride = api.url("/unrelated-exam").toString(),
        classroomHostOverride = api.url("/").toString(),
    )

    protected fun legacyToken(campus: EASToken.Campus) = EASToken().apply {
        this.campus = campus
        cookies["JSESSIONID"] = "local-classroom-session"
        cookies["HIT"] = "local-classroom-hit"
    }

    protected fun building(id: String = "12", name: String? = "教学楼") = BuildingItem().apply {
        this.id = id
        this.name = name
    }

    protected fun html(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    protected fun roomRow(
        name: String = "101",
        metadata: List<String> = listOf("60人"),
        occupiedBlocks: Set<Int> = setOf(0, 7, 41),
        cellTag: String = "td",
    ): String = buildString {
        append("<tr><$cellTag>$name</$cellTag>")
        metadata.forEach { append("<$cellTag>$it</$cellTag>") }
        repeat(42) { block ->
            val content = when {
                block in occupiedBlocks -> "<span class=\"kjs_icon01\"></span>"
                block == 1 -> "占用文字本身不算占用"
                else -> "<span class=\"kjs_icon02\"></span>"
            }
            append("<$cellTag>$content</$cellTag>")
        }
        append("</tr>")
    }

    protected fun classroomHtml(vararg rows: String): String =
        "<table class=\"dataTable\">${rows.joinToString("")}</table>"

    protected fun standardClassroomHtml() = classroomHtml(roomRow())

    protected data class ExpectedRoom(
        val name: String = "101",
        val capacity: Int = 60,
        val occupied: Set<Pair<Int, Int>> = setOf(1 to 1, 1 to 2, 2 to 3, 2 to 4, 7 to 11, 7 to 12),
    )

    /** ClassroomItem.equals omits schedules and specialClassroom, so compare all fields. */
    protected fun assertClassrooms(state: DataState<List<ClassroomItem>>, vararg expected: ExpectedRoom) {
        assertMetadata(state, DataState.STATE.SUCCESS)
        val actual = requireNotNull(state.data)
        assertEquals(expected.size, actual.size)
        actual.zip(expected.toList()).forEach { (room, wanted) ->
            assertEquals(wanted.name, room.name)
            assertEquals(wanted.name, room.id)
            assertEquals(wanted.capacity, room.capacity)
            assertEquals("", room.specialClassroom)
            val expectedSchedule = (1..7).flatMap { day ->
                (1..12).map { period ->
                    Triple(day, period, if ((day to period) in wanted.occupied) "占用" else "")
                }
            }
            val actualSchedule = room.scheduleList.map { entry ->
                assertEquals(setOf("XQJ", "XJ", "PKBJ"), entry.keys().asSequence().toSet())
                Triple(entry.getInt("XQJ"), entry.getInt("XJ"), entry.getString("PKBJ"))
            }
            assertEquals(expectedSchedule, actualSchedule)
        }
    }

    protected fun assertMetadata(
        state: DataState<List<ClassroomItem>>,
        expectedState: DataState.STATE,
        message: String? = null,
    ) {
        assertEquals(expectedState, state.state)
        assertEquals(message, state.message)
        assertFalse(state.fromCache)
        assertEquals(DataState.STATE.NOTHING, state.stateRetried)
        assertEquals(DataState.LIST_ACTION.REPLACE_ALL, state.listAction)
        if (expectedState != DataState.STATE.SUCCESS) assertNull(state.data)
    }

    protected fun assertLegacyCookies(request: RecordedRequest) {
        assertTrue(request.getHeader("Cookie").orEmpty().contains("JSESSIONID=local-classroom-session"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("HIT=local-classroom-hit"))
        assertFalse(request.getHeader("Cookie").orEmpty().contains("ignored-replacement"))
        assertTrue(request.getHeader("User-Agent").orEmpty().contains("Macintosh"))
    }

    protected fun assertClassroomRequest(
        request: RecordedRequest,
        weihai: Boolean,
        campus: String = "",
        buildingId: String = "12",
        room: String = "",
        firstWeek: String = "1",
        lastWeek: String = firstWeek,
    ) {
        assertEquals("POST", request.method)
        assertEquals(if (weihai) WEIHAI_CLASSROOM_PATH else BENBU_CLASSROOM_PATH, request.path)
        assertEquals(
            mapOf(
                "pageXnxq" to term.getCode(), "pageZc1" to firstWeek, "pageZc2" to lastWeek,
                "pageXiaoqu" to campus, "pageLhdm" to buildingId, "pageCddm" to room,
            ),
            form(request),
        )
        assertLegacyCookies(request)
        assertEquals("zh-CN,zh-Hans;q=0.9", request.getHeader("Accept-Language"))
        if (weihai) {
            assertEquals("application/json, text/javascript, */*; q=0.01", request.getHeader("Accept"))
            assertEquals("https://webvpn.hitwh.edu.cn", request.getHeader("Origin"))
            assertEquals(api.url(BENBU_CLASSROOM_PATH).toString(), request.getHeader("Referer"))
            assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
            assertEquals("same-origin", request.getHeader("sec-fetch-site"))
            assertEquals("cors", request.getHeader("sec-fetch-mode"))
            assertEquals("empty", request.getHeader("sec-fetch-dest"))
        } else {
            assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", request.getHeader("Accept"))
            assertNull(request.getHeader("Origin"))
            assertNull(request.getHeader("Referer"))
        }
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
    }

    protected fun assertProbe(request: RecordedRequest, campus: String) {
        assertEquals("POST", request.method)
        assertEquals(BUILDING_PROBE_PATH, request.path)
        assertEquals(mapOf("id" to campus), form(request))
        assertLegacyCookies(request)
        assertEquals("application/json, text/javascript, */*; q=0.01", request.getHeader("Accept"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertNull(request.getHeader("Origin"))
        assertNull(request.getHeader("Referer"))
    }

    protected fun requestTrace(): List<String> = apiRequests.map { request ->
        val fields = form(request)
        when (request.path) {
            BUILDING_PROBE_PATH -> "probe:${fields["id"]}"
            BENBU_CLASSROOM_PATH, WEIHAI_CLASSROOM_PATH ->
                "query:${fields["pageXiaoqu"]}|${fields["pageLhdm"]}|${fields["pageCddm"]}"
            else -> throw AssertionError("Unexpected path ${request.path}")
        }
    }

    protected fun eagerClassroomResult(
        entered: CountDownLatch,
        release: CountDownLatch,
        query: () -> LiveData<DataState<List<ClassroomItem>>>,
    ): DataState<List<ClassroomItem>> {
        lateinit var live: LiveData<DataState<List<ClassroomItem>>>
        try {
            instrumentation.runOnMainSync {
                live = query()
                assertMetadata(requireNotNull(live.value), DataState.STATE.NOTHING)
                assertFalse("Legacy query must start without observing", live.hasObservers())
            }
            assertTrue("No eager classroom request arrived", entered.await(20, TimeUnit.SECONDS))
            return observe { live }.use { observed ->
                assertEquals(listOf(DataState.STATE.NOTHING), observed.values.map { it.state })
                release.countDown()
                val state = observed.await { it.state != DataState.STATE.NOTHING && it.state != DataState.STATE.LOADING }
                assertEquals(listOf(DataState.STATE.NOTHING, state.state), observed.values.map { it.state })
                state
            }
        } finally {
            release.countDown()
        }
    }

    protected fun hold(entered: CountDownLatch, release: CountDownLatch) {
        entered.countDown()
        assertTrue("Held classroom response was not released", release.await(20, TimeUnit.SECONDS))
    }

    protected companion object {
        const val BENBU_CLASSROOM_PATH = "/kjscx/queryKjs"
        const val WEIHAI_CLASSROOM_PATH = "/kjscx/queryKjs?vpn-12-o1-jwts.hitwh.edu.cn"
        const val BUILDING_PROBE_PATH = "/kjscx/queryJxlListBySjid?sf_request_type=ajax"
        val FULL_DEFAULT_FALLBACK = listOf(
            "probe:01", "query:01|12|，", "query:01|12|", "query:01||，", "query:01||",
            "probe:1", "query:1|12|，", "query:1|12|", "query:1||，", "query:1||",
            "probe:2", "query:2|12|，", "query:2|12|", "query:2||，", "query:2||",
            "query:|12|，", "query:|12|", "query:||，", "query:||",
        )
    }
}
