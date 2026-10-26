package cn.limpu.hita.data.source.web.eas

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The executed legacy baseline files are retained; these exercise the independent once entry. */
@RunWith(AndroidJUnit4::class)
class LegacyEmptyClassroomSuspendContractTest : LegacyEmptyClassroomContractHarness() {
    @Test
    fun benbuOnceMatchesIndependentLegacyResultIncludingDuplicateRoomsAndAllScheduleFields() = runBlocking {
        classroomResponse = {
            html(classroomHtml(
                roomRow("A101"),
                roomRow("A101", metadata = emptyList(), occupiedBlocks = emptySet()),
            )).addHeader("Set-Cookie", "JSESSIONID=ignored-replacement; Path=/")
        }

        val state = matchingLegacy(EASToken.Campus.BENBU)

        assertClassrooms(state, ExpectedRoom("A101"), ExpectedRoom("A101", 0, emptySet()))
        assertEquals(listOf("query:|12|"), requestTrace())
        assertClassroomRequest(apiRequests.single(), weihai = false)
    }

    @Test
    fun benbuOnceKeepsRawBuildingAndFirstLastWeekDefaults() = runBlocking {
        classroomResponse = { html(standardClassroomHtml()) }
        val scenarios = listOf(
            Triple(emptyList(), "1", "1"),
            Triple(listOf("4"), "4", "4"),
            Triple(listOf(" 2 ", "ignored", " 8 "), " 2 ", " 8 "),
            Triple(listOf("", "9"), "", "9"),
        )
        scenarios.forEach { (weeks, first, last) ->
            val state = matchingLegacy(EASToken.Campus.BENBU, buildingId = " 001 ", weeks = weeks)
            assertClassrooms(state, ExpectedRoom())
            assertClassroomRequest(apiRequests.single(), weihai = false, buildingId = " 001 ", firstWeek = first, lastWeek = last)
        }
    }

    @Test
    fun benbuOnceMatchesLegacyEmptyLoginHtmlHttpAndTransportMappings() = runBlocking {
        val emptyBodies = listOf("", "<table class=dataTable><tr><td>短行</td></tr></table>", "<form action=login><input name=password></form>")
        emptyBodies.forEach { body ->
            classroomResponse = { html(body) }
            assertClassrooms(matchingLegacy(EASToken.Campus.BENBU))
            assertEquals(listOf("query:|12|"), requestTrace())
        }
        listOf(401, 403, 503).forEach { status ->
            classroomResponse = { html(standardClassroomHtml(), status) }
            assertMetadata(matchingLegacy(EASToken.Campus.BENBU), DataState.STATE.FETCH_FAILED, "HTTP $status")
            assertEquals(listOf("query:|12|"), requestTrace())
        }
        classroomResponse = { badGzip() }
        val failed = matchingLegacy(EASToken.Campus.BENBU)
        assertTrue(!failed.message.isNullOrBlank())
        assertMetadata(failed, DataState.STATE.FETCH_FAILED, failed.message)
        assertEquals(listOf("query:|12|"), requestTrace())
    }

    @Test
    fun weihaiOnceMatchesRemoteCodeNamePriorityAndRoomOrderWithoutCookieUpdates() = runBlocking {
        probeResponse = {
            json("""[{"DM":" 0001 ","MC":"编号匹配"},{"DM":"7","MC":" 教学楼 "},{"DM":"7","MC":"教学楼"},{"DM":"8","MC":"无关"}]""")
                .addHeader("Set-Cookie", "JSESSIONID=ignored-replacement; Path=/")
        }
        classroomResponse = { request ->
            val fields = form(request)
            val response = if (fields["pageLhdm"] == "01" && fields["pageCddm"] == "") html(standardClassroomHtml()) else html("")
            response.addHeader("Set-Cookie", "HIT=ignored-replacement; Path=/")
        }

        val state = matchingLegacy(EASToken.Campus.WEIHAI, buildingId = "001", weeks = listOf(" 3 ", "ignored", "8"))

        assertClassrooms(state, ExpectedRoom())
        assertEquals(
            listOf(
                "probe:01", "query:01|0001|，", "query:01|0001|", "query:01|7|，", "query:01|7|",
                "query:01|001|，", "query:01|001|", "query:01|1|，", "query:01|1|", "query:01|01|，", "query:01|01|",
            ),
            requestTrace(),
        )
        assertProbe(apiRequests.first(), "01")
        apiRequests.drop(1).forEach { request ->
            val fields = form(request)
            assertClassroomRequest(
                request, weihai = true, campus = "01",
                buildingId = requireNotNull(fields["pageLhdm"]), room = requireNotNull(fields["pageCddm"]),
                firstWeek = " 3 ", lastWeek = "8",
            )
        }
    }

