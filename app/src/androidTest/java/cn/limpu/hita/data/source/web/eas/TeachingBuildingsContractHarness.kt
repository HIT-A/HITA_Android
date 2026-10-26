package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Synthetic HTTP contracts shared by the legacy teaching-building entry points. */
abstract class TeachingBuildingsContractHarness : ShenzhenAcademicContractHarness() {
    protected val legacyCookieCallbacks = AtomicInteger()

    protected fun benbuSource() = BenbuEASWebSource(
        teachingBuildingsHostOverride = api.url("/").toString(),
        onCookiesUpdated = { legacyCookieCallbacks.incrementAndGet() },
    )

    protected fun weihaiSource() = WeihaiEASWebSource(
        teachingBuildingsHostOverride = api.url("/").toString(),
        onCookiesUpdated = { legacyCookieCallbacks.incrementAndGet() },
    )

    protected fun legacyToken(campus: EASToken.Campus) = EASToken().apply {
        this.campus = campus
        cookies["JSESSIONID"] = "local-legacy-session"
        cookies["HIT"] = "local-legacy-hit"
    }

    protected fun assertBuildings(state: DataState<List<BuildingItem>>, vararg expected: Pair<String, String>) {
        assertEquals(state.message, DataState.STATE.SUCCESS, state.state)
        assertEquals(expected.toList(), requireNotNull(state.data).map { it.id to it.name })
    }

    protected fun assertLegacyRequest(request: RecordedRequest, campusId: String) {
        assertEquals("POST", request.method)
        assertEquals(LEGACY_PATH, request.path)
        assertEquals(mapOf("id" to campusId), form(request))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertEquals("application/json, text/javascript, */*; q=0.01", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("JSESSIONID=local-legacy-session"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("HIT=local-legacy-hit"))
    }

    protected fun assertWebBuildingPost(request: RecordedRequest, path: String) {
        assertEquals("POST", request.method)
        assertEquals(path, request.path)
        assertEquals(emptyMap<String, String>(), form(request))
        assertEquals("01", request.getHeader("RoleCode"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertEquals(web.url("/").toString().trimEnd('/'), request.getHeader("Origin"))
        assertEquals(web.url("/cdkb/querycdzy").toString(), request.getHeader("Referer"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("SESSION=local-web-session"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
    }

    /** Hold the first response until the initial value and eager request are checked. */
    protected fun eagerResult(
        initial: DataState.STATE?,
        entered: CountDownLatch,
        release: CountDownLatch,
        query: () -> LiveData<DataState<List<BuildingItem>>>,
    ): DataState<List<BuildingItem>> {
        lateinit var liveData: LiveData<DataState<List<BuildingItem>>>
        try {
            instrumentation.runOnMainSync {
                liveData = query()
                assertEquals(initial, liveData.value?.state)
                assertTrue("Request must start without an observer", !liveData.hasObservers())
            }
            assertTrue("No request arrived before observing", entered.await(20, TimeUnit.SECONDS))
            return observe { liveData }.use { observed ->
                assertEquals(listOfNotNull(initial), observed.values.map { it.state })
                release.countDown()
                observed.await { it.state != DataState.STATE.NOTHING && it.state != DataState.STATE.LOADING }
            }
        } finally {
            release.countDown()
        }
    }

    protected fun hold(entered: CountDownLatch, release: CountDownLatch) {
        entered.countDown()
        assertTrue("Held teaching-building request was not released", release.await(20, TimeUnit.SECONDS))
    }

    protected companion object {
        const val API_PATH = "/app/commapp/queryjxllist"
        const val WEB_PATH = "/pksd/queryjxlList"
        const val LEGACY_PATH = "/kjscx/queryJxlListBySjid?sf_request_type=ajax"
        val WEB_INITIALIZATION_PATHS = listOf(
            "/pksd/queryxiaoquList", "/pksd/querycdlbList", "/kbfbsz/querydqxnxq",
        )
    }
}
