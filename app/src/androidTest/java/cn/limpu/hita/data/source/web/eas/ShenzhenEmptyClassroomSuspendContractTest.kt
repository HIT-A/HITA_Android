package cn.limpu.hita.data.source.web.eas

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.preference.EasCredential
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Independent source instances prevent the legacy call from warming the once call's date cache. */
@RunWith(AndroidJUnit4::class)
class ShenzhenEmptyClassroomSuspendContractTest : ShenzhenEmptyClassroomContractHarness() {
    @Test
    fun apiOnceMatchesLegacySparseDatesRoomFieldsDuplicatesAndCookiePropagation() = runBlocking {
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> matrix().addHeader("Set-Cookie", "matrix=ready; Path=/")
            DAILY_PATH -> when (form(request)["nyr"]) {
                MATRIX_WEEK_DATES[0] -> json("""{"content":[
                    {"CDMC":" A101 ","DJ1":"有课","DJ6":"1","DJ2":"0","DJ3":" "},
                    7, {"CDMC":" "}, {"CDMC":"B202","DJ2":"借用"}, {"CDMC":"A101","DJ1":"重复"}
                ]}""").addHeader("Set-Cookie", "daily=ready; Path=/")
                MATRIX_WEEK_DATES[1] -> json("""{"content":[{"CDMC":"A101","DJ2":"否"}]}""")
                else -> emptyDaily(request)
            }
            else -> error("Unexpected ${request.path}")
        } }

        val state = matchingLegacy(weeks = listOf(" 8 ", "20")) {
            apiToken().apply { webCookies["SESSION"] = "local-web-session" }
        }

        assertRooms(state,
            Room("A101", schedule = listOf(Slot(1, 1), Slot(1, 2), Slot(1, 11), Slot(1, 12), Slot(1, 1), Slot(1, 2), Slot(2, 3), Slot(2, 4))),
            Room("B202", schedule = listOf(Slot(1, 3), Slot(1, 4))),
        )
        assertEquals(List(2) { listOf(MATRIX_PATH) + List(7) { DAILY_PATH } }.flatten(), apiRequests.map { it.path })
        apiRequests.forEach { assertApiPost(it) }
        assertEquals(listOf("8", "8"), apiRequests.filter { it.path == MATRIX_PATH }.map { jsonBody(it).getString("zc") })
        assertEquals(MATRIX_WEEK_DATES + MATRIX_WEEK_DATES, apiRequests.filter { it.path == DAILY_PATH }.map { form(it)["nyr"] })
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiOnceMatchesLegacyFirstWeekDefaultsAndCookieOnlyRouting() = runBlocking {
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> matrix()
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        listOf(emptyList<String>() to "1", listOf(" ") to "1", listOf(" 2 ", "9") to "2", listOf("-3") to "-3").forEach { (weeks, expected) ->
            assertRooms(matchingLegacy(weeks = weeks) { apiToken().apply { accessToken = null } })
            apiRequests.takeLast(16).forEach { assertApiPost(it, "bearer null") }
            assertEquals(listOf(expected, expected), apiRequests.takeLast(16).filter { it.path == MATRIX_PATH }.map { jsonBody(it).getString("zc") })
        }
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiOnceMatchesLegacyMatrixFallbacksAndInvalidDateFailure() = runBlocking {
        val responses = listOf(
            { json("""{"content":[]}""") }, { json("not-json") }, { json("{}", 503) }, { badGzip() },
        )
        responses.forEach { response ->
            apiRequests.clear()
            apiResponse = { request -> when (request.path) {
                MATRIX_PATH -> response()
                OVERVIEW_PATH -> overview()
                DAILY_PATH -> emptyDaily(request)
                else -> error("Unexpected ${request.path}")
            } }
            assertRooms(matchingLegacy(token = ::apiToken))
            val expected = listOf(MATRIX_PATH) + List(5) { OVERVIEW_PATH } + List(7) { DAILY_PATH }
            assertEquals(expected + expected, apiRequests.map { it.path })
            assertEquals(OVERVIEW_WEEK_TWO_DATES + OVERVIEW_WEEK_TWO_DATES, apiRequests.filter { it.path == DAILY_PATH }.map { form(it)["nyr"] })
        }
        apiRequests.clear()
        apiResponse = { request -> assertEquals(MATRIX_PATH, request.path); matrix("invalid-date") }
        assertFailure(matchingLegacy(token = ::apiToken))
        assertEquals(listOf(MATRIX_PATH, MATRIX_PATH), apiRequests.map { it.path })
    }

    @Test
    fun apiOnceMatchesLegacyDailyEmptyAuthHttpAndTransportFailureMapping() = runBlocking {
        val responses = listOf(
            { json("not-json") },
            { json("""{"code":401,"message":"unauthorized"}""", 401) },
            { json("""{"content":[{"CDMC":"A101","DJ1":"1"}]}""", 503) },
        )
        responses.forEachIndexed { index, response ->
            apiResponse = { request -> when (request.path) {
                MATRIX_PATH -> matrix()
                DAILY_PATH -> response()
                else -> error("Unexpected ${request.path}")
            } }
            val state = matchingLegacy(token = ::apiToken)
            if (index < 2) assertRooms(state) else assertRooms(state,
                Room("A101", schedule = (1..7).flatMap { listOf(Slot(it, 1), Slot(it, 2)) }),
            )
        }
        apiRequests.clear()
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> matrix()
            DAILY_PATH -> if (form(request)["nyr"] == MATRIX_WEEK_DATES.first()) {
                json("""{"content":[{"CDMC":"A101","DJ1":"1"}]}""")
            } else badGzip()
            else -> error("Unexpected ${request.path}")
        } }
        assertFailure(matchingLegacy(token = ::apiToken))
        assertEquals(List(2) { listOf(MATRIX_PATH, DAILY_PATH, DAILY_PATH) }.flatten(), apiRequests.map { it.path })
    }

    @Test
    fun apiOnceMatchesLegacyOverviewTwentySixWeekLimitAndNoEmptyCaching() = runBlocking {
        apiResponse = { request ->
            assertTrue(request.path == MATRIX_PATH || request.path == OVERVIEW_PATH)
            json("{}")
        }
        val legacySource = classroomSource()
        val onceSource = classroomSource()
        repeat(2) {
            apiRequests.clear()
            val legacy = awaitState { query(querySource = legacySource) }
            val once = withContext(Dispatchers.Main) { onceSource.queryEmptyClassroomOnce(apiToken(), term, building, listOf("2")) }
            assertEquivalent(legacy, once)
            assertFailure(once)
            val expected = listOf(MATRIX_PATH) + List(26) { OVERVIEW_PATH }
            assertEquals(expected + expected, apiRequests.map { it.path })
            assertEquals(apiRequests.take(27).map(::requestFields), apiRequests.drop(27).map(::requestFields))
        }
    }

    @Test
    fun apiOnceMatchesLegacyOverviewCacheIsolationKeyAndMatrixPriority() = runBlocking {
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}")
            OVERVIEW_PATH -> overview()
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        val legacySource = classroomSource()
        val onceSource = classroomSource()
        suspend fun compare(queryTerm: TermItem = term, expectedOverview: Int) {
            apiRequests.clear()
            val legacy = awaitState { query(querySource = legacySource, queryTerm = queryTerm) }
            val split = apiRequests.size
            val once = withContext(Dispatchers.Main) { onceSource.queryEmptyClassroomOnce(apiToken(), queryTerm, building, listOf("2")) }
            assertEquivalent(legacy, once)
            assertRooms(once)
            assertEquals(apiRequests.take(split).map(::requestFields), apiRequests.drop(split).map(::requestFields))
            assertEquals(expectedOverview * 2, apiRequests.count { it.path == OVERVIEW_PATH })
            assertEquals(2, apiRequests.count { it.path == MATRIX_PATH })
        }
        compare(expectedOverview = 5)
        compare(expectedOverview = 0)
        compare(TermItem("2030-2031", "other", "3", "summer").apply { id = term.id }, expectedOverview = 0)
        assertEquals(OVERVIEW_WEEK_TWO_DATES + OVERVIEW_WEEK_TWO_DATES, apiRequests.filter { it.path == DAILY_PATH }.map { form(it)["nyr"] })
        compare(TermItem(term.yearCode, term.yearName, term.termCode, term.termName).apply { id = "other-id" }, expectedOverview = 5)
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> matrix("2026-10-07")
            DAILY_PATH -> emptyDaily(request)
            else -> error("Matrix must bypass overview: ${request.path}")
        } }
        compare(expectedOverview = 0)
        assertEquals(List(2) { (5..11).map { "2026-10-${it.toString().padStart(2, '0')}" } }.flatten(),
            apiRequests.filter { it.path == DAILY_PATH }.map { form(it)["nyr"] })
    }

    @Test
    fun apiOnceMatchesLegacyResetStagnationAndFailedOverviewPrefix() = runBlocking {
        var scan = 0
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> { scan = 0; json("{}") }
            OVERVIEW_PATH -> when (scan++) { 0 -> overview(); 3 -> overview("2026-09-02"); else -> json("{}") }
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        assertRooms(matchingLegacy(token = ::apiToken))
        assertEquals(16, apiRequests.count { it.path == OVERVIEW_PATH })
        assertEquals(MATRIX_WEEK_DATES + MATRIX_WEEK_DATES, apiRequests.filter { it.path == DAILY_PATH }.map { form(it)["nyr"] })
        apiRequests.clear()
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> { scan = 0; json("{}") }
            OVERVIEW_PATH -> if (scan++ == 0) overview() else badGzip()
            else -> error("Unexpected ${request.path}")
        } }
        assertFailure(matchingLegacy(token = ::apiToken))
        assertEquals(List(2) { listOf(MATRIX_PATH, OVERVIEW_PATH, OVERVIEW_PATH) }.flatten(), apiRequests.map { it.path })
    }

    @Test
    fun webOnceMatchesLegacyRoomAliasesSortedDuplicatesAndCookieCallbacks() = runBlocking {
        webResponse = { request -> when (request.path) {
            LEFT_PATH -> json("""{"list":[
                {"DM":" B ","MC":" 教室乙 ","ZWS":"60.9","SFKJ":"1"},
                {"dm":"A","mc":"教室甲","zws":"bad","sfjtjs":"0"},
                {"DM":"B","MC":"乙重复"},{"DM":"C"},7,{"DM":" "}
            ]}""").addHeader("Set-Cookie", "left=ready; Path=/")
            RIGHT_PATH -> json("""{"content":{"list":[
                {"CDDM":"B","XQJ":"2","XJ":"3","PKBJ":"1"},
                {"cddm":"B","xqj":"1","xj":"2","jybj":"借用"},
                {"CDDM":"B","XQJ":"1","XJ":"2","PKJYBJ":"1"},
                {"CDDM":"A","XQJ":"1","XJ":"1","PKBJ":"0","JYBJ":"否","PKJYBJ":" "},
                {"CDDM":"A","XQJ":"3","XJ":"4","PKJYBJ":"占用"},
                {"CDDM":"C","XQJ":"bad","XJ":"1","PKBJ":"1"},
                {"CDDM":"missing","XQJ":"1","XJ":"1","PKBJ":"1"}
            ]}}""").addHeader("Set-Cookie", "right=ready; Path=/")
            else -> error("Unexpected ${request.path}")
        } }
        val state = matchingLegacy { webToken().apply { accessToken = " "; cookies["api-cookie"] = "local-api-cookie" } }
        val occupied = listOf(Slot(1, 2), Slot(1, 2), Slot(2, 3))
        assertRooms(state, Room("B", "教室乙", 60, "1", occupied), Room("A", "教室甲", 0, "0", listOf(Slot(3, 4))),
            Room("B", "乙重复", special = null, schedule = occupied), Room("C", special = null))
        assertEquals(listOf(LEFT_PATH, RIGHT_PATH, LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
        webRequests.forEach { assertWebPost(it, 2) }
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webOnceMatchesLegacyUntrimmedWeekMasksAndEmptyPayloads() = runBlocking {
        webResponse = { request ->
            assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
            json("[]")
        }
        listOf(emptyList<String>() to 1, listOf(" ") to 1, listOf(" 2 ") to 1, listOf("invalid") to 1,
            listOf("0") to 1, listOf("99") to 34, listOf("2", "9") to 2).forEach { (weeks, week) ->
            assertRooms(matchingLegacy(weeks = weeks, token = ::webToken))
            webRequests.takeLast(4).forEach { assertWebPost(it, week) }
        }
        listOf("{}", "{\"data\":[]}").forEach { body ->
            webResponse = { request ->
                assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
                json(body)
            }
            assertRooms(matchingLegacy(token = ::webToken))
        }
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webOnceMatchesLegacyAuthHttpParseAndTransportStopRules() = runBlocking {
        for (badPath in listOf(LEFT_PATH, RIGHT_PATH)) {
            for (kind in listOf("auth", "http", "json", "transport")) {
                webRequests.clear()
                webResponse = { request ->
                    assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
                    if (request.path != badPath) json("[]") else when (kind) {
                        "auth" -> json("{}", 401)
                        "http" -> json("[]", 503)
                        "json" -> json("{\"invalid\":")
                        else -> badGzip()
                    }
                }
                val state = matchingLegacy(token = ::webToken)
                when (kind) {
                    "auth" -> assertFailure(state, DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
                    "http", "json" -> assertFailure(state, message = "空教室数据解析失败")
                    else -> {
                        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
                        assertFalse(state.message.isNullOrBlank())
                        assertNull(state.data)
                        assertStateMetadata(state)
                    }
                }
                val expected = if (badPath == LEFT_PATH && kind in listOf("auth", "transport")) listOf(LEFT_PATH) else listOf(LEFT_PATH, RIGHT_PATH)
                assertEquals(expected + expected, webRequests.map { it.path })
            }
        }
    }

    @Test
    fun apiMatrixCancellationStopsBothOverviewFallbackAndDailyRequests() {
        for (responseKind in listOf("empty", "valid", "transport")) {
            apiRequests.clear()
            cancelHeld(expectedApi = listOf(MATRIX_PATH)) { entered, release ->
                apiResponse = { request ->
                    assertEquals(MATRIX_PATH, request.path)
                    hold(entered, release)
                    when (responseKind) { "valid" -> matrix(); "transport" -> badGzip(); else -> json("{}") }
                }
            }
        }
    }

    @Test
    fun apiOverviewCancellationStopsScanningAndDoesNotCacheCollectedDates() = runBlocking {
        for (heldScan in listOf(2, 5)) {
            apiRequests.clear()
            val sharedSource = classroomSource()
            var scans = 0
            cancelHeld(service = sharedSource, expectedApi = listOf(MATRIX_PATH) + List(heldScan) { OVERVIEW_PATH }) { entered, release ->
                apiResponse = { request -> when (request.path) {
                    MATRIX_PATH -> json("{}")
                    OVERVIEW_PATH -> {
                        if (++scans == heldScan) hold(entered, release)
                        overview()
                    }
                    else -> error("Unexpected ${request.path}")
                } }
            }
            apiRequests.clear()
            apiResponse = { request -> when (request.path) {
                MATRIX_PATH -> json("{}")
                OVERVIEW_PATH -> overview()
                DAILY_PATH -> emptyDaily(request)
                else -> error("Unexpected ${request.path}")
            } }
            assertRooms(withContext(Dispatchers.Main) { sharedSource.queryEmptyClassroomOnce(apiToken(), term, building, listOf("2")) })
            assertEquals(listOf(MATRIX_PATH) + List(5) { OVERVIEW_PATH } + List(7) { DAILY_PATH }, apiRequests.map { it.path })
        }
    }

    @Test
    fun apiDailyCancellationStopsNextDayWithoutDeliveringPartialResults() {
        cancelHeld(expectedApi = listOf(MATRIX_PATH, DAILY_PATH, DAILY_PATH)) { entered, release ->
            apiResponse = { request -> when (request.path) {
                MATRIX_PATH -> matrix()
                DAILY_PATH -> {
                    if (form(request)["nyr"] == MATRIX_WEEK_DATES[1]) hold(entered, release)
                    json("""{"content":[{"CDMC":"A101","DJ1":"1"}]}""")
                }
                else -> error("Unexpected ${request.path}")
            } }
        }
    }

    @Test
    fun webLeftCancellationStopsRightAndRightCancellationDoesNotDeliver() {
        for (heldPath in listOf(LEFT_PATH, RIGHT_PATH)) {
            webRequests.clear()
            cancelHeld(token = webToken(), expectedApi = emptyList(),
                expectedWeb = if (heldPath == LEFT_PATH) listOf(LEFT_PATH) else listOf(LEFT_PATH, RIGHT_PATH)) { entered, release ->
                webResponse = { request ->
                    assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
                    if (request.path == heldPath) hold(entered, release)
                    json("[]")
                }
            }
        }
    }

    @Test
    fun alreadyCancelledCallerActuallyEntersOnceAndIssuesNoApiOrWebRequest() = runBlocking {
        for (token in listOf(apiToken(), webToken())) {
            val reads = AtomicInteger()
            val weeks = object : AbstractList<String>() {
                override val size: Int = 1
                override fun get(index: Int): String { reads.incrementAndGet(); return "2" }
            }
            val caught = AtomicReference<CancellationException>()
            val returned = AtomicReference<DataState<List<ClassroomItem>>>()
            withContext(Dispatchers.Main) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel()
                    try {
                        returned.set(source.queryEmptyClassroomOnce(token, term, building, weeks))
                    } catch (error: CancellationException) {
                        caught.set(error)
                    }
                }.join()
            }
            assertTrue("Snapshot reads prove the once method was entered", reads.get() > 0)
            assertNotNull(caught.get())
            assertNull(returned.get())
        }
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun webCallbackCancellationPropagatesOriginalExceptionAndKeepsCompletedCookieSideEffect() = runBlocking {
        val sentinel = CancellationException("synthetic classroom callback cancellation")
        val token = webToken()
        val service = classroomSource { assertSame(token, it); throw sentinel }
        webResponse = { request ->
            assertEquals(LEFT_PATH, request.path)
            json("[]").addHeader("Set-Cookie", "left=ready; Path=/")
        }
        var caught: CancellationException? = null
        var returned: DataState<List<ClassroomItem>>? = null
        try {
            returned = withContext(Dispatchers.Main) { service.queryEmptyClassroomOnce(token, term, building, listOf("2")) }
        } catch (error: CancellationException) {
            caught = error
        }
        assertSame(sentinel, caught)
        assertNull(returned)
        assertEquals("ready", token.webCookies["left"])
        assertEquals(listOf(LEFT_PATH), webRequests.map { it.path })
    }

    @Test
    fun apiMatrixReloginCallbackCancellationPropagatesWithoutOverviewFallback() = runBlocking {
        val sentinel = CancellationException("synthetic matrix relogin callback cancellation")
        val token = apiToken().apply { username = "local-user" }
        val callbackCount = AtomicInteger()
        val service = EASWebSource(
            onTokenRefreshed = { assertSame(token, it); callbackCount.incrementAndGet(); throw sentinel },
            credentialProvider = { campus, username ->
                assertEquals(EASToken.Campus.SHENZHEN, campus)
                assertEquals("local-user", username)
                EasCredential(campus, "local-user", "local-password")
            },
            apiHostOverride = api.url("/").toString(),
            webHostOverride = web.url("/").toString(),
        )
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}", 401)
            "/component/queryApplicationSetting/rsa", "/c_raskey" -> json("{}")
            "/authentication/ldap" -> {
                assertEquals(mapOf("username" to "local-user", "password" to "local-password"), form(request))
                json("""{"access_token":"local-refreshed","refresh_token":"local-refresh"}""")
                    .addHeader("Set-Cookie", "renewed=session; Path=/")
            }
            else -> error("Cancelled matrix must not fall back: ${request.path}")
        } }
        var caught: CancellationException? = null
        var returned: DataState<List<ClassroomItem>>? = null
        try {
            returned = withContext(Dispatchers.Main) { service.queryEmptyClassroomOnce(token, term, building, listOf("2")) }
        } catch (error: CancellationException) {
            caught = error
        }
        assertSame(sentinel, caught)
        assertNull(returned)
        assertEquals(1, callbackCount.get())
        assertEquals("local-refreshed", token.accessToken)
        assertEquals("session", token.cookies["renewed"])
        assertEquals(listOf(MATRIX_PATH, "/component/queryApplicationSetting/rsa", "/c_raskey", "/authentication/ldap"), apiRequests.map { it.path })
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiOnceSnapshotsTermBuildingAndWeeksBeforeFollowingOverviewAndCacheWrites() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val originalTerm = TermItem(term.yearCode, term.yearName, term.termCode, term.termName).apply { id = term.id }
        val originalBuildingId = building.id
        val weeks = mutableListOf("2", "8")
        val token = apiToken()
        val originalOverview = """{"content":[{"XN":"${originalTerm.yearCode}","XQ":"${originalTerm.termCode}","RQ":"2026-09-09"}]}"""
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> { hold(entered, release); json("{}") }
            OVERVIEW_PATH -> json(originalOverview)
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        val returned = AtomicReference<DataState<List<ClassroomItem>>>()
        val job = launch(Dispatchers.Main) { returned.set(source.queryEmptyClassroomOnce(token, term, building, weeks)) }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            term.yearCode = "2035-2036"
            term.yearName = "changed year"
            term.termCode = "3"
            term.termName = "changed term"
            term.name = "changed name"
            term.id = "changed id"
            term.isCurrent = true
            building.id = "changed building"
            building.name = "changed building name"
            weeks[0] = "9"
            weeks.clear()
            token.accessToken = null
            token.webCookies["SESSION"] = "local-web-session"
        } finally {
            release.countDown()
            withTimeout(20_000) { job.join() }
        }
        assertRooms(requireNotNull(returned.get()))
        assertEquals(originalTerm.yearCode, jsonBody(apiRequests.first()).getString("xn"))
        assertEquals(originalTerm.termCode, jsonBody(apiRequests.first()).getString("xq"))
        assertEquals("2", jsonBody(apiRequests.first()).getString("zc"))
        assertEquals(5, apiRequests.count { it.path == OVERVIEW_PATH })
        assertEquals("2026-09-01", form(apiRequests[1])["nyr"])
        assertEquals(OVERVIEW_WEEK_TWO_DATES, apiRequests.takeLast(7).map { form(it)["nyr"] })
        apiRequests.takeLast(7).forEach {
            assertEquals(originalBuildingId, form(it)["jxl"])
            assertEquals("bearer null", it.getHeader("authorization"))
        }
        assertTrue("Changing token route hints must not switch the running query", webRequests.isEmpty())
        apiRequests.clear()
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}")
            DAILY_PATH -> emptyDaily(request)
            else -> error("Original term id should already be cached: ${request.path}")
        } }
        assertRooms(source.queryEmptyClassroomOnce(apiToken(), originalTerm, building, listOf("2")))
        assertEquals(listOf(MATRIX_PATH) + List(7) { DAILY_PATH }, apiRequests.map { it.path })
    }

    @Test
    fun webOnceKeepsOriginalFormAndRouteWhileTheCallerMutatesInputs() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val weeks = mutableListOf("2", "8")
        val token = webToken()
        val originalForm = webForm(2)
        webResponse = { request -> when (request.path) {
            LEFT_PATH -> { hold(entered, release); json("[]").addHeader("Set-Cookie", "left=ready; Path=/") }
            RIGHT_PATH -> json("[]").addHeader("Set-Cookie", "right=ready; Path=/")
            else -> error("Unexpected ${request.path}")
        } }
        val returned = AtomicReference<DataState<List<ClassroomItem>>>()
        val job = launch(Dispatchers.Main) { returned.set(source.queryEmptyClassroomOnce(token, term, building, weeks)) }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            term.yearCode = "2035-2036"
            term.termCode = "3"
            building.id = "different-building"
            weeks[0] = "33"
            token.accessToken = "changed-api-token"
        } finally {
            release.countDown()
            withTimeout(20_000) { job.join() }
        }
        assertRooms(requireNotNull(returned.get()))
        assertEquals(listOf(LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
        assertEquals(originalForm + mapOf("pageNum" to "1", "pageSize" to "500"), form(webRequests.first()))
        assertEquals(originalForm, form(webRequests.last()))
        assertEquals("ready", token.webCookies["left"])
        assertEquals("ready", token.webCookies["right"])
        assertSame(token, refreshedToken.get())
        assertEquals(2, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    private suspend fun matchingLegacy(
        weeks: List<String> = listOf("2"),
        token: () -> EASToken,
    ): DataState<List<ClassroomItem>> {
        val oldToken = token()
        val onceToken = token()
        val oldCallbacks = AtomicInteger()
        val onceCallbacks = AtomicInteger()
        val legacySource = classroomSource { assertSame(oldToken, it); oldCallbacks.incrementAndGet() }
        val onceSource = classroomSource { assertSame(onceToken, it); onceCallbacks.incrementAndGet() }
        val apiStart = apiRequests.size
        val webStart = webRequests.size
        val legacy = awaitState { query(oldToken, weeks, querySource = legacySource) }
        val apiSplit = apiRequests.size
        val webSplit = webRequests.size
        val once = withContext(Dispatchers.Main) { onceSource.queryEmptyClassroomOnce(onceToken, term, building, weeks) }
        assertEquivalent(legacy, once)
        assertEquals(oldToken.cookies, onceToken.cookies)
        assertEquals(oldToken.webCookies, onceToken.webCookies)
        assertEquals(oldToken.accessToken, onceToken.accessToken)
        assertEquals(oldToken.refreshToken, onceToken.refreshToken)
        assertEquals(oldCallbacks.get(), onceCallbacks.get())
        assertEquals(apiRequests.subList(apiStart, apiSplit).map(::requestFields), apiRequests.drop(apiSplit).map(::requestFields))
        assertEquals(webRequests.subList(webStart, webSplit).map(::requestFields), webRequests.drop(webSplit).map(::requestFields))
        return once
    }

    private fun assertEquivalent(legacy: DataState<List<ClassroomItem>>, once: DataState<List<ClassroomItem>>) {
        assertEquals(legacy.state, once.state)
        assertEquals(legacy.message, once.message)
        assertEquals(legacy.data?.map(::roomFields), once.data?.map(::roomFields))
        assertEquals(legacy.listAction, once.listAction)
        assertEquals(legacy.fromCache, once.fromCache)
        assertEquals(legacy.stateRetried, once.stateRetried)
    }

    private fun requestFields(request: RecordedRequest) = listOf(
        request.method, request.path, request.body.clone().readUtf8(),
        request.getHeader("Authorization"), request.getHeader("RoleCode"), request.getHeader("_lang"),
        request.getHeader("Cookie"), request.getHeader("Referer"), request.getHeader("Origin"),
        request.getHeader("Content-Type"), request.getHeader("Accept"), request.getHeader("User-Agent"),
    )

    private fun cancelHeld(
        service: EASWebSource = classroomSource(),
        token: EASToken = apiToken(),
        expectedApi: List<String>,
        expectedWeb: List<String> = emptyList(),
        configure: (CountDownLatch, CountDownLatch) -> Unit,
    ) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responsive = CountDownLatch(1)
        val returned = AtomicReference<DataState<List<ClassroomItem>>>()
        val caught = AtomicReference<CancellationException>()
        configure(entered, release)
        val job = launch(Dispatchers.Main) {
            try {
                returned.set(service.queryEmptyClassroomOnce(token, term, building, listOf("2")))
            } catch (error: CancellationException) {
                caught.set(error)
                throw error
            }
        }
        try {
            assertTrue("No classroom request arrived", entered.await(20, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { responsive.countDown() }
            assertTrue("Classroom HTTP blocked Main", responsive.await(20, TimeUnit.SECONDS))
            job.cancel(CancellationException("cancel held classroom request"))
        } finally {
            job.cancel()
            release.countDown()
            withTimeout(20_000) { job.join() }
        }
        assertNotNull("Cancellation must propagate", caught.get())
        assertNull("Cancelled query must not deliver any DataState", returned.get())
        assertFalse(job.isActive)
        assertEquals(expectedApi, apiRequests.map { it.path })
        assertEquals(expectedWeb, webRequests.map { it.path })
    }
}