    @Test
    fun weihaiOnceMatchesZeroAndWhitespaceBuildingCandidateQuirks() = runBlocking {
        probeResponse = { json("[]") }
        classroomResponse = { request -> if (form(request)["pageLhdm"] == "00") html(standardClassroomHtml()) else html("") }
        assertClassrooms(matchingLegacy(EASToken.Campus.WEIHAI, buildingId = "000"), ExpectedRoom())
        assertEquals(
            listOf("probe:01", "query:01|000|，", "query:01|000|", "query:01||，", "query:01||", "query:01|00|，"),
            requestTrace(),
        )

        classroomResponse = { request -> if (form(request)["pageLhdm"] == "") html(standardClassroomHtml()) else html("") }
        assertClassrooms(matchingLegacy(EASToken.Campus.WEIHAI, buildingId = " 001 "), ExpectedRoom())
        assertEquals(
            listOf("probe:01", "query:01|001|，", "query:01|001|", "query:01| 001 |，", "query:01| 001 |", "query:01||，"),
            requestTrace(),
        )
    }

    @Test
    fun weihaiOnceMatchesProbePartialJsonAndIgnoredHttpAuthParseTransportFailures() = runBlocking {
        probeResponse = { json("""[{"DM":"77","MC":"教学楼"},7,{"DM":"88","MC":"教学楼"}]""") }
        classroomResponse = { html(standardClassroomHtml()) }
        assertClassrooms(matchingLegacy(EASToken.Campus.WEIHAI), ExpectedRoom())
        assertEquals(listOf("probe:01", "query:01|77|，"), requestTrace())

        val failures: List<() -> MockResponse> = listOf(
            { json("[]", 401) }, { html("<form action=login><input name=password></form>") },
            { json("invalid-json") }, { badGzip() },
        )
        failures.forEach { response ->
            probeResponse = { response() }
            assertClassrooms(matchingLegacy(EASToken.Campus.WEIHAI), ExpectedRoom())
            assertEquals(listOf("probe:01", "query:01|12|，"), requestTrace())
        }
    }

    @Test
    fun weihaiOnceMatchesMainAuthenticationAndStopsAfterEarlierEmptyCandidate() = runBlocking {
        probeResponse = { json("[]") }
        val responses: List<() -> MockResponse> = listOf(
            { html("", 401) }, { html(standardClassroomHtml(), 403) },
            { html("<title>统一身份认证</title><input name=password>") },
            { html("<p>reAuth challenge</p>") }, { html("<title>Just a moment</title>") },
        )
        responses.forEach { response ->
            classroomResponse = { response() }
            assertMetadata(matchingLegacy(EASToken.Campus.WEIHAI), DataState.STATE.NOT_LOGGED_IN)
            assertEquals(listOf("probe:01", "query:01|12|，"), requestTrace())
        }
        classroomResponse = { if (mainRequestsCount() == 1) html("") else html("", 403) }
        assertMetadata(matchingLegacy(EASToken.Campus.WEIHAI), DataState.STATE.NOT_LOGGED_IN)
        assertEquals(listOf("probe:01", "query:01|12|，", "query:01|12|"), requestTrace())
    }

