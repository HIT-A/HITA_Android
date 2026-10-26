package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Each inherited contract executes once against each actual campus source. */
abstract class LegacyTermQueryContractCases : TermQueryContractHarness() {
    protected abstract val campus: EASToken.Campus

    @Test
    fun bothPagesMergeDeduplicateAndSortWithScoreLabelsTakingPriority() {
        apiResponse = { request -> when (request.path) {
            SCORE_TERMS -> html(termPage("pageXnxq", """
                <option value='2025-20263'>2025-2026夏季学期</option>
                <option value='2025-20262' selected>2025-2026春季学期</option>
            """))
            TIMETABLE_TERMS -> html(termPage("xnxq", """
                <option value='2025-20262' selected>课表别名</option>
                <option value='2024-20251'>2024-2025秋季学期</option>
            """))
            else -> error("Unexpected legacy path ${request.path}")
        } }
        val terms = success { legacySource(campus).getAllTerms(legacyToken(campus)) }
        assertEquals(listOf("2024-2025-1", "2025-2026-2", "2025-2026-3"), terms.map { it.id })
        assertEquals(listOf(false, true, false), terms.map { it.isCurrent })
        assertEquals(listOf("2025-2026", "2025-2026", "2", "2025-2026 春季学期",
            "2025-2026 春季学期", "2025-2026-2", true), termFields(terms[1]))
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
        assertLegacyGet(apiRequests[0], SCORE_TERMS)
        assertLegacyGet(apiRequests[1], TIMETABLE_TERMS)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun differingSelectedTermsPreserveBothCurrentFlags() {
        apiResponse = { request -> when (request.path) {
            SCORE_TERMS -> html(singleTermPage("pageXnxq"))
            TIMETABLE_TERMS -> html(termPage("xnxq", "<option value='2025-20262' selected>2025-2026春季学期</option>"))
            else -> error("Unexpected legacy path ${request.path}")
        } }
        val terms = success { legacySource(campus).getAllTerms(legacyToken(campus)) }
        assertEquals(listOf("2025-2026-1", "2025-2026-2"), terms.map { it.id })
        assertEquals(listOf(true, true), terms.map { it.isCurrent })
    }

    @Test
    fun firstOptionSuppliesCurrentTermWhenNoOptionIsSelected() {
        apiResponse = { request -> html(termPage(selector(request.path), """
            <option value='2025-20262'>2025-2026春季学期</option>
            <option value='2025-20261'>2025-2026秋季学期</option>
        """)) }
        val terms = success { legacySource(campus).getAllTerms(legacyToken(campus)) }
        assertEquals(listOf("2025-2026-1", "2025-2026-2"), terms.map { it.id })
        assertEquals(listOf(false, true), terms.map { it.isCurrent })
    }

    @Test
    fun validTimetablePageSurvivesExpiredScorePage() {
        apiResponse = { request -> when (request.path) {
            SCORE_TERMS -> html("<input name='password'>", 401)
            TIMETABLE_TERMS -> html(singleTermPage("xnxq"))
            else -> error("Unexpected legacy path ${request.path}")
        } }
        assertEquals("2025-2026-1", success { legacySource(campus).getAllTerms(legacyToken(campus)) }.single().id)
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
    }

    @Test
    fun validScorePageSurvivesExpiredTimetablePage() {
        apiResponse = { request -> when (request.path) {
            SCORE_TERMS -> html(singleTermPage("pageXnxq"))
            TIMETABLE_TERMS -> html("<form action='/login'></form>", 403)
            else -> error("Unexpected legacy path ${request.path}")
        } }
        assertEquals("2025-2026-1", success { legacySource(campus).getAllTerms(legacyToken(campus)) }.single().id)
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
    }

    @Test
    fun authenticationErrorsOnBothPagesAreNotLoggedIn() {
        apiResponse = { request -> html("<input name='password'>", if (request.path == SCORE_TERMS) 401 else 403) }
        val state = awaitState { legacySource(campus).getAllTerms(legacyToken(campus)) }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
        assertNull(state.data)
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
    }

    @Test
    fun loginHtmlEvenWithTermSelectorsIsNotLoggedIn() {
        apiResponse = { request -> html(singleTermPage(selector(request.path)) +
            "<form action='/login'><input name='password'></form>") }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState {
            legacySource(campus).getAllTerms(legacyToken(campus))
        }.state)
        assertEquals(2, apiRequests.size)
    }

