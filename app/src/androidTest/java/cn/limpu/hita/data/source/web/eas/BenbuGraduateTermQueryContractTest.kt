package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenbuGraduateTermQueryContractTest : TermQueryContractHarness() {
    @Test
    fun semesterJsonUsesGraduatePostAndKeepsGraduateCurrentFirstOrdering() {
        apiResponse = { json("""{"data":[
            {"xnxq":"2025-20261","name":"2025-2026秋季学期"},
            {"xnxq":"2026-20271","name":"2026-2027秋季学期"},
            {"xnxq":"2025-20262","name":"2025-2026春季学期","current":true}
        ]}""") }
        val terms = success { legacySource(EASToken.Campus.BENBU).getAllTerms(graduateToken()) }
        assertEquals(listOf("2025-2026-2", "2026-2027-1", "2025-2026-1"), terms.map { it.id })
        assertEquals(listOf(true, false, false), terms.map { it.isCurrent })
        assertEquals(listOf("2025-2026", "2025-2026", "2", "春季学期", "2025-2026春季学期", "2025-2026-2", true), termFields(terms[0]))
        val request = apiRequests.single()
        assertEquals(GRADUATE_TERMS, request.path)
        assertEquals("POST", request.method)
        assertTrue(form(request).isEmpty())
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertEquals("application/json, text/plain, */*", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("JSESSIONID=local-term-session"))
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun semesterResponseCookiesRetainExistingNonPersistenceBehavior() {
        apiResponse = { json("""[{"xnxq":"2025-20261","current":true}]""")
            .addHeader("Set-Cookie", "JSESSIONID=not-adopted-by-semester; Path=/") }
        val token = graduateToken()
        assertEquals(1, success { legacySource(EASToken.Campus.BENBU).getAllTerms(token) }.size)
        assertEquals("local-term-session", token.cookies["JSESSIONID"])
        assertEquals(0, legacyCookieCallbacks.get())
        assertEquals(1, apiRequests.size)
    }

    @Test
    fun emptySemesterJsonBridgesToHtmlAndUsesItsOriginalOptionOrder() {
        apiResponse = { request -> when (request.path) {
            GRADUATE_TERMS -> json("[]")
            GRADUATE_BRIDGE -> MockResponse().setResponseCode(302)
                .addHeader("Location", api.url(GRADUATE_PAGE).toString())
                .addHeader("Set-Cookie", "bridge-session=from-redirect; Path=/")
            GRADUATE_PAGE -> html(termPage("xnxq", """
                <option value='2025-20263'>2025-2026夏季学期</option>
                <option value='2025-20261' selected>2025-2026秋季学期</option>
            """)).addHeader("Set-Cookie", "JSESSIONID=graduate-course-session; Path=/")
            else -> error("Unexpected graduate path ${request.path}")
        } }
        val token = graduateToken()
        val terms = success { legacySource(EASToken.Campus.BENBU).getAllTerms(token) }
        assertEquals(listOf("2025-2026-3", "2025-2026-1"), terms.map { it.id })
        assertEquals(listOf(false, true), terms.map { it.isCurrent })
        assertEquals(listOf(GRADUATE_TERMS, GRADUATE_BRIDGE, GRADUATE_PAGE), apiRequests.map { it.path })
        assertEquals("GET", apiRequests[1].method)
        assertEquals("GET", apiRequests[2].method)
        assertTrue(apiRequests[2].getHeader("Cookie").orEmpty().contains("bridge-session=from-redirect"))
        assertEquals("graduate-course-session", token.cookies["JSESSIONID"])
        assertEquals("from-redirect", token.cookies["bridge-session"])
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun malformedSemesterJsonAlsoFallsBackToTheGraduateHtmlBridge() {
        apiResponse = { request -> when (request.path) {
            GRADUATE_TERMS -> json("not-json")
            GRADUATE_BRIDGE -> html(singleTermPage("xnxq"))
            else -> error("Unexpected graduate path ${request.path}")
        } }
        assertEquals("2025-2026-1", success { legacySource(EASToken.Campus.BENBU).getAllTerms(graduateToken()) }.single().id)
        assertEquals(listOf(GRADUATE_TERMS, GRADUATE_BRIDGE), apiRequests.map { it.path })
    }

    @Test
    fun emptyGraduateHtmlReportsGraduateSpecificFetchFailure() {
        apiResponse = { request -> when (request.path) {
            GRADUATE_TERMS -> json("[]")
            GRADUATE_BRIDGE -> html("<html><body>no graduate terms</body></html>")
            else -> error("Unexpected graduate path ${request.path}")
        } }
        val state = awaitState { legacySource(EASToken.Campus.BENBU).getAllTerms(graduateToken()) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("未获取到研究生学期列表", state.message)
        assertNull(state.data)
        assertEquals(listOf(GRADUATE_TERMS, GRADUATE_BRIDGE), apiRequests.map { it.path })
    }

    @Test
    fun semesterHttpFailureDoesNotAttemptUndergraduateOrBridgeRequests() {
        listOf(401, 403, 500).forEach { status ->
            apiResponse = { json("[]", status) }
            val state = awaitState { legacySource(EASToken.Campus.BENBU).getAllTerms(graduateToken()) }
            assertEquals(DataState.STATE.FETCH_FAILED, state.state)
            assertEquals("HTTP $status", state.message)
        }
        assertEquals(listOf(GRADUATE_TERMS, GRADUATE_TERMS, GRADUATE_TERMS), apiRequests.map { it.path })
    }

    @Test
    fun bridgeHttpFailureReturnsItsExistingFailureMessage() {
        apiResponse = { request -> when (request.path) {
            GRADUATE_TERMS -> json("[]")
            GRADUATE_BRIDGE -> html("unavailable", 503)
            else -> error("Unexpected graduate path ${request.path}")
        } }
        val state = awaitState { legacySource(EASToken.Campus.BENBU).getAllTerms(graduateToken()) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("研究生课表桥接失败 HTTP 503", state.message)
        assertEquals(listOf(GRADUATE_TERMS, GRADUATE_BRIDGE), apiRequests.map { it.path })
    }

    @Test
    fun bridgeRedirectToADifferentLocalHostIsRejectedByTheExplicitOverride() {
        val sourceHost = api.url("/").host
        // MockWebServer may reverse-resolve its 127.0.0.1 bind address to localhost.
        val redirectHost = if (sourceHost == "127.0.0.1") "localhost" else "127.0.0.1"
        val redirectUrl = web.url(GRADUATE_PAGE).newBuilder().host(redirectHost).build()
        assertNotEquals("Fixture must redirect to a different host spelling", sourceHost, redirectUrl.host)
        webResponse = { html(singleTermPage("xnxq")) }
        apiResponse = { request -> when (request.path) {
            GRADUATE_TERMS -> json("[]")
            GRADUATE_BRIDGE -> MockResponse().setResponseCode(302)
                .addHeader("Location", redirectUrl.toString())
            else -> error("Unexpected graduate path ${request.path}")
        } }
        val state = awaitState { legacySource(EASToken.Campus.BENBU).getAllTerms(graduateToken()) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("研究生课表会话建立失败", state.message)
        assertEquals(listOf(GRADUATE_TERMS, GRADUATE_BRIDGE), apiRequests.map { it.path })
        assertEquals(GRADUATE_PAGE, webRequests.single().path)
        assertEquals("${redirectUrl.host}:${redirectUrl.port}", webRequests.single().getHeader("Host"))
    }

    private fun graduateToken() = legacyToken(EASToken.Campus.BENBU).apply { stutype = EASToken.TYPE.GRAD }
}
