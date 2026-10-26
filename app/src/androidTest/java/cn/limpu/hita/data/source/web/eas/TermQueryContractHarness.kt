package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Local HTTP fixtures for the existing, eager term-list entry points. */
abstract class TermQueryContractHarness : ShenzhenAcademicContractHarness() {
    protected val legacyCookieCallbacks = AtomicInteger()
    protected val legacyRefreshedToken = AtomicReference<EASToken>()

    protected fun legacySource(campus: EASToken.Campus): AbstractEASWebSource {
        val callback: (EASToken) -> Unit = {
            legacyRefreshedToken.set(it)
            legacyCookieCallbacks.incrementAndGet()
        }
        return when (campus) {
            EASToken.Campus.BENBU -> BenbuEASWebSource(
                teachingBuildingsHostOverride = null,
                onCookiesUpdated = callback,
                termsHostOverride = api.url("/").toString(),
                graduateTermsHostOverride = api.url("/").toString(),
            )
            EASToken.Campus.WEIHAI -> WeihaiEASWebSource(
                teachingBuildingsHostOverride = null,
                onCookiesUpdated = callback,
                termsHostOverride = api.url("/").toString(),
            )
            else -> error("Use the Shenzhen source for Shenzhen terms")
        }
    }

    protected fun legacyToken(campus: EASToken.Campus) = EASToken().apply {
        this.campus = campus
        cookies["JSESSIONID"] = "local-term-session"
        cookies["HIT"] = "local-term-hit"
    }

    protected fun html(body: String, status: Int = 200) = json(body, status)
        .setHeader("Content-Type", "text/html; charset=utf-8")

    protected fun termPage(selector: String, options: String) =
        "<html><head><title>合成学期页</title></head><body><select name='$selector'>$options</select></body></html>"

    protected fun singleTermPage(selector: String) = termPage(selector,
        "<option value='2025-20261' selected>2025-2026秋季学期</option>")

    protected fun assertLegacyGet(request: RecordedRequest, path: String) {
        assertEquals("GET", request.method)
        assertEquals(path, request.path)
        assertEquals("", request.body.clone().readUtf8())
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", request.getHeader("Accept"))
        assertEquals("zh-CN,zh-Hans;q=0.9", request.getHeader("Accept-Language"))
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("Mozilla/5.0"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("HIT=local-term-hit"))
        assertNull(request.getHeader("Authorization"))
    }

    protected fun assertWebPost(request: RecordedRequest, path: String, referer: String) {
        assertEquals("POST", request.method)
        assertEquals(path, request.path)
        assertEquals(web.url(referer).toString(), request.getHeader("Referer"))
        assertEquals(web.url("/").toString().trimEnd('/'), request.getHeader("Origin"))
        assertEquals("01", request.getHeader("RoleCode"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertNull(request.getHeader("Authorization"))
    }

    protected fun termFields(term: TermItem): List<Any> = listOf(
        term.yearCode, term.yearName, term.termCode, term.termName,
        term.name, term.id, term.isCurrent,
    )

    protected fun eagerTerms(
        initial: DataState.STATE?,
        entered: CountDownLatch,
        release: CountDownLatch,
        query: () -> LiveData<DataState<List<TermItem>>>,
    ): DataState<List<TermItem>> {
        lateinit var liveData: LiveData<DataState<List<TermItem>>>
        try {
            instrumentation.runOnMainSync {
                liveData = query()
                // Both legacy postValue and Shenzhen's no-initial-state route are null here.
                assertNull(liveData.value)
                assertTrue(!liveData.hasObservers())
            }
            assertTrue("Term request did not begin without observers", entered.await(20, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
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
        assertTrue("Held term response was not released", release.await(20, TimeUnit.SECONDS))
    }

    protected companion object {
        const val SCORE_TERMS = "/cjcx/queryQmcj"
        const val TIMETABLE_TERMS = "/kbcx/queryGrkb"
        const val API_TERMS = "/app/commapp/queryxnxqlist"
        const val WEB_TERMS = "/component/queryXnxq"
        const val WEB_FALLBACK = "/component/queryxnxqdata"
        const val WEB_CURRENT = "/Xsxk/queryXkdqXnxq"
        const val GRADUATE_TERMS = "/yjsgl/common/getSemester"
        const val GRADUATE_BRIDGE = "/yjsgl/outInterface/grkb"
        const val GRADUATE_PAGE = "/kbgl/queryxskbxsy"
        const val WEB_LIST = """{"data":[
            {"XN":"2026-2027","XNMC":"2026-2027学年","XQ":"1","XQMC":"秋季","SFDQXQ":"0"},
            {"XN":"2025-2026","XNMC":"2025-2026学年","XQ":"3","XQMC":"夏季","SFDQXQ":"1"}
        ]}"""
    }
}
