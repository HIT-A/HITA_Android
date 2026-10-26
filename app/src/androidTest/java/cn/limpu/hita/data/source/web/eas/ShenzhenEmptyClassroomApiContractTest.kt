package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.preference.EasCredential
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShenzhenEmptyClassroomApiContractTest : ShenzhenEmptyClassroomContractHarness() {
    @Test
    fun apiIsEagerExpandsSparseMatrixAndKeepsRoomOrderDuplicatesAndEveryScheduleField() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = apiToken().apply { webCookies["SESSION"] = "local-web-session" }
        apiResponse = { request ->
            when (request.path) {
                MATRIX_PATH -> {
                    hold(entered, release)
                    matrix().addHeader("Set-Cookie", "matrix=ready; Path=/")
                }
                DAILY_PATH -> when (form(request)["nyr"]) {
                    MATRIX_WEEK_DATES[0] -> json("""{"content":[
                        {"CDMC":" A101 ","DJ1":"有课","DJ6":"1","DJ2":"0","DJ3":" "},
                        7, {"CDMC":" "}, {"CDMC":"B202","DJ2":"借用"}, {"CDMC":"A101","DJ1":"重复"}
                    ]}""").addHeader("Set-Cookie", "daily=ready; Path=/")
                    MATRIX_WEEK_DATES[1] -> json("""{"content":[{"CDMC":"A101","DJ2":"否"}]}""")
                    else -> emptyDaily(request)
                }
                else -> error("Unexpected ${request.path}")
            }
        }

        val state = eagerResult(entered, release) { query(token, listOf(" 8 ", "20")) }

        assertRooms(state,
            Room("A101", schedule = listOf(Slot(1, 1), Slot(1, 2), Slot(1, 11), Slot(1, 12), Slot(1, 1), Slot(1, 2), Slot(2, 3), Slot(2, 4))),
            Room("B202", schedule = listOf(Slot(1, 3), Slot(1, 4))),
        )
        assertEquals(listOf(MATRIX_PATH) + List(7) { DAILY_PATH }, apiRequests.map { it.path })
        apiRequests.forEach { assertApiPost(it) }
        val body = jsonBody(apiRequests.first())
        assertEquals(setOf("xn", "xq", "zc", "type"), body.keys().asSequence().toSet())
        assertEquals(term.yearCode, body.getString("xn"))
        assertEquals(term.termCode, body.getString("xq"))
        assertEquals("8", body.getString("zc"))
        assertEquals("json", body.getString("type"))
        assertTrue(apiRequests.first().getHeader("Content-Type").orEmpty().startsWith("application/json"))
        apiRequests.drop(1).zip(MATRIX_WEEK_DATES).forEach { (request, date) ->
            assertEquals(mapOf("nyr" to date, "jxl" to " 01 "), form(request))
            assertTrue(request.getHeader("Cookie").orEmpty().contains("matrix=ready"))
        }
        apiRequests.drop(2).forEach { assertTrue(it.getHeader("Cookie").orEmpty().contains("daily=ready")) }
        assertEquals("ready", token.cookies["matrix"])
        assertEquals("ready", token.cookies["daily"])
        assertEquals(mapOf("SESSION" to "local-web-session"), token.webCookies)
        assertEquals(0, refreshCount.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiUsesOnlyFirstTrimmedWeekAndCookieOnlyTokensRemainOnApi() {
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> matrix()
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        val cases = listOf(emptyList<String>() to "1", listOf(" ") to "1", listOf(" 2 ", "9") to "2", listOf("-3") to "-3")
        cases.forEach { (weeks, expected) ->
            val token = apiToken().apply { accessToken = null }
            assertRooms(awaitState { query(token, weeks) })
            assertEquals(expected, jsonBody(apiRequests[apiRequests.size - 8]).getString("zc"))
            apiRequests.takeLast(8).forEach { assertApiPost(it, "bearer null") }
        }
        assertEquals(32, apiRequests.size)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiDailyMalformedAndExpiredBodiesAreEmptySuccessAndNon200ContentStillParses() {
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
            val state = awaitState { query() }
            if (index < 2) assertRooms(state) else assertRooms(state,
                Room("A101", schedule = (1..7).flatMap { day -> listOf(Slot(day, 1), Slot(day, 2)) }),
            )
        }
        assertEquals(24, apiRequests.size)
        assertEquals(0, refreshCount.get())
    }

    @Test
    fun apiDailyTransportFailureDiscardsEarlierDaysAndKeepsNullFailureMessage() {
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> matrix()
            DAILY_PATH -> if (form(request)["nyr"] == MATRIX_WEEK_DATES.first()) {
                json("""{"content":[{"CDMC":"A101","DJ1":"1"}]}""")
            } else badGzip()
            else -> error("Unexpected ${request.path}")
        } }

        assertFailure(awaitState { query() })

        assertEquals(listOf(MATRIX_PATH, DAILY_PATH, DAILY_PATH), apiRequests.map { it.path })
    }

    @Test
    fun apiNonemptyInvalidMatrixDatesFailWithoutOverviewFallback() {
        apiResponse = { request ->
            assertEquals(MATRIX_PATH, request.path)
            matrix("invalid-date")
        }

        assertFailure(awaitState { query() })

        assertEquals(listOf(MATRIX_PATH), apiRequests.map { it.path })
    }

    @Test
    fun apiMissingMalformedAndFailedMatrixResponsesFallBackToTermOverview() {
        val responses = listOf(
            { json("""{"content":[]}""") },
            { json("not-json") },
            { json("{}", 503) },
            { badGzip() },
        )
        responses.forEach { response ->
            apiRequests.clear()
            apiResponse = { request -> when (request.path) {
                MATRIX_PATH -> response()
                OVERVIEW_PATH -> overview()
                DAILY_PATH -> emptyDaily(request)
                else -> error("Unexpected ${request.path}")
            } }
            assertRooms(awaitState { query(querySource = classroomSource()) })
            assertEquals(listOf(MATRIX_PATH) + List(5) { OVERVIEW_PATH } + List(7) { DAILY_PATH }, apiRequests.map { it.path })
            assertEquals(OVERVIEW_WEEK_TWO_DATES, apiRequests.takeLast(7).map { form(it)["nyr"] })
        }
    }

    @Test
    fun apiOverviewUsesTermAnchorFiltersSortsAndCachesOnlyAfterFourStagnantScans() {
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}")
            OVERVIEW_PATH -> overview()
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        assertRooms(awaitState { query() })
        val firstRequests = apiRequests.toList()
        assertEquals(listOf(MATRIX_PATH) + List(5) { OVERVIEW_PATH } + List(7) { DAILY_PATH }, firstRequests.map { it.path })
        firstRequests.forEach { assertApiPost(it) }
        assertEquals((0L..4L).map { mapOf("nyr" to LocalDate.of(2026, 9, 1).plusWeeks(it).toString()) },
            firstRequests.filter { it.path == OVERVIEW_PATH }.map(::form))
        assertEquals(OVERVIEW_WEEK_TWO_DATES, firstRequests.takeLast(7).map { form(it)["nyr"] })

        apiRequests.clear()
        assertRooms(awaitState { query(weeks = listOf("1")) })
        assertEquals(listOf(MATRIX_PATH) + List(7) { DAILY_PATH }, apiRequests.map { it.path })
        assertEquals(MATRIX_WEEK_DATES, apiRequests.takeLast(7).map { form(it)["nyr"] })

        apiRequests.clear()
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> matrix("2026-10-07")
            DAILY_PATH -> emptyDaily(request)
            else -> error("Matrix success must not read overview: ${request.path}")
        } }
        assertRooms(awaitState { query() })
        assertEquals((5..11).map { "2026-10-${it.toString().padStart(2, '0')}" }, apiRequests.takeLast(7).map { form(it)["nyr"] })
    }

    @Test
    fun apiOverviewCacheKeyIsTermIdAndIsSharedAcrossTokensButNotSourceInstances() {
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}")
            OVERVIEW_PATH -> overview()
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        assertRooms(awaitState { query() })
        val alias = TermItem("2030-2031", "different year", "3", "summer").apply { id = term.id }
        apiRequests.clear()
        assertRooms(awaitState { query(apiToken().apply { username = "other-account" }, queryTerm = alias) })
        assertEquals(listOf(MATRIX_PATH) + List(7) { DAILY_PATH }, apiRequests.map { it.path })
        assertEquals(OVERVIEW_WEEK_TWO_DATES, apiRequests.takeLast(7).map { form(it)["nyr"] })

        apiRequests.clear()
        assertRooms(awaitState { query(querySource = classroomSource()) })
        assertEquals(5, apiRequests.count { it.path == OVERVIEW_PATH })

        apiRequests.clear()
        val differentId = TermItem(term.yearCode, term.yearName, term.termCode, term.termName).apply { id = "different-id" }
        assertRooms(awaitState { query(queryTerm = differentId) })
        assertEquals(5, apiRequests.count { it.path == OVERVIEW_PATH })
    }

    @Test
    fun apiEmptyOverviewScansAllTwentySixWeeksAndIsNotCached() {
        apiResponse = { request ->
            assertTrue(request.path == MATRIX_PATH || request.path == OVERVIEW_PATH)
            json("{}")
        }
        repeat(2) {
            apiRequests.clear()
            assertFailure(awaitState { query() })
            assertEquals(listOf(MATRIX_PATH) + List(26) { OVERVIEW_PATH }, apiRequests.map { it.path })
            assertEquals((0L..25L).map { LocalDate.of(2026, 9, 1).plusWeeks(it).toString() }, apiRequests.drop(1).map { form(it)["nyr"] })
        }
    }

    @Test
    fun apiNewOverviewDatesResetStagnationAndEarliestValidDateDefinesWeekOne() {
        val scan = AtomicInteger()
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}")
            OVERVIEW_PATH -> when (scan.getAndIncrement()) {
                0 -> overview()
                3 -> overview("2026-09-02")
                else -> json("{}")
            }
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }

        assertRooms(awaitState { query() })

        assertEquals(8, scan.get())
        assertEquals(MATRIX_WEEK_DATES, apiRequests.takeLast(7).map { form(it)["nyr"] })
    }

    @Test
    fun apiOverviewFailureDiscardsCollectedDatesAndDoesNotPopulateCache() {
        val scan = AtomicInteger()
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}")
            OVERVIEW_PATH -> if (scan.getAndIncrement() == 0) overview() else badGzip()
            else -> error("Unexpected ${request.path}")
        } }
        assertFailure(awaitState { query() })
        assertEquals(listOf(MATRIX_PATH, OVERVIEW_PATH, OVERVIEW_PATH), apiRequests.map { it.path })

        apiRequests.clear()
        apiResponse = { request -> when (request.path) {
            MATRIX_PATH -> json("{}")
            OVERVIEW_PATH -> overview()
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }
        assertRooms(awaitState { query() })
        assertEquals(5, apiRequests.count { it.path == OVERVIEW_PATH })
    }

    @Test
    fun apiLegacyMatrixCallbackCancellationFallsBackAfterLocalRelogin() {
        val callbackFailure = CancellationException("legacy classroom callback cancelled")
        val token = apiToken().apply { username = "local-user" }
        val callbackCount = AtomicInteger()
        val reloginSource = EASWebSource(
            onTokenRefreshed = {
                assertSame(token, it)
                callbackCount.incrementAndGet()
                throw callbackFailure
            },
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
            OVERVIEW_PATH -> overview()
            DAILY_PATH -> emptyDaily(request)
            else -> error("Unexpected ${request.path}")
        } }

        assertRooms(awaitState { query(token, querySource = reloginSource) })

        assertEquals(listOf(MATRIX_PATH, "/component/queryApplicationSetting/rsa", "/c_raskey", "/authentication/ldap") +
            List(5) { OVERVIEW_PATH } + List(7) { DAILY_PATH }, apiRequests.map { it.path })
        assertEquals(1, callbackCount.get())
        assertEquals("local-refreshed", token.accessToken)
        assertEquals("session", token.cookies["renewed"])
        assertFalse(token.cookies.containsKey("api-cookie"))
        apiRequests.drop(4).forEach { assertEquals("bearer local-refreshed", it.getHeader("authorization")) }
        assertTrue(webRequests.isEmpty())
    }
}
