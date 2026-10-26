package cn.limpu.hita.data.source.web.eas

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Cancellation stops later term business requests; already executing sockets/auth helpers are synchronous. */
@RunWith(AndroidJUnit4::class)
class TermQueryCancellationContractTest : TermQueryOnceContractHarness() {
    @Test fun alreadyCancelledParentPreventsShenzhenApiHttp() = alreadyCancelled(apiToken())
    @Test fun alreadyCancelledParentPreventsShenzhenWebHttp() = alreadyCancelled(webToken())
    @Test fun alreadyCancelledParentPreventsBenbuUndergraduateHttp() = alreadyCancelled(legacyToken(EASToken.Campus.BENBU))
    @Test fun alreadyCancelledParentPreventsWeihaiHttp() = alreadyCancelled(legacyToken(EASToken.Campus.WEIHAI))
    @Test fun alreadyCancelledParentPreventsBenbuGraduateHttp() = alreadyCancelled(graduateTermToken())

    @Test
    fun parentCancellationDuringApiRequestReturnsNoStateOrWebFallback() {
        cancelHeld(apiToken(), expectedApi = listOf(API_TERMS)) { entered, release ->
            apiResponse = { hold(entered, release); json("""{"content":[]}""") }
        }
    }

    @Test
    fun parentCancellationDuringWebPrimaryStopsFallbackAndCurrentRequests() {
        cancelHeld(webToken(), expectedWeb = listOf(WEB_TERMS)) { entered, release ->
            webResponse = { hold(entered, release); json("{}") }
        }
    }

    @Test
    fun parentCancellationDuringWebFallbackStopsCurrentRequest() {
        cancelHeld(webToken(), expectedWeb = listOf(WEB_TERMS, WEB_FALLBACK)) { entered, release ->
            webResponse = { request -> when (request.path) {
                WEB_TERMS -> json("{}")
                WEB_FALLBACK -> { hold(entered, release); json(WEB_LIST) }
                else -> error("Unexpected path ${request.path}")
            } }
        }
    }

    @Test
    fun parentCancellationDuringWebCurrentReturnsNoPartialDropdown() {
        cancelHeld(webToken(), expectedWeb = listOf(WEB_TERMS, WEB_CURRENT)) { entered, release ->
            webResponse = { request -> when (request.path) {
                WEB_TERMS -> json(WEB_LIST)
                WEB_CURRENT -> { hold(entered, release); json("{}") }
                else -> error("Unexpected path ${request.path}")
            } }
        }
    }

    @Test
    fun directWebOnceAlsoUsesTheCallerParentCancellation() {
        cancelHeld(webToken(), webOnly = true, expectedWeb = listOf(WEB_TERMS)) { entered, release ->
            webResponse = { hold(entered, release); json(WEB_LIST) }
        }
    }

    @Test fun benbuParentCancellationDuringScorePageStopsTimetablePage() = cancelRegular(EASToken.Campus.BENBU, SCORE_TERMS)
    @Test fun weihaiParentCancellationDuringScorePageStopsTimetablePage() = cancelRegular(EASToken.Campus.WEIHAI, SCORE_TERMS)
    @Test fun benbuParentCancellationDuringTimetablePageReturnsNoPartialTerms() = cancelRegular(EASToken.Campus.BENBU, TIMETABLE_TERMS)
    @Test fun weihaiParentCancellationDuringTimetablePageReturnsNoPartialTerms() = cancelRegular(EASToken.Campus.WEIHAI, TIMETABLE_TERMS)

    @Test
    fun graduateParentCancellationDuringSemesterStopsTheHtmlBridge() {
        cancelHeld(graduateTermToken(), expectedApi = listOf(GRADUATE_TERMS)) { entered, release ->
            apiResponse = { hold(entered, release); json("[]") }
        }
    }

    @Test
    fun graduateParentCancellationDuringBridgeReturnsNoHtmlResult() {
        cancelHeld(graduateTermToken(), expectedApi = listOf(GRADUATE_TERMS, GRADUATE_BRIDGE)) { entered, release ->
            apiResponse = { request -> when (request.path) {
                GRADUATE_TERMS -> json("[]")
                GRADUATE_BRIDGE -> { hold(entered, release); html(singleTermPage("xnxq")) }
                else -> error("Unexpected path ${request.path}")
            } }
        }
    }