    @Test
    fun weihaiOnceKeepsReAuthSuccessAndOrdinaryNon200AsCandidatesBeforeFirstNonempty() = runBlocking {
        probeResponse = { json("[]") }
        val firstResponses: List<() -> MockResponse> = listOf(
            { json("""{"code":"reAuth_success"}""") }, { html(standardClassroomHtml(), 503) },
        )
        firstResponses.forEach { response ->
            classroomResponse = { if (mainRequestsCount() == 1) response() else html(standardClassroomHtml()) }
            assertClassrooms(matchingLegacy(EASToken.Campus.WEIHAI), ExpectedRoom())
            assertEquals(listOf("probe:01", "query:01|12|，", "query:01|12|"), requestTrace())
        }
    }

    @Test
    fun weihaiOnceMatchesFullExhaustionForAllNon200Earlier200AndAllEmptyHtml() = runBlocking {
        probeResponse = { json("[]") }
        classroomResponse = { html("unavailable", 503) }
        assertMetadata(matchingLegacy(EASToken.Campus.WEIHAI), DataState.STATE.FETCH_FAILED, "HTTP request failed")
        assertEquals(FULL_DEFAULT_FALLBACK, requestTrace())

        classroomResponse = { if (mainRequestsCount() == 1) html("") else html("unavailable", 503) }
        assertClassrooms(matchingLegacy(EASToken.Campus.WEIHAI))
        assertEquals(FULL_DEFAULT_FALLBACK, requestTrace())

        classroomResponse = { html("<table class=dataTable><tr><td>短行</td></tr></table>") }
        assertClassrooms(matchingLegacy(EASToken.Campus.WEIHAI))
        assertEquals(FULL_DEFAULT_FALLBACK, requestTrace())
    }

    @Test
    fun weihaiOnceMatchesMainTransportAbortAfterEarlierEmptySuccess() = runBlocking {
        probeResponse = { json("[]") }
        classroomResponse = { if (mainRequestsCount() == 1) html("") else badGzip() }

        val state = matchingLegacy(EASToken.Campus.WEIHAI)

        assertTrue(!state.message.isNullOrBlank())
        assertMetadata(state, DataState.STATE.FETCH_FAILED, state.message)
        assertEquals(listOf("probe:01", "query:01|12|，", "query:01|12|"), requestTrace())
    }

    @Test
    fun benbuCancellationKeepsMainResponsiveAndNeverReturnsSuccessHttpOrTransportFailure() {
        val responses: List<() -> MockResponse> = listOf(
            { html(standardClassroomHtml()) }, { html("unavailable", 503) }, { badGzip() },
        )
        responses.forEach { response ->
            cancelledRequest(EASToken.Campus.BENBU, listOf("query:|12|")) { entered, release ->
                classroomResponse = { hold(entered, release); response() }
            }
        }
    }

    @Test
    fun weihaiCancellationAtProbeCannotBecomeFallbackEvenForHttpAuthOrTransportFailure() {
        val responses: List<() -> MockResponse> = listOf(
            { json("[]") }, { json("[]", 401) }, { html("<input name=password>") }, { badGzip() },
        )
        responses.forEach { response ->
            cancelledRequest(EASToken.Campus.WEIHAI, listOf("probe:01")) { entered, release ->
                probeResponse = { hold(entered, release); response() }
                classroomResponse = { throw AssertionError("Cancelled probe must not start a classroom request") }
            }
        }
    }

    @Test
    fun weihaiCancellationAtMainNeverReturnsSuccessEmptyAuthHttpOrTransportResult() {
        val responses: List<() -> MockResponse> = listOf(
            { html(standardClassroomHtml()) }, { html("") }, { html("", 403) }, { html("unavailable", 503) }, { badGzip() },
        )
        responses.forEach { response ->
            cancelledRequest(EASToken.Campus.WEIHAI, listOf("probe:01", "query:01|12|，")) { entered, release ->
                probeResponse = { json("[]") }
                classroomResponse = { hold(entered, release); response() }
            }
        }
    }

    @Test
    fun weihaiCancellationAtSecondRoomStopsBeforeNextBuildingCandidate() {
        cancelledRequest(EASToken.Campus.WEIHAI, listOf("probe:01", "query:01|12|，", "query:01|12|")) { entered, release ->
            probeResponse = { json("[]") }
            classroomResponse = {
                if (mainRequestsCount() == 2) hold(entered, release)
                html("")
            }
        }
    }

