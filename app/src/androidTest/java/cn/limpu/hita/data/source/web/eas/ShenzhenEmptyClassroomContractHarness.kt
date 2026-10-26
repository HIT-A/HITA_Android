package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Source contracts use only the two loopback servers; unrelated requests fail in the base harness. */
abstract class ShenzhenEmptyClassroomContractHarness : ShenzhenAcademicContractHarness() {
    protected val building = BuildingItem().apply { id = " 01 "; name = "一号楼" }

    protected fun query(
        token: EASToken = apiToken(),
        weeks: List<String> = listOf("2"),
        queryTerm: TermItem = term,
        querySource: EASWebSource = source,
    ): LiveData<DataState<List<ClassroomItem>>> =
        querySource.queryEmptyClassroom(token, queryTerm, building, weeks)

    protected fun classroomSource(onRefresh: (EASToken) -> Unit = {
        refreshedToken.set(it)
        refreshCount.incrementAndGet()
    }) = EASWebSource(
        onTokenRefreshed = onRefresh,
        apiHostOverride = api.url("/").toString(),
        webHostOverride = web.url("/").toString(),
    )

    protected fun matrix(date: String = "2026-09-09"): MockResponse = json(
        """{"content":[{"rqList":[{"RQ":" "}]},7,{"rqList":[{"RQ":" $date "}]},{"rqList":[{"RQ":"1999-01-01"}]}]}""",
    )

    protected fun overview(date: String = "2026-09-09"): MockResponse = json(
        """{"content":[
            {"XN":"1998-1999","XQ":"1","RQ":"1999-01-01"},
            {"XN":"${term.yearCode}","XQ":"2","RQ":"2026-02-02"},
            {"XN":" ${term.yearCode} ","XQ":" ${term.termCode} ","RQ":" $date "},
            {"XN":"${term.yearCode}","XQ":"${term.termCode}","RQ":"$date"}
        ]}""".trimIndent(),
    )

    protected fun emptyDaily(request: RecordedRequest): MockResponse {
        assertEquals(DAILY_PATH, request.path)
        return json("""{"content":[]}""")
    }

    protected fun badGzip() = json("invalid-gzip").setHeader("Content-Encoding", "gzip")

    protected data class Slot(val day: Int, val period: Int, val occupied: String = "占用")
    protected data class Room(
        val id: String,
        val name: String = id,
        val capacity: Int = 0,
        val special: String? = "",
        val schedule: List<Slot> = emptyList(),
    )

    protected fun roomFields(item: ClassroomItem) = Room(
        item.id, item.name, item.capacity, item.specialClassroom,
        item.scheduleList.map { row ->
            assertEquals(setOf("XQJ", "XJ", "PKBJ"), row.keys().asSequence().toSet())
            Slot(row.getInt("XQJ"), row.getInt("XJ"), row.getString("PKBJ"))
        },
    )

    protected fun assertStateMetadata(state: DataState<List<ClassroomItem>>) {
        assertFalse(state.fromCache)
        assertEquals(DataState.LIST_ACTION.REPLACE_ALL, state.listAction)
        assertEquals(DataState.STATE.NOTHING, state.stateRetried)
    }

    protected fun assertRooms(state: DataState<List<ClassroomItem>>, vararg expected: Room) {
        assertEquals(state.message, DataState.STATE.SUCCESS, state.state)
        assertNull(state.message)
        assertStateMetadata(state)
        assertEquals(expected.toList(), requireNotNull(state.data).map(::roomFields))
    }

    protected fun assertFailure(
        state: DataState<List<ClassroomItem>>,
        expectedState: DataState.STATE = DataState.STATE.FETCH_FAILED,
        message: String? = null,
    ) {
        assertEquals(expectedState, state.state)
        assertEquals(message, state.message)
        assertNull(state.data)
        assertStateMetadata(state)
    }

    protected fun assertApiPost(request: RecordedRequest, authorization: String = "bearer local-api-token") {
        assertEquals("POST", request.method)
        assertEquals(authorization, request.getHeader("authorization"))
        assertEquals("06", request.getHeader("rolecode"))
        assertEquals("cn", request.getHeader("_lang"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("api-cookie=local-api-cookie"))
        assertFalse(request.getHeader("Cookie").orEmpty().contains("local-web-session"))
    }

    protected fun webForm(week: Int) = linkedMapOf(
        "pxn" to term.yearCode, "pxq" to term.termCode, "dmmc" to "", "xiaoqu" to "",
        "jxl" to building.id, "cdlb" to "", "zc" to "0".repeat(week - 1) + "1" + "0".repeat(34 - week),
        "wpksfxs" to "0", "qsjsz" to "16", "kjs" to "0", "xsbkycd" to "0", "zws" to "", "lc" to "",
    )

    protected fun assertWebPost(request: RecordedRequest, week: Int) {
        assertEquals("POST", request.method)
        val left = request.path == LEFT_PATH
        assertTrue(left || request.path == RIGHT_PATH)
        assertEquals(webForm(week) + if (left) mapOf("pageNum" to "1", "pageSize" to "500") else emptyMap(), form(request))
        assertEquals("01", request.getHeader("RoleCode"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertEquals(web.url("/").toString().trimEnd('/'), request.getHeader("Origin"))
        assertEquals(web.url("/cdkb/querycdzy").toString(), request.getHeader("Referer"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("SESSION=local-web-session"))
        assertFalse(request.getHeader("Cookie").orEmpty().contains("local-api-cookie"))
    }

    protected fun hold(entered: CountDownLatch, release: CountDownLatch) {
        entered.countDown()
        assertTrue("Held classroom response was not released", release.await(20, TimeUnit.SECONDS))
    }

    protected fun eagerResult(
        entered: CountDownLatch,
        release: CountDownLatch,
        query: () -> LiveData<DataState<List<ClassroomItem>>>,
    ): DataState<List<ClassroomItem>> {
        lateinit var live: LiveData<DataState<List<ClassroomItem>>>
        try {
            instrumentation.runOnMainSync {
                live = query()
                assertNull(live.value)
                assertFalse(live.hasObservers())
            }
            assertTrue("Classroom request did not start without an observer", entered.await(20, TimeUnit.SECONDS))
            return observe { live }.use { observed ->
                assertTrue(observed.values.isEmpty())
                release.countDown()
                val state = observed.await { it.state != DataState.STATE.NOTHING && it.state != DataState.STATE.LOADING }
                assertEquals(listOf(state), observed.values.toList())
                state
            }
        } finally {
            release.countDown()
        }
    }

    protected companion object {
        const val MATRIX_PATH = "/app/Kbcx/query"
        const val OVERVIEW_PATH = "/app/kbrcbyapp/querykbrczong"
        const val DAILY_PATH = "/app/kbrcbyapp/querycdzyxx"
        const val LEFT_PATH = "/cdkb/querycdzyleftzhou?sf_request_type=ajax"
        const val RIGHT_PATH = "/cdkb/querycdzyrightzhou?sf_request_type=ajax"
        val MATRIX_WEEK_DATES = (7..13).map { "2026-09-${it.toString().padStart(2, '0')}" }
        val OVERVIEW_WEEK_TWO_DATES = (14..20).map { "2026-09-$it" }
    }
}
