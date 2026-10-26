package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
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

/** Complements the four existing term cases in ShenzhenAcademicCatalogContractTest. */
@RunWith(AndroidJUnit4::class)
class ShenzhenTermQueryContractTest : TermQueryContractHarness() {
    @Test
    fun apiStartsEagerlyWithoutAnInitialLoadingState() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        apiResponse = { hold(entered, release); json("""{"content":[]}""") }
        assertEquals(DataState.STATE.SUCCESS, eagerTerms(null, entered, release) {
            source.getAllTerms(apiToken())
        }.state)
        assertEquals(API_TERMS, apiRequests.single().path)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiEmptyArrayIsSuccessAndDoesNotUseAvailableWebSession() {
        apiResponse = { json("""{"content":[]}""") }
        val token = apiToken().apply { webCookies["SESSION"] = "unused-web" }
        assertTrue(success { source.getAllTerms(token) }.isEmpty())
        assertEquals(1, apiRequests.size)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiMissingOrMalformedContentIsNotLoggedIn() {
        listOf("not-json", "{}", """{"content":null}""", """{"content":{}}""").forEach { body ->
            apiResponse = { json(body) }
            val state = awaitState { source.getAllTerms(apiToken()) }
            assertEquals(body, DataState.STATE.NOT_LOGGED_IN, state.state)
            assertNull(state.data)
        }
        assertEquals(4, apiRequests.size)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiAuthenticationErrorsDoNotFallBackToWeb() {
        listOf(401, 403).forEach { status ->
            apiResponse = { json("""{"code":$status}""", status) }
            val token = apiToken().apply { webCookies["SESSION"] = "unused-web" }
            assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState { source.getAllTerms(token) }.state)
        }
        assertEquals(2, apiRequests.size)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiUsesContentEvenWhenHttpStatusIsAnError() {
        apiResponse = { json("""{"content":[{"XN":"2025-2026","XQ":"2","XNXQMC":"春季","SFDQXQ":"1"}]}""", 500) }
        val item = success { source.getAllTerms(apiToken()) }.single()
        assertEquals(listOf("2025-2026", "2025-2026", "2", "春季", "2025-2026 春季", "2025-2026-2", true), termFields(item))
        assertEquals(1, apiRequests.size)
    }

    @Test
    fun apiPreservesDuplicatesAndBlankObjectFieldsWhileSkippingNonObjects() {
        apiResponse = { json("""{"content":[null,4,"ignored",{},
            {"XN":"2026-2027","XQ":"1","XNXQMC":"秋季","SFDQXQ":"true"},
            {"XN":"2026-2027","XQ":"1","XNXQMC":"秋季","SFDQXQ":"1"}]}""") }
        val terms = success { source.getAllTerms(apiToken()) }
        assertEquals(3, terms.size)
        assertEquals(listOf("", "", "", "", "", "-", false), termFields(terms[0]))
        assertEquals(listOf("-", "2026-2027-1", "2026-2027-1"), terms.map { it.id })
        assertEquals(listOf(false, false, true), terms.map { it.isCurrent })
    }

    @Test
    fun apiCookieResponseMutatesOnlyApiCookiesWithoutRefreshCallback() {
        apiResponse = { json("""{"content":[]}""").addHeader("Set-Cookie", "api-cookie=renewed-api; Path=/") }
        val token = apiToken().apply { webCookies["SESSION"] = "unchanged-web" }
        success { source.getAllTerms(token) }
        assertEquals("renewed-api", token.cookies["api-cookie"])
        assertEquals("unchanged-web", token.webCookies["SESSION"])
        assertEquals(0, refreshCount.get())
        val request = apiRequests.single()
        assertEquals("POST", request.method)
        assertEquals(API_TERMS, request.path)
        assertEquals("bearer local-api-token", request.getHeader("Authorization"))
        assertEquals("06", request.getHeader("rolecode"))
        assertTrue(form(request).isEmpty())
        assertFalse(request.getHeader("Cookie").orEmpty().contains("SESSION"))
    }

    @Test
    fun absentSessionStillUsesLegacyApiRequest() {
        apiResponse = { json("{}") }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState { source.getAllTerms(EASToken()) }.state)
        assertEquals("bearer null", apiRequests.single().getHeader("Authorization"))
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun directWebEntryRejectsMissingSessionWithoutHttp() {
        val state = awaitState { source.getShenzhenWebTerms(apiToken()) }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
        assertEquals("请先连接深圳 Web 教务", state.message)
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun blankBearerAndDirectWebCookiesSelectWebRoute() {
        validWebResponses()
        val token = EASToken().apply {
            accessToken = "  "
            webCookies["JSESSIONID"] = "direct-session"
            webCookies["route"] = "direct-route"
        }
        assertEquals(2, success { source.getAllTerms(token) }.size)
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.first().getHeader("Cookie").orEmpty().contains("JSESSIONID=direct-session"))
        assertTrue(webRequests.first().getHeader("Cookie").orEmpty().contains("route=direct-route"))
    }

    @Test
    fun cookiesFromAnotherCampusDoNotQualifyAsShenzhenWebSession() {
        apiResponse = { json("""{"content":[]}""") }
        val token = webToken().apply { campus = EASToken.Campus.WEIHAI }
        assertTrue(success { source.getAllTerms(token) }.isEmpty())
        assertEquals(API_TERMS, apiRequests.single().path)
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun invalidSelectionDropdownFallsBackAndUsesLegacyReferer() {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json("not-json")
            WEB_FALLBACK -> json(WEB_LIST)
            WEB_CURRENT -> json("{}")
            else -> error("Unexpected term path ${request.path}")
        } }
        assertEquals(listOf(false, true), success { source.getAllTerms(webToken()) }.map { it.isCurrent })
        assertEquals(listOf(WEB_TERMS, WEB_FALLBACK, WEB_CURRENT), webRequests.map { it.path })
        assertWebPost(webRequests[1], WEB_FALLBACK, "/authentication/main")
        assertTrue(form(webRequests[1]).isEmpty())
    }

    @Test
    fun expiredPrimaryDropdownStopsBeforeFallback() {
        webResponse = { json("""{"code":401,"message":"session expired"}""", 401) }
        val state = awaitState { source.getAllTerms(webToken()) }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
        assertEquals("深圳 Web 会话已失效", state.message)
        assertEquals(WEB_TERMS, webRequests.single().path)
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun loginHtmlOnPrimaryDropdownStopsBeforeFallback() {
        webResponse = { html("<html><title>统一身份认证</title><form action='/login'><input name='password'></form></html>") }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState { source.getAllTerms(webToken()) }.state)
        assertEquals(WEB_TERMS, webRequests.single().path)
    }

    @Test
    fun expiredFallbackStopsBeforeCurrentTermQuery() {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json("{}")
            WEB_FALLBACK -> json("""{"code":403}""", 403)
            else -> error("Unexpected term path ${request.path}")
        } }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState { source.getAllTerms(webToken()) }.state)
        assertEquals(listOf(WEB_TERMS, WEB_FALLBACK), webRequests.map { it.path })
    }

    @Test
    fun twoEmptyDropdownsFailWithoutCurrentTermRequest() {
        webResponse = { json("[]") }
        val state = awaitState { source.getAllTerms(webToken()) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("学期列表解析失败", state.message)
        assertNull(state.data)
        assertEquals(listOf(WEB_TERMS, WEB_FALLBACK), webRequests.map { it.path })
    }

    @Test
    fun fallbackHttpErrorDiscardsOtherwiseParsableTerms() {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json("{}")
            WEB_FALLBACK -> json(WEB_LIST, 500)
            else -> error("Unexpected term path ${request.path}")
        } }
        assertEquals(DataState.STATE.FETCH_FAILED, awaitState { source.getAllTerms(webToken()) }.state)
        assertEquals(listOf(WEB_TERMS, WEB_FALLBACK), webRequests.map { it.path })
    }

    @Test
    fun unknownSelectionTermKeepsDropdownCurrentFlags() {
        validWebResponses("""{"p_xn":"2099-2100","p_xq":"1"}""")
        assertEquals(listOf(false, true), success { source.getAllTerms(webToken()) }.map { it.isCurrent })
        assertEquals(listOf(WEB_TERMS, WEB_CURRENT), webRequests.map { it.path })
    }

    @Test
    fun malformedAuxiliarySelectionResponseKeepsValidDropdown() {
        validWebResponses("not-json")
        assertEquals(listOf(false, true), success { source.getAllTerms(webToken()) }.map { it.isCurrent })
        assertEquals(2, webRequests.size)
    }

    @Test
    fun auxiliaryNonAuthHttpErrorStillUsesItsTermFields() {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json(WEB_LIST)
            WEB_CURRENT -> json("""{"p_xn":"2026-2027","p_xq":"1"}""", 500)
            else -> error("Unexpected term path ${request.path}")
        } }
        assertEquals(listOf(true, false), success { source.getAllTerms(webToken()) }.map { it.isCurrent })
    }

    @Test
    fun webCookiesAreForwardedToCurrentQueryAndPersistedAfterEachResponse() {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json(WEB_LIST).addHeader("Set-Cookie", "SESSION=renewed-web; Path=/")
            WEB_CURRENT -> json("{}").addHeader("Set-Cookie", "term-extra=final-cookie; Path=/")
            else -> error("Unexpected term path ${request.path}")
        } }
        val token = webToken().apply { cookies["api-cookie"] = "unchanged-api" }
        val terms = success { source.getAllTerms(token) }
        assertEquals(listOf("2026-2027", "2026-2027学年", "1", "秋季", "2026-2027学年秋季", "2026-2027-1", false), termFields(terms[0]))
        assertWebPost(webRequests[0], WEB_TERMS, "/Xsxk/query/1")
        assertWebPost(webRequests[1], WEB_CURRENT, "/Xsxk/query/1")
        assertTrue(form(webRequests.first()).isEmpty())
        assertEquals(mapOf("p_pylx" to "1"), form(webRequests.last()))
        assertTrue(webRequests[1].getHeader("Cookie").orEmpty().contains("SESSION=renewed-web"))
        assertFalse(webRequests[1].getHeader("Cookie").orEmpty().contains("api-cookie"))
        assertEquals("renewed-web", token.webCookies["SESSION"])
        assertEquals("final-cookie", token.webCookies["term-extra"])
        assertEquals("unchanged-api", token.cookies["api-cookie"])
        assertEquals(2, refreshCount.get())
        assertSame(token, refreshedToken.get())
    }

    @Test
    fun graduateWebQueryUsesGraduateTypeForCurrentTerm() {
        validWebResponses()
        success { source.getAllTerms(webToken().apply { stutype = EASToken.TYPE.GRAD }) }
        assertEquals(mapOf("p_pylx" to "2"), form(webRequests.last()))
    }

    @Test
    fun webListKeepsFirstDuplicateAndSkipsRowsWithoutIdentity() {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json("""{"content":[
                {"XN":"2026-2027","XQ":"1","XQMC":"first","SFDQXQ":"0"},
                {"XN":"2026-2027","XQ":"1","XQMC":"second","SFDQXQ":"1"},
                {"XQ":"3","XQMC":"missing-year"}]}""")
            WEB_CURRENT -> json("{}")
            else -> error("Unexpected term path ${request.path}")
        } }
        assertEquals(listOf("2026-2027", "2026-2027", "1", "first", "2026-2027first", "2026-2027-1", false),
            termFields(success { source.getAllTerms(webToken()) }.single()))
    }

    @Test
    fun webStartsEagerlyWithoutInitialLoadingState() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> { hold(entered, release); json(WEB_LIST) }
            WEB_CURRENT -> json("{}")
            else -> error("Unexpected term path ${request.path}")
        } }
        assertEquals(DataState.STATE.SUCCESS, eagerTerms(null, entered, release) {
            source.getAllTerms(webToken())
        }.state)
        assertEquals(listOf(WEB_TERMS, WEB_CURRENT), webRequests.map { it.path })
    }

    @Test
    fun apiTransportFailureIsFetchFailedWithoutWebFallback() {
        apiResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val token = apiToken().apply { webCookies["SESSION"] = "unused-web" }
        val state = awaitState { source.getAllTerms(token) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertNull(state.data)
        assertTrue(apiRequests.isNotEmpty())
        assertTrue(apiRequests.all { it.path == API_TERMS })
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun primaryWebTransportFailureDoesNotAttemptLegacyFallback() {
        webResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val state = awaitState { source.getAllTerms(webToken()) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertNull(state.data)
        assertTrue(webRequests.isNotEmpty())
        assertTrue(webRequests.all { it.path == WEB_TERMS })
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun auxiliaryTransportFailureRetainsLegacyWholeQueryFailure() {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json(WEB_LIST)
            WEB_CURRENT -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else -> error("Unexpected term path ${request.path}")
        } }
        val state = awaitState { source.getAllTerms(webToken()) }
        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertNull(state.data)
        assertEquals(WEB_TERMS, webRequests.first().path)
        assertTrue(webRequests.drop(1).all { it.path == WEB_CURRENT })
        assertTrue(webRequests.size >= 2)
    }

    private fun validWebResponses(current: String = "{}") {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json(WEB_LIST)
            WEB_CURRENT -> json(current)
            else -> error("Unexpected term path ${request.path}")
        } }
    }
}