    @Test
    fun weihaiCancellationAtLastRoomStopsBeforeNextCampusProbe() {
        cancelledRequest(EASToken.Campus.WEIHAI, FULL_DEFAULT_FALLBACK.take(5)) { entered, release ->
            probeResponse = { json("[]") }
            classroomResponse = {
                if (mainRequestsCount() == 4) hold(entered, release)
                html("")
            }
        }
    }

    @Test
    fun weihaiCancellationDuringSecondCampusProbeStopsBeforeItsMainRequest() {
        cancelledRequest(EASToken.Campus.WEIHAI, FULL_DEFAULT_FALLBACK.take(6)) { entered, release ->
            probeResponse = { request ->
                if (form(request)["id"] == "1") hold(entered, release)
                json("[]")
            }
            classroomResponse = { html("unavailable", 503) }
        }
    }

    @Test
    fun alreadyCancelledCoroutineActuallyCallsBothOnceEntriesWithoutStartingHttp() = runBlocking {
        listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI).forEach { campus ->
            val invoked = AtomicBoolean()
            val returned = AtomicReference<DataState<List<ClassroomItem>>>()
            val caught = AtomicReference<CancellationException>()
            val service = service(campus)
            val query = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException("already cancelled before entry"))
                invoked.set(true)
                try {
                    returned.set(service.queryEmptyClassroomOnce(legacyToken(campus), term, building(), emptyList()))
                } catch (error: CancellationException) {
                    caught.set(error)
                    throw error
                }
            }
            withTimeout(20_000) { query.join() }
            assertTrue("The test must actually reach the once call", invoked.get())
            assertNull(returned.get())
            assertNotNull(caught.get())
        }
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun weihaiOnceUsesCapturedTermBuildingNameAndWeeksAfterCallerMutatesThemAtHeldProbe() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val selectedTerm = TermItem("2026-2027", "原学年", "1", "原学期").apply {
            id = "original-id"; name = "原学期显示"; isCurrent = true
        }
        val selectedBuilding = building()
        val selectedWeeks = mutableListOf(" 2 ", "5", "8")
        val returned = AtomicReference<DataState<List<ClassroomItem>>>()
        probeResponse = {
            hold(entered, release)
            json("""[{"DM":"77","MC":"教学楼"}]""")
        }
        classroomResponse = { html(standardClassroomHtml()) }
        val service = weihaiClassroomSource()
        val query = launch(Dispatchers.Main) {
            returned.set(service.queryEmptyClassroomOnce(legacyToken(EASToken.Campus.WEIHAI), selectedTerm, selectedBuilding, selectedWeeks))
        }
        try {
            assertTrue("Probe did not arrive", entered.await(20, TimeUnit.SECONDS))
            selectedTerm.yearCode = "2030-2031"
            selectedTerm.yearName = "新学年"
            selectedTerm.termCode = "2"
            selectedTerm.termName = "新学期"
            selectedTerm.id = "new-id"
            selectedTerm.name = "新学期显示"
            selectedTerm.isCurrent = false
            selectedBuilding.id = "99"
            selectedBuilding.name = "其他楼"
            selectedWeeks.clear()
            selectedWeeks.add("20")
            release.countDown()
            withTimeout(20_000) { query.join() }
        } finally {
            query.cancel()
            release.countDown()
            withTimeout(20_000) { query.join() }
        }

        assertClassrooms(requireNotNull(returned.get()), ExpectedRoom())
        assertEquals(listOf("probe:01", "query:01|77|，"), requestTrace())
        assertEquals(
            mapOf(
                "pageXnxq" to "2026-20271", "pageZc1" to " 2 ", "pageZc2" to "8",
                "pageXiaoqu" to "01", "pageLhdm" to "77", "pageCddm" to "，",
            ),
            form(apiRequests.last()),
        )
    }

    private fun service(campus: EASToken.Campus): EASService = when (campus) {
        EASToken.Campus.BENBU -> benbuClassroomSource()
        EASToken.Campus.WEIHAI -> weihaiClassroomSource()
        else -> error("Only legacy campuses are tested here")
    }

    private fun mainRequestsCount() = apiRequests.count { it.path == WEIHAI_CLASSROOM_PATH }

    private fun badGzip() = html("invalid-gzip").setHeader("Content-Encoding", "gzip")

    private fun roomFields(item: ClassroomItem): List<Any?> = listOf(
        item.id, item.name, item.capacity, item.specialClassroom,
        item.scheduleList.map { entry ->
            assertEquals(setOf("XQJ", "XJ", "PKBJ"), entry.keys().asSequence().toSet())
            Triple(entry.getInt("XQJ"), entry.getInt("XJ"), entry.getString("PKBJ"))
        },
    )

    private fun requestFields(request: RecordedRequest): List<Any?> =
        listOf(request.method, request.path, form(request), request.headers.toMultimap())

    /** Independent equivalent sources and tokens avoid accidentally sharing mutable session state. */
    private suspend fun matchingLegacy(
        campus: EASToken.Campus,
        buildingId: String = "12",
        weeks: List<String> = emptyList(),
    ): DataState<List<ClassroomItem>> {
        apiRequests.clear()
        val legacySource = service(campus)
        val onceSource = service(campus)
        val legacySession = legacyToken(campus)
        val onceSession = legacyToken(campus)
        val expectedCookies = legacySession.cookies.toMap()
        val legacy = awaitState { legacySource.queryEmptyClassroom(legacySession, term, building(buildingId), weeks.toList()) }
        val legacyRequests = apiRequests.map(::requestFields)
        assertEquals(expectedCookies, legacySession.cookies)
        assertEquals(0, legacyCookieCallbacks.get())
        apiRequests.clear()
        val once = withContext(Dispatchers.Main) {
            onceSource.queryEmptyClassroomOnce(onceSession, term, building(buildingId), weeks.toList())
        }
        assertEquals(legacy.state, once.state)
        assertEquals(legacy.message, once.message)
        assertEquals(legacy.listAction, once.listAction)
        assertEquals(legacy.fromCache, once.fromCache)
        assertEquals(legacy.stateRetried, once.stateRetried)
        assertEquals(legacy.data?.map(::roomFields), once.data?.map(::roomFields))
        assertEquals(legacyRequests, apiRequests.map(::requestFields))
        assertEquals(expectedCookies, onceSession.cookies)
        assertEquals(legacySession.webCookies, onceSession.webCookies)
        assertEquals(0, legacyCookieCallbacks.get())
        assertTrue(webRequests.isEmpty())
        return once
    }

    private fun cancelledRequest(
        campus: EASToken.Campus,
        expectedTrace: List<String>,
        configure: (CountDownLatch, CountDownLatch) -> Unit,
    ): Unit = runBlocking {
        apiRequests.clear()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mainResponsive = CountDownLatch(1)
        val returned = AtomicReference<DataState<List<ClassroomItem>>>()
        val caught = AtomicReference<CancellationException>()
        configure(entered, release)
        val service = service(campus)
        val query = launch(Dispatchers.Main) {
            try {
                returned.set(service.queryEmptyClassroomOnce(legacyToken(campus), term, building(), emptyList()))
            } catch (error: CancellationException) {
                caught.set(error)
                throw error
            }
        }
        try {
            assertTrue("No held request arrived", entered.await(20, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { mainResponsive.countDown() }
            assertTrue("Classroom HTTP blocked Main", mainResponsive.await(20, TimeUnit.SECONDS))
            query.cancel(CancellationException("cancel held classroom request"))
        } finally {
            query.cancel()
            release.countDown()
            withTimeout(20_000) { query.join() }
        }
        assertNull("Cancellation must not deliver any DataState", returned.get())
        assertNotNull("Cancellation must reach the caller", caught.get())
        assertEquals("Cancellation must stop all later HTTP requests", expectedTrace, requestTrace())
        assertTrue(webRequests.isEmpty())
    }
}
