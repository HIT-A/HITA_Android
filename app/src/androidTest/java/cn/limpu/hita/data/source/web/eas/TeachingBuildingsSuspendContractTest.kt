package cn.limpu.hita.data.source.web.eas

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The original 18 legacy test identities remain intact; these exercise the new suspend API. */
@RunWith(AndroidJUnit4::class)
class TeachingBuildingsSuspendContractTest : TeachingBuildingsContractHarness() {
    @Test
    fun shenzhenApiOnceMatchesLegacyFieldsOrderingDuplicatesAndBearerPriority() = runBlocking {
        apiResponse = {
            json("""{"content":[{"DM":"B","MC":"乙"},7,{"DM":"A","MC":"甲"},{"DM":"B","MC":"重复乙"},{"MC":"无编号"}]}""")
        }

        val state = matchingLegacy(source) { apiToken().apply { webCookies["SESSION"] = "local-web-session" } }

        assertBuildings(state, "B" to "乙", "A" to "甲", "B" to "重复乙", "" to "无编号")
        assertEquals(listOf(API_PATH, API_PATH), apiRequests.map { it.path })
        apiRequests.forEach {
            assertEquals("POST", it.method)
            assertEquals("bearer local-api-token", it.getHeader("authorization"))
            assertEquals("06", it.getHeader("rolecode"))
            assertEquals("cn", it.getHeader("_lang"))
            assertEquals(emptyMap<String, String>(), form(it))
        }
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun shenzhenWebOnceMatchesLegacyInitializationAndFieldFallbacks() = runBlocking {
        webResponse = { request ->
            if (request.path == WEB_PATH) {
                json("""[{"DM":"B","MC":"乙"},{"dm":"A","mc":"甲"},{"DM":"C"},{"MC":"无编号"},{"DM":"B","MC":"重复乙"}]""")
            } else {
                json("[]")
            }
        }

        val state = matchingLegacy(source, ::webToken)

        assertBuildings(state, "B" to "乙", "A" to "甲", "C" to "C", "B" to "重复乙")
        val sequence = WEB_INITIALIZATION_PATHS + WEB_PATH
        assertEquals(sequence + sequence, webRequests.map { it.path })
        webRequests.forEach { assertWebBuildingPost(it, requireNotNull(it.path)) }
        assertEquals(8, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun benbuOnceMatchesLegacyCampusOrderAndPreservesBlankAndDuplicateIds() = runBlocking {
        apiResponse = { request ->
            if (form(request)["id"] == "1") {
                json("""[{"DM":"B","MC":"乙"},{"MC":"无编号"}]""")
            } else {
                json("""[{"DM":"B","MC":"重复乙"},{"DM":"A","MC":"甲"}]""")
            }
        }

        val state = matchingLegacy(benbuSource()) { legacyToken(EASToken.Campus.BENBU) }

        assertBuildings(state, "B" to "乙", "" to "无编号", "B" to "重复乙", "A" to "甲")
        assertEquals(listOf("1", "2", "1", "2"), apiRequests.map { form(it)["id"] })
        apiRequests.forEach { assertLegacyRequest(it, requireNotNull(form(it)["id"])) }
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun weihaiOnceMatchesLegacyFallbackAndFirstIdWins() = runBlocking {
        apiResponse = { request ->
            when (form(request)["id"]) {
                "01" -> json("""[{"DM":" ","MC":"空编号"}]""")
                "1" -> json("""[{"DM":"B","MC":"乙"},{"DM":"A","MC":"甲"}]""")
                "2" -> json("""[{"DM":"B","MC":"重复乙"},{"DM":"C","MC":"丙"}]""")
                else -> throw AssertionError("Unexpected campus ${form(request)}")
            }
        }

        val state = matchingLegacy(weihaiSource()) { legacyToken(EASToken.Campus.WEIHAI) }

        assertBuildings(state, "B" to "乙", "A" to "甲", "C" to "丙")
        assertEquals(listOf("01", "1", "2", "01", "1", "2"), apiRequests.map { form(it)["id"] })
        apiRequests.forEach { assertLegacyRequest(it, requireNotNull(form(it)["id"])) }
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun shenzhenApiOnceMatchesLegacyMalformedAuthHttpAndTransportMappings() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { json("not-json") },
            { json("""{"code":401}""", 401) },
            { json("""{"content":[{"DM":"A","MC":"甲"}]}""", 503) },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        responses.forEach { response ->
            apiResponse = { response() }
            matchingLegacy(source, ::apiToken)
        }
        assertEquals(8, apiRequests.size)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun shenzhenWebOnceMatchesLegacyEmptyParseAuthHttpAndTransportMappings() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { json("[]") },
            { json("{\"invalid\":") },
            { json("""{"code":401}""", 401) },
            { json("[]", 503) },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        responses.forEach { response ->
            webResponse = { request -> if (request.path == WEB_PATH) response() else json("[]") }
            matchingLegacy(source, ::webToken)
        }
        assertEquals(40, webRequests.size)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun benbuOnceMatchesLegacyPartialParserNon200AndTransportMappings() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { json("[]") },
            { json("not-json") },
            { json("""[{"DM":"A","MC":"有效前缀"},7,{"DM":"B","MC":"跳过后缀"}]""") },
            { json("[]", 401) },
            { json("[]", 503) },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        val service = benbuSource()
        responses.forEach { response ->
            apiResponse = { response() }
            matchingLegacy(service) { legacyToken(EASToken.Campus.BENBU) }
        }
        assertEquals(22, apiRequests.size)
    }

    @Test
    fun weihaiOnceMatchesLegacyNon200MalformedAndTransportFallbackMappings() = runBlocking {
        val responses: List<() -> MockResponse> = listOf(
            { json("[]") },
            { json("not-json") },
            { json("[]", 401) },
            { json("[]", 503) },
            { json("invalid-gzip").setHeader("Content-Encoding", "gzip") },
        )
        val service = weihaiSource()
        responses.forEach { response ->
            apiResponse = { response() }
            matchingLegacy(service) { legacyToken(EASToken.Campus.WEIHAI) }
        }
        assertEquals(26, apiRequests.size)
    }

    @Test
    fun shenzhenApiCancellationLeavesMainResponsiveAndDoesNotDeliverResult() {
        cancelledRequest(source, apiToken(), useWeb = false, expected = listOf(API_PATH)) { entered, release ->
            apiResponse = {
                hold(entered, release)
                json("""{"content":[{"DM":"A","MC":"甲"}]}""")
            }
        }
    }

    @Test
    fun shenzhenWebCancellationLeavesMainResponsiveAndStopsAfterFirstInitialization() {
        cancelledRequest(source, webToken(), useWeb = true, expected = listOf(WEB_INITIALIZATION_PATHS.first())) { entered, release ->
            webResponse = {
                hold(entered, release)
                json("[]")
            }
        }
    }

    @Test
    fun benbuCancellationLeavesMainResponsiveAndStopsBeforeSecondCampus() {
        cancelledRequest(benbuSource(), legacyToken(EASToken.Campus.BENBU), useWeb = false, expected = listOf("1")) { entered, release ->
            apiResponse = {
                hold(entered, release)
                json("[]")
            }
        }
    }

    @Test
    fun weihaiCancellationLeavesMainResponsiveAndStopsBeforeFallback() {
        cancelledRequest(weihaiSource(), legacyToken(EASToken.Campus.WEIHAI), useWeb = false, expected = listOf("01")) { entered, release ->
            apiResponse = {
                hold(entered, release)
                json("[]")
            }
        }
    }

    @Test
    fun weihaiCancellationDuringFallbackStopsBeforeCampusTwo() {
        cancelledRequest(weihaiSource(), legacyToken(EASToken.Campus.WEIHAI), useWeb = false, expected = listOf("01", "1")) { entered, release ->
            apiResponse = { request ->
                if (form(request)["id"] == "1") hold(entered, release)
                json("[]")
            }
        }
    }

    @Test
    fun cancellationExceptionFromTokenCallbackEscapesWithoutBecomingBusinessFailure() = runBlocking {
        val sentinel = CancellationException("synthetic teaching-building cancellation")
        val service = EASWebSource(
            onTokenRefreshed = { throw sentinel },
            apiHostOverride = api.url("/").toString(),
            webHostOverride = web.url("/").toString(),
        )
        webResponse = { json("[]") }
        var returned: DataState<List<BuildingItem>>? = null
        var caught: CancellationException? = null

        try {
            returned = service.getTeachingBuildingsOnce(webToken())
        } catch (error: CancellationException) {
            caught = error
        }

        assertNull("Cancellation must not return FETCH_FAILED", returned)
        assertSame(sentinel, caught)
        assertEquals(listOf(WEB_INITIALIZATION_PATHS.first()), webRequests.map { it.path })
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun legacyWebCallbackCancellationStillDeliversItsHistoricalFailureState() {
        val sentinel = CancellationException("synthetic legacy callback cancellation")
        val service = EASWebSource(
            onTokenRefreshed = { throw sentinel },
            apiHostOverride = api.url("/").toString(),
            webHostOverride = web.url("/").toString(),
        )
        webResponse = { json("[]") }

        val state = awaitState { service.getTeachingBuildings(webToken()) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals(sentinel.message, state.message)
        assertNull(state.data)
        assertEquals(listOf(WEB_INITIALIZATION_PATHS.first()), webRequests.map { it.path })
        assertTrue(apiRequests.isEmpty())
    }

    private suspend fun matchingLegacy(service: EASService, token: () -> EASToken): DataState<List<BuildingItem>> {
        val legacy = awaitState { service.getTeachingBuildings(token()) }
        val once = withContext(Dispatchers.Main) { service.getTeachingBuildingsOnce(token()) }
        assertEquals(legacy.state, once.state)
        assertEquals(legacy.message, once.message)
        assertEquals(legacy.data?.map { it.id to it.name }, once.data?.map { it.id to it.name })
        assertEquals(legacy.listAction, once.listAction)
        assertEquals(legacy.fromCache, once.fromCache)
        assertEquals(legacy.stateRetried, once.stateRetried)
        return once
    }

    private fun cancelledRequest(
        service: EASService,
        token: EASToken,
        useWeb: Boolean,
        expected: List<String>,
        configure: (CountDownLatch, CountDownLatch) -> Unit,
    ): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mainResponsive = CountDownLatch(1)
        val returned = AtomicReference<DataState<List<BuildingItem>>>()
        val caught = AtomicReference<CancellationException>()
        configure(entered, release)
        val query = launch(Dispatchers.Main) {
            try {
                returned.set(service.getTeachingBuildingsOnce(token))
            } catch (error: CancellationException) {
                caught.set(error)
                throw error
            }
        }
        try {
            assertTrue("No request arrived", entered.await(20, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { mainResponsive.countDown() }
            assertTrue("HTTP blocked the main thread", mainResponsive.await(20, TimeUnit.SECONDS))
            query.cancel(CancellationException("cancel held teaching-building request"))
        } finally {
            query.cancel()
            release.countDown()
            withTimeout(20_000) { query.join() }
        }
        assertNull("Cancelled query must not deliver a DataState", returned.get())
        assertNotNull("Cancellation must propagate to caller", caught.get())
        val requests = if (useWeb) webRequests else apiRequests
        val actual = requests.map { request ->
            if (token.campus == EASToken.Campus.SHENZHEN) requireNotNull(request.path)
            else requireNotNull(form(request)["id"])
        }
        assertEquals("No next HTTP request after cancellation", expected, actual)
    }
}