    @Test
    fun shenzhenCallbackCancellationPropagatesTheSameExceptionAfterCookieUpdate() = runBlocking {
        val sentinel = CancellationException("synthetic term callback cancellation")
        val token = webToken().apply { sessionGeneration = 83L }
        val callbackToken = AtomicReference<EASToken>()
        val service = shenzhenTermService { callbackToken.set(it); throw sentinel }
        webResponse = { json(WEB_LIST).addHeader("Set-Cookie", "SESSION=completed-before-cancel; Path=/") }
        assertCallbackCancellation(service, token, sentinel)
        assertSame(token, callbackToken.get())
        assertEquals(83L, token.sessionGeneration)
        assertEquals("completed-before-cancel", token.webCookies["SESSION"])
        assertEquals(listOf(WEB_TERMS), webRequests.map { it.path })
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun legacyShenzhenCallbackCancellationRetainsHistoricalFailureState() {
        val sentinel = CancellationException("legacy term callback cancellation")
        val service = shenzhenTermService { throw sentinel }
        webResponse = { json(WEB_LIST) }
        val state = awaitState { service.getAllTerms(webToken()) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals(sentinel.message, state.message)
        assertNull(state.data)
        assertEquals(listOf(WEB_TERMS), webRequests.map { it.path })
    }

    @Test fun benbuCallbackCancellationEscapesBeforeSecondPage() = regularCallbackCancellation(EASToken.Campus.BENBU)
    @Test fun weihaiCallbackCancellationEscapesBeforeSecondPage() = regularCallbackCancellation(EASToken.Campus.WEIHAI)
    @Test fun legacyBenbuCallbackCancellationStillAllowsTheSecondPage() = regularLegacyCallbackCancellation(EASToken.Campus.BENBU)
    @Test fun legacyWeihaiCallbackCancellationStillAllowsTheSecondPage() = regularLegacyCallbackCancellation(EASToken.Campus.WEIHAI)

    @Test
    fun onceWebUndergraduateTypeIsFixedWhileThePrimaryRequestIsRunning() = capturedWebStudentType(
        EASToken.TYPE.UNDERGRAD, EASToken.TYPE.GRAD, "1",
    )

    @Test
    fun onceWebGraduateTypeIsFixedWhileThePrimaryRequestIsRunning() = capturedWebStudentType(
        EASToken.TYPE.GRAD, EASToken.TYPE.UNDERGRAD, "2",
    )

    @Test
    fun legacyWebStillReadsStudentTypeWhenTheAuxiliaryRequestBegins() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = webToken()
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> { hold(entered, release); json(WEB_LIST) }
            WEB_CURRENT -> json("{}")
            else -> error("Unexpected path ${request.path}")
        } }
        observe { shenzhenTermService().getAllTerms(token) }.use { observed ->
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                token.stutype = EASToken.TYPE.GRAD
                release.countDown()
                assertEquals(DataState.STATE.SUCCESS, observed.await { it.state == DataState.STATE.SUCCESS }.state)
            } finally {
                release.countDown()
            }
        }
        assertEquals(mapOf("p_pylx" to "2"), form(webRequests.last()))
        assertEquals(listOf(WEB_TERMS, WEB_CURRENT), webRequests.map { it.path })
    }

    @Test
    fun webRouteDoesNotSwitchToApiWhenBearerAppearsDuringTheQuery() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = webToken().apply { sessionGeneration = 89L }
        val callbackToken = AtomicReference<EASToken>()
        val service = shenzhenTermService { callbackToken.set(it) }
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> { hold(entered, release); json(WEB_LIST).addHeader("Set-Cookie", "SESSION=renewed-web; Path=/") }
            WEB_CURRENT -> json("{}")
            else -> error("Unexpected path ${request.path}")
        } }
        val query = async(Dispatchers.Main) { service.getAllTermsOnce(token) }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            token.accessToken = "appeared-after-route-selection"
            release.countDown()
            assertEquals(DataState.STATE.SUCCESS, withTimeout(20_000) { query.await() }.state)
        } finally {
            release.countDown()
            query.cancel()
            withTimeout(20_000) { query.join() }
        }
        assertTrue(apiRequests.isEmpty())
        assertEquals(listOf(WEB_TERMS, WEB_CURRENT), webRequests.map { it.path })
        assertSame(token, callbackToken.get())
        assertEquals(89L, token.sessionGeneration)
        assertEquals("renewed-web", token.webCookies["SESSION"])
    }

    @Test
    fun benbuGraduateRequestKeepsItsRouteAfterStudentTypeChanges() = heldBenbuRoute(
        graduate = true, heldPath = GRADUATE_TERMS,
        expected = listOf(GRADUATE_TERMS, GRADUATE_BRIDGE),
    )

    @Test
    fun benbuUndergraduateRequestKeepsItsRouteAfterStudentTypeChanges() = heldBenbuRoute(
        graduate = false, heldPath = SCORE_TERMS,
        expected = listOf(SCORE_TERMS, TIMETABLE_TERMS),
    )

    private fun cancelRegular(campus: EASToken.Campus, heldPath: String) {
        cancelHeld(legacyToken(campus), expectedApi = if (heldPath == SCORE_TERMS) listOf(SCORE_TERMS)
            else listOf(SCORE_TERMS, TIMETABLE_TERMS)) { entered, release ->
            apiResponse = { request ->
                if (request.path == heldPath) hold(entered, release)
                html(singleTermPage(regularSelector(request.path)))
            }
        }
    }

    private fun alreadyCancelled(token: EASToken) = runBlocking {
        val parent = Job()
        val entryReached = AtomicBoolean()
        val returned = AtomicReference<DataState<List<TermItem>>>()
        val caught = AtomicReference<CancellationException>()
        val service = termService(token.campus)
        // UNDISPATCHED executes this body; a never-started cancelled launch would be a vacuous test.
        val child = CoroutineScope(parent + Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
            parent.cancel(CancellationException("parent cancelled immediately before Once entry"))
            entryReached.set(true)
            try {
                returned.set(service.getAllTermsOnce(token))
            } catch (error: CancellationException) {
                caught.set(error)
                throw error
            }
        }
        try {
            withTimeout(20_000) { child.join() }
            assertTrue(entryReached.get())
            assertNotNull(caught.get())
            assertNull(returned.get())
            assertTrue(child.isCancelled)
            assertTrue(apiRequests.isEmpty())
            assertTrue(webRequests.isEmpty())
        } finally {
            parent.cancel()
            withTimeout(20_000) { parent.join() }
        }
    }

    private fun cancelHeld(
        token: EASToken,
        webOnly: Boolean = false,
        expectedApi: List<String> = emptyList(),
        expectedWeb: List<String> = emptyList(),
        configure: (CountDownLatch, CountDownLatch) -> Unit,
    ) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responsive = CountDownLatch(1)
        val returned = AtomicReference<DataState<List<TermItem>>>()
        val caught = AtomicReference<CancellationException>()
        val parent = Job()
        val service = termService(token.campus)
        configure(entered, release)
        val child = CoroutineScope(parent + Dispatchers.Main).launch {
            try {
                returned.set(if (webOnly) (service as EASWebSource).getShenzhenWebTermsOnce(token)
                    else service.getAllTermsOnce(token))
            } catch (error: CancellationException) {
                caught.set(error)
                throw error
            }
        }
        try {
            assertTrue("No held term business request", entered.await(20, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { responsive.countDown() }
            assertTrue("Term request blocked Main", responsive.await(20, TimeUnit.SECONDS))
            parent.cancel(CancellationException("cancel the caller parent during term HTTP"))
        } finally {
            parent.cancel()
            // A synchronous socket needs its response to return; cancellation cannot stop it instantly.
            release.countDown()
            withTimeout(20_000) { child.join(); parent.join() }
        }
        assertNotNull("Caller must receive cancellation", caught.get())
        assertNull("Cancellation must not return any DataState", returned.get())
        assertTrue(parent.isCancelled)
        assertTrue(child.isCancelled)
        assertFalse(child.isActive)
        assertEquals("No later term API business request", expectedApi, apiRequests.map { it.path })
        assertEquals("No later term Web business request", expectedWeb, webRequests.map { it.path })
    }

    private suspend fun assertCallbackCancellation(service: EASService, token: EASToken, sentinel: CancellationException) {
        var returned: DataState<List<TermItem>>? = null
        var caught: CancellationException? = null
        try {
            returned = withContext(Dispatchers.Main) { service.getAllTermsOnce(token) }
        } catch (error: CancellationException) {
            caught = error
        }
        assertNull(returned)
        assertSame(sentinel, caught)
    }

    private fun regularCallbackCancellation(campus: EASToken.Campus) = runBlocking {
        val sentinel = CancellationException("synthetic regular term callback cancellation")
        val token = legacyToken(campus).apply { sessionGeneration = 97L }
        val callbackToken = AtomicReference<EASToken>()
        val service = termService(campus) { callbackToken.set(it); throw sentinel }
        apiResponse = { html(singleTermPage(regularSelector(it.path)))
            .addHeader("Set-Cookie", "JSESSIONID=completed-cookie; Path=/") }
        assertCallbackCancellation(service, token, sentinel)
        assertSame(token, callbackToken.get())
        assertEquals(97L, token.sessionGeneration)
        assertEquals("completed-cookie", token.cookies["JSESSIONID"])
        assertEquals(listOf(SCORE_TERMS), apiRequests.map { it.path })
        assertTrue(webRequests.isEmpty())
    }

    private fun regularLegacyCallbackCancellation(campus: EASToken.Campus) {
        val sentinel = CancellationException("legacy regular term callback cancellation")
        val service = termService(campus) { throw sentinel }
        apiResponse = { request ->
            val response = html(singleTermPage(regularSelector(request.path)))
            if (request.path == SCORE_TERMS) response.addHeader("Set-Cookie", "JSESSIONID=legacy-completed-cookie; Path=/")
            response
        }
        val token = legacyToken(campus)
        val state = awaitState { service.getAllTerms(token) }
        assertEquals(DataState.STATE.SUCCESS, state.state)
        assertEquals(listOf("2025-2026-1"), state.data?.map { it.id })
        assertEquals("legacy-completed-cookie", token.cookies["JSESSIONID"])
        assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
    }

    private fun capturedWebStudentType(initial: EASToken.TYPE, changed: EASToken.TYPE, expected: String) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = webToken().apply { stutype = initial }
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> { hold(entered, release); json(WEB_LIST) }
            WEB_CURRENT -> json("{}")
            else -> error("Unexpected path ${request.path}")
        } }
        val query = async(Dispatchers.Main) { shenzhenTermService().getAllTermsOnce(token) }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            token.stutype = changed
            release.countDown()
            assertEquals(DataState.STATE.SUCCESS, withTimeout(20_000) { query.await() }.state)
        } finally {
            release.countDown()
            query.cancel()
            withTimeout(20_000) { query.join() }
        }
        assertEquals(mapOf("p_pylx" to expected), form(webRequests.last()))
        assertEquals(listOf(WEB_TERMS, WEB_CURRENT), webRequests.map { it.path })
    }

    /** Runtime route stability only; pre-IO capture placement is separately verified in source review. */
    private fun heldBenbuRoute(graduate: Boolean, heldPath: String, expected: List<String>) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = if (graduate) graduateTermToken() else legacyToken(EASToken.Campus.BENBU)
        apiResponse = { request ->
            if (request.path == heldPath) hold(entered, release)
            when (request.path) {
                GRADUATE_TERMS -> json("[]")
                GRADUATE_BRIDGE -> html(singleTermPage("xnxq"))
                SCORE_TERMS, TIMETABLE_TERMS -> html(singleTermPage(regularSelector(request.path)))
                else -> error("Unexpected path ${request.path}")
            }
        }
        val query = async(Dispatchers.Main) { termService(EASToken.Campus.BENBU).getAllTermsOnce(token) }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            token.stutype = if (graduate) EASToken.TYPE.UNDERGRAD else EASToken.TYPE.GRAD
            release.countDown()
            assertEquals(DataState.STATE.SUCCESS, withTimeout(20_000) { query.await() }.state)
        } finally {
            release.countDown()
            query.cancel()
            withTimeout(20_000) { query.join() }
        }
        assertEquals(expected, apiRequests.map { it.path })
        assertTrue(webRequests.isEmpty())
    }
}
