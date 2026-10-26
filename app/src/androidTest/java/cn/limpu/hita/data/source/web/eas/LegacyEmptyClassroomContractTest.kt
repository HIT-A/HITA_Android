package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Baselines exercise only the original LiveData API, before sharing its request core. */
@RunWith(AndroidJUnit4::class)
class LegacyEmptyClassroomContractTest : LegacyEmptyClassroomContractHarness() {
    @Test
    fun benbuStartsWithoutObserversWithSynchronousNothingAndOnePostWithoutCookieUpdates() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = legacyToken(EASToken.Campus.BENBU)
        classroomResponse = {
            hold(entered, release)
            html(standardClassroomHtml()).addHeader("Set-Cookie", "JSESSIONID=ignored-replacement; Path=/")
        }

        val state = eagerClassroomResult(entered, release) {
            benbuClassroomSource().queryEmptyClassroom(token, term, building(" 001 "), listOf(" 3 ", "ignored", "8"))
        }

        assertClassrooms(state, ExpectedRoom())
        assertClassroomRequest(apiRequests.single(), weihai = false, buildingId = " 001 ", firstWeek = " 3 ", lastWeek = "8")
        assertEquals("local-classroom-session", token.cookies["JSESSIONID"])
        assertEquals(0, legacyCookieCallbacks.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun benbuWeekDefaultsAndRawFirstLastValuesRemainUnnormalized() {
        classroomResponse = { html(standardClassroomHtml()) }
        val scenarios = listOf(
            Triple(emptyList(), "1", "1"),
            Triple(listOf("4"), "4", "4"),
            Triple(listOf(" 2 ", "ignored", " 8 "), " 2 ", " 8 "),
            Triple(listOf("", "9"), "", "9"),
        )
        val service = benbuClassroomSource()

        scenarios.forEachIndexed { index, (weeks, first, last) ->
            assertClassrooms(
                awaitState { service.queryEmptyClassroom(legacyToken(EASToken.Campus.BENBU), term, building(), weeks) },
                ExpectedRoom(),
            )
            assertClassroomRequest(apiRequests[index], weihai = false, firstWeek = first, lastWeek = last)
        }

        assertEquals(scenarios.size, apiRequests.size)
    }

    @Test
    fun benbuParserSelectsWidestTableAndPreservesDuplicateRowsAndAll84ScheduleEntries() {
        val narrow = classroomHtml(roomRow("错误表", metadata = emptyList()))
        val wide = classroomHtml(
            "<tr><th>教室</th><th>容量</th></tr>",
            roomRow("  A101  ", metadata = listOf("普通教室", "60 人")),
            roomRow("B202", metadata = emptyList(), occupiedBlocks = setOf(5), cellTag = "th"),
            roomRow("A101", metadata = listOf("40 座"), occupiedBlocks = emptySet()),
            roomRow("&nbsp;", metadata = listOf("80人", "另列")),
            "<tr><td>不完整行</td><td>80人</td></tr>",
        )
        classroomResponse = { html(narrow + wide) }

        val state = awaitState {
            benbuClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.BENBU), term, building(), emptyList())
        }