    @Test
    fun missingSelectorsRetainLegacyAuthenticationClassificationWithoutRetry() {
        apiResponse = { html("<html><title>临时业务页面</title><body>没有学期下拉框</body></html>") }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState {
            legacySource(campus).getAllTerms(legacyToken(campus))
        }.state)
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
    }

    @Test
    fun twoHttpServerErrorsRetainLegacyAuthenticationClassification() {
        apiResponse = { html("temporary unavailable", 503) }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState {
            legacySource(campus).getAllTerms(legacyToken(campus))
        }.state)
        assertEquals(2, apiRequests.size)
    }

    @Test
    fun selectorsContainingOnlyInvalidOptionsReturnFetchFailed() {
        apiResponse = { request -> html(termPage(selector(request.path), "<option value='invalid'>请选择</option>")) }
        val state = awaitState { legacySource(campus).getAllTerms(legacyToken(campus)) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("未获取到学期列表", state.message)
        assertNull(state.data)
        assertEquals(2, apiRequests.size)
    }

    @Test
    fun transportFailuresReturnFetchFailedInsteadOfAuthenticationError() {
        apiResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val state = awaitState { legacySource(campus).getAllTerms(legacyToken(campus)) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("未获取到学期列表", state.message)
        assertNull(state.data)
        assertEquals(setOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path }.toSet())
    }

    @Test
    fun responseCookiesAreMergedAndForwardedBeforeFetchingSecondPage() {
        apiResponse = { request -> when (request.path) {
            SCORE_TERMS -> html(singleTermPage("pageXnxq")).addHeader("Set-Cookie", "JSESSIONID=renewed-term; Path=/")
            TIMETABLE_TERMS -> html(singleTermPage("xnxq")).addHeader("Set-Cookie", "term-extra=second-page; Path=/")
            else -> error("Unexpected legacy path ${request.path}")
        } }
        val token = legacyToken(campus)
        success { legacySource(campus).getAllTerms(token) }
        assertEquals("renewed-term", token.cookies["JSESSIONID"])
        assertEquals("second-page", token.cookies["term-extra"])
        assertEquals("local-term-hit", token.cookies["HIT"])
        assertEquals(2, legacyCookieCallbacks.get())
        assertSame(token, legacyRefreshedToken.get())
        assertTrue(apiRequests.first().getHeader("Cookie").orEmpty().contains("JSESSIONID=local-term-session"))
        assertTrue(apiRequests.last().getHeader("Cookie").orEmpty().contains("JSESSIONID=renewed-term"))
    }

    @Test
    fun invalidFirstPageStillUpdatesCookiesForTheValidSecondPage() {
        apiResponse = { request -> when (request.path) {
            SCORE_TERMS -> html("missing selector").addHeader("Set-Cookie", "JSESSIONID=invalid-page-cookie; Path=/")
            TIMETABLE_TERMS -> html(singleTermPage("xnxq"))
            else -> error("Unexpected legacy path ${request.path}")
        } }
        val token = legacyToken(campus)
        assertEquals(1, success { legacySource(campus).getAllTerms(token) }.size)
        assertEquals("invalid-page-cookie", token.cookies["JSESSIONID"])
        assertEquals(1, legacyCookieCallbacks.get())
        assertTrue(apiRequests.last().getHeader("Cookie").orEmpty().contains("JSESSIONID=invalid-page-cookie"))
    }

    @Test
    fun queryStartsWithoutObserversAndExposesNothingBeforeCompletion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        apiResponse = { request ->
            if (request.path == SCORE_TERMS) hold(entered, release)
            html(singleTermPage(selector(request.path)))
        }
        assertEquals(DataState.STATE.SUCCESS, eagerTerms(DataState.STATE.NOTHING, entered, release) {
            legacySource(campus).getAllTerms(legacyToken(campus))
        }.state)
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
    }

    @Test
    fun emptyTokenDoesNotShortCircuitTheExistingRequests() {
        apiResponse = { request -> html(singleTermPage(selector(request.path))) }
        assertEquals(1, success { legacySource(campus).getAllTerms(EASToken().apply { this.campus = this@LegacyTermQueryContractCases.campus }) }.size)
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
        assertTrue(apiRequests.all { it.getHeader("Cookie").isNullOrEmpty() })
    }

    @Test
    fun campusPostprocessingPreservesOpenedSummerAndOnlyWeihaiHidesFutureAcademicYears() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val currentYear = "$year-${year + 1}"
        val futureYear = "${year + 5}-${year + 6}"
        apiResponse = { request -> html(termPage(selector(request.path), """
            <option value='${currentYear}1' selected>${currentYear}秋季学期</option>
            <option value='${currentYear}3'>${currentYear}夏季学期</option>
            <option value='${futureYear}1'>${futureYear}秋季学期</option>
        """)) }
        val terms = success { legacySource(campus).getAllTerms(legacyToken(campus)) }
        val expected = listOf("$currentYear-1", "$currentYear-3") +
            if (campus == EASToken.Campus.BENBU) listOf("$futureYear-1") else emptyList()
        assertEquals(expected, terms.map { it.id })
        assertEquals("$currentYear-1", terms.single { it.isCurrent }.id)
        assertFalse(terms.single { it.termCode == "3" }.isCurrent)
    }

    private fun selector(path: String?): String = when (path) {
        SCORE_TERMS -> "pageXnxq"
        TIMETABLE_TERMS -> "xnxq"
        else -> error("Unexpected legacy path $path")
    }
}

@RunWith(AndroidJUnit4::class)
class BenbuTermQueryContractTest : LegacyTermQueryContractCases() {
    override val campus = EASToken.Campus.BENBU
}

@RunWith(AndroidJUnit4::class)
class WeihaiTermQueryContractTest : LegacyTermQueryContractCases() {
    override val campus = EASToken.Campus.WEIHAI
}