        assertClassrooms(
            state,
            ExpectedRoom(name = "A101"),
            ExpectedRoom(name = "B202", capacity = 0, occupied = setOf(1 to 11, 1 to 12)),
            ExpectedRoom(name = "A101", capacity = 40, occupied = emptySet()),
        )
        assertEquals(1, apiRequests.size)
    }

    @Test
    fun benbuEmptyMalformedAndLoginHtmlAreSuccessfulEmptyWithoutAuthMapping() {
        val bodies = listOf(
            "", "<html><body>没有课表</body></html>", "<table class=dataTable><tr><td>短行</td></tr></table>",
            "<title>统一身份认证</title><form action=login><input name=password></form>",
        )
        val service = benbuClassroomSource()
        bodies.forEach { body ->
            classroomResponse = { html(body) }
            assertClassrooms(awaitState {
                service.queryEmptyClassroom(legacyToken(EASToken.Campus.BENBU), term, building(), emptyList())
            })
        }

        assertEquals(bodies.size, apiRequests.size)
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun benbuEveryNon200IncludingAuthStatusReturnsHttpFailure() {
        val service = benbuClassroomSource()
        val statuses = listOf(401, 403, 503)
        statuses.forEach { status ->
            classroomResponse = { html(standardClassroomHtml(), status) }
            val state = awaitState {
                service.queryEmptyClassroom(legacyToken(EASToken.Campus.BENBU), term, building(), emptyList())
            }
            assertMetadata(state, DataState.STATE.FETCH_FAILED, "HTTP $status")
        }

        assertEquals(statuses.size, apiRequests.size)
    }

    @Test
    fun benbuTransportDecodeFailureReturnsMessageAndDoesNotRetry() {
        classroomResponse = { html("invalid-gzip").setHeader("Content-Encoding", "gzip") }

        val state = awaitState {
            benbuClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.BENBU), term, building(), emptyList())
        }

        assertTrue(!state.message.isNullOrBlank())
        assertMetadata(state, DataState.STATE.FETCH_FAILED, state.message)
        assertEquals(listOf("query:|12|"), requestTrace())
    }

    @Test
    fun weihaiStartsWithNothingAndProbeBeforeMainWithoutUpdatingEitherResponseCookie() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = legacyToken(EASToken.Campus.WEIHAI)
        probeResponse = {
            hold(entered, release)
            json("[]").addHeader("Set-Cookie", "JSESSIONID=ignored-replacement; Path=/")
        }
        classroomResponse = {
            html(standardClassroomHtml()).addHeader("Set-Cookie", "HIT=ignored-replacement; Path=/")
        }

        val state = eagerClassroomResult(entered, release) {
            weihaiClassroomSource().queryEmptyClassroom(token, term, building(), emptyList())
        }

        assertClassrooms(state, ExpectedRoom())
        assertEquals(listOf("probe:01", "query:01|12|，"), requestTrace())
        assertProbe(apiRequests[0], "01")
        assertClassroomRequest(apiRequests[1], weihai = true, campus = "01", room = "，")
        assertEquals("local-classroom-session", token.cookies["JSESSIONID"])
        assertEquals("local-classroom-hit", token.cookies["HIT"])
        assertEquals(0, legacyCookieCallbacks.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun weihaiUsesRawFirstAndLastWeekValuesAndOnlyDefaultsAnEmptyList() {
        probeResponse = { json("[]") }
        classroomResponse = { html(standardClassroomHtml()) }
        val scenarios = listOf(
            Triple(emptyList(), "1", "1"),
            Triple(listOf("4"), "4", "4"),
            Triple(listOf(" 2 ", "ignored", " 8 "), " 2 ", " 8 "),
            Triple(listOf("", "9"), "", "9"),
        )
        val service = weihaiClassroomSource()

        scenarios.forEachIndexed { index, (weeks, first, last) ->
            assertClassrooms(
                awaitState { service.queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), weeks) },
                ExpectedRoom(),
            )
            assertProbe(apiRequests[index * 2], "01")
            assertClassroomRequest(
                apiRequests[index * 2 + 1], weihai = true, campus = "01", room = "，", firstWeek = first, lastWeek = last,
            )
        }

        assertEquals(scenarios.size * 2, apiRequests.size)
    }

    @Test
    fun weihaiMatchesRemoteCodeOrNameThenPreservesCandidateAndRoomOrderUntilFirstNonempty() {
        probeResponse = {
            json("""[{"DM":" 0001 ","MC":"编号匹配"},{"DM":"7","MC":" 教学楼 "},{"DM":"7","MC":"教学楼"},{"DM":"8","MC":"无关"}]""")
        }
        classroomResponse = { request ->
            val fields = form(request)
            if (fields["pageLhdm"] == "01" && fields["pageCddm"] == "") html(standardClassroomHtml()) else html("")
        }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building("001"), emptyList())
        }, ExpectedRoom())

        assertEquals(
            listOf(
                "probe:01", "query:01|0001|，", "query:01|0001|", "query:01|7|，", "query:01|7|",
                "query:01|001|，", "query:01|001|", "query:01|1|，", "query:01|1|", "query:01|01|，", "query:01|01|",
            ),
            requestTrace(),
        )
    }

    @Test
    fun weihaiZeroIdTriesBlankCandidateBeforePaddedZero() {
        probeResponse = { json("[]") }
        classroomResponse = { request ->
            if (form(request)["pageLhdm"] == "00") html(standardClassroomHtml()) else html("")
        }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building("000"), emptyList())
        }, ExpectedRoom())

        assertEquals(
            listOf("probe:01", "query:01|000|，", "query:01|000|", "query:01||，", "query:01||", "query:01|00|，"),
            requestTrace(),
        )
    }

    @Test
    fun weihaiZeroRemovalKeepsOriginalWhitespaceBranchDistinctFromTrimmedId() {
        probeResponse = { json("[]") }
        classroomResponse = { request ->
            if (form(request)["pageLhdm"] == "") html(standardClassroomHtml()) else html("")
        }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(" 001 "), emptyList())
        }, ExpectedRoom())

        assertEquals(
            listOf("probe:01", "query:01|001|，", "query:01|001|", "query:01| 001 |，", "query:01| 001 |", "query:01||，"),
            requestTrace(),
        )
    }

    @Test
    fun weihaiProbeParserKeepsValidPrefixBeforeMalformedRowAndDoesNotReachLaterMatch() {
        probeResponse = {
            json("""[{"DM":"77","MC":"教学楼"},7,{"DM":"88","MC":"教学楼"}]""")
        }
        classroomResponse = { html(standardClassroomHtml()) }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        }, ExpectedRoom())

        assertEquals(listOf("probe:01", "query:01|77|，"), requestTrace())
    }

    @Test
    fun weihaiProbeNon200AuthHtmlMalformedJsonAndTransportFailureAllContinueToMain() {
        val responses: List<() -> MockResponse> = listOf(
            { json("[]", 401) },
            { html("<title>登录</title><form action=login><input name=password></form>") },
            { json("invalid-json") },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        val service = weihaiClassroomSource()
        classroomResponse = { html(standardClassroomHtml()) }
        responses.forEach { response ->
            probeResponse = { response() }
            assertClassrooms(awaitState {
                service.queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
            }, ExpectedRoom())
        }

        assertEquals(List(responses.size) { listOf("probe:01", "query:01|12|，") }.flatten(), requestTrace())
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun weihaiMainAuthResponsesStopWithoutTryingSecondRoomOrNextCampus() {
        val responses: List<() -> MockResponse> = listOf(
            { html("", 401) },
            { html(standardClassroomHtml(), 403) },
            { html("<title>统一身份认证</title><form action=login><input name=password></form>") },
            { html("<html><body>reAuth challenge</body></html>") },
            { html("<title>Just a moment</title><p>Please enable javascript</p>") },
            { html("<form action=login><input name=password></form>", 503) },
        )
        probeResponse = { json("[]") }
        val service = weihaiClassroomSource()
        responses.forEach { response ->
            classroomResponse = { response() }
            val state = awaitState {
                service.queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
            }
            assertMetadata(state, DataState.STATE.NOT_LOGGED_IN)
        }

        assertEquals(List(responses.size) { listOf("probe:01", "query:01|12|，") }.flatten(), requestTrace())
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun weihaiLaterAuthStopsEvenAfterAnEarlierSuccessfulEmptyResponse() {
        val calls = AtomicInteger()
        probeResponse = { json("[]") }
        classroomResponse = { if (calls.incrementAndGet() == 1) html("") else html("", 403) }

        val state = awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        }

        assertMetadata(state, DataState.STATE.NOT_LOGGED_IN)
        assertEquals(listOf("probe:01", "query:01|12|，", "query:01|12|"), requestTrace())
    }

    @Test
    fun weihaiReAuthSuccessMarkerRemainsAnEmptyCandidateAndAllowsNextRoom() {
        val calls = AtomicInteger()
        probeResponse = { json("[]") }
        classroomResponse = {
            if (calls.incrementAndGet() == 1) json("""{"code":"reAuth_success"}""") else html(standardClassroomHtml())
        }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        }, ExpectedRoom())

        assertEquals(listOf("probe:01", "query:01|12|，", "query:01|12|"), requestTrace())
    }

    @Test
    fun weihaiOrdinaryNon200AdvancesToNextRoomAndFirstNonemptyStopsAllFallbacks() {
        val calls = AtomicInteger()
        probeResponse = { json("[]") }
        classroomResponse = {
            if (calls.incrementAndGet() == 1) html(standardClassroomHtml(), 503) else html(standardClassroomHtml())
        }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        }, ExpectedRoom())

        assertEquals(listOf("probe:01", "query:01|12|，", "query:01|12|"), requestTrace())
    }

    @Test
    fun weihaiExhaustsCampusesBuildingsAndRoomsInOrderAndFailsIfEveryMainResponseIsNon200() {
        probeResponse = { json("[]") }
        classroomResponse = { html("unavailable", 503) }

        val state = awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        }

        assertMetadata(state, DataState.STATE.FETCH_FAILED, "HTTP request failed")
        assertEquals(FULL_DEFAULT_FALLBACK, requestTrace())
        apiRequests.forEach { request ->
            val fields = form(request)
            if (request.path == BUILDING_PROBE_PATH) {
                assertProbe(request, requireNotNull(fields["id"]))
            } else {
                assertClassroomRequest(
                    request, weihai = true,
                    campus = requireNotNull(fields["pageXiaoqu"]),
                    buildingId = requireNotNull(fields["pageLhdm"]),
                    room = requireNotNull(fields["pageCddm"]),
                )
            }
        }
    }

    @Test
    fun weihaiEarlier200EmptySurvivesLaterNon200WhenAllCandidatesAreExhausted() {
        val calls = AtomicInteger()
        probeResponse = { json("[]") }
        classroomResponse = { if (calls.incrementAndGet() == 1) html("") else html("unavailable", 503) }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        })

        assertEquals(FULL_DEFAULT_FALLBACK, requestTrace())
    }

    @Test
    fun weihaiEvery200WithoutValidClassroomRowsReturnsEmptySuccessAfterFullFallback() {
        probeResponse = { json("[]") }
        classroomResponse = { html("<table class=dataTable><tr><td>短行</td></tr></table>") }

        assertClassrooms(awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        })

        assertEquals(FULL_DEFAULT_FALLBACK, requestTrace())
    }

    @Test
    fun weihaiMainTransportFailureAbortsEvenAfterAnEarlier200EmptyCandidate() {
        val calls = AtomicInteger()
        probeResponse = { json("[]") }
        classroomResponse = {
            if (calls.incrementAndGet() == 1) html("") else html("invalid-gzip").setHeader("Content-Encoding", "gzip")
        }

        val state = awaitState {
            weihaiClassroomSource().queryEmptyClassroom(legacyToken(EASToken.Campus.WEIHAI), term, building(), emptyList())
        }

        assertTrue(!state.message.isNullOrBlank())
        assertMetadata(state, DataState.STATE.FETCH_FAILED, state.message)
        assertEquals(listOf("probe:01", "query:01|12|，", "query:01|12|"), requestTrace())
    }
}
