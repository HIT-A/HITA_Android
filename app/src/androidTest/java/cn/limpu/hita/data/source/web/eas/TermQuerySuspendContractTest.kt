package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Once entry points run on Main and must preserve the frozen legacy HTTP contracts. */
@RunWith(AndroidJUnit4::class)
class TermQuerySuspendContractTest : TermQueryOnceContractHarness() {
    @Test
    fun apiPreservesAllFieldsDuplicatesOrderAndBearerPriority() = runBlocking {
        apiResponse = { json("""{"content":[null,5,{},
            {"XN":"2025-2026","XQ":"2","XNXQMC":"春季","SFDQXQ":"1"},
            {"XN":"2025-2026","XQ":"2","XNXQMC":"重复春季","SFDQXQ":"true"}]}""") }
        val result = matchingLegacy { apiToken().apply { webCookies["SESSION"] = "unused-web" } }
        val terms = assertTermSuccess(result, "-", "2025-2026-2", "2025-2026-2")
        assertEquals(listOf("2025-2026", "2025-2026", "2", "春季", "2025-2026 春季", "2025-2026-2", true), termFields(terms[1]))
        assertEquals(listOf(false, true, false), terms.map { it.isCurrent })
        assertTrace(result, apiPaths = listOf(API_TERMS))
        assertEquals("bearer local-api-token", result.apiRequests.single().getHeader("Authorization"))
        assertEquals("06", result.apiRequests.single().getHeader("RoleCode"))
        assertTrue(form(result.apiRequests.single()).isEmpty())
    }

    @Test
    fun apiEmptyArrayRemainsSuccessWithoutSwitchingToWeb() = runBlocking {
        apiResponse = { json("""{"content":[]}""") }
        val result = matchingLegacy { apiToken().apply { webCookies["SESSION"] = "unused-web" } }
        assertTermSuccess(result)
        assertTrace(result, apiPaths = listOf(API_TERMS))
    }

    @Test
    fun apiMalformedContentAndAuthenticationErrorsKeepTheirLegacyClassification() = runBlocking {
        listOf("not-json" to 200, "{}" to 200, """{"content":null}""" to 200,
            """{"code":401}""" to 401, """{"code":403}""" to 403).forEach { (body, status) ->
            apiResponse = { json(body, status) }
            val result = matchingLegacy { apiToken().apply { webCookies["SESSION"] = "unused-web" } }
            assertTermFailure(result, DataState.STATE.NOT_LOGGED_IN)
            assertTrace(result, apiPaths = listOf(API_TERMS))
        }
    }

    @Test
    fun apiHttpErrorWithValidContentStillReturnsItsTerms() = runBlocking {
        apiResponse = { json("""{"content":[{"XN":"2025-2026","XQ":"2","XNXQMC":"春季","SFDQXQ":"1"}]}""", 500) }
        val result = matchingLegacy(token = ::apiToken)
        assertTrue(assertTermSuccess(result, "2025-2026-2").single().isCurrent)
        assertTrace(result, apiPaths = listOf(API_TERMS))
    }

    @Test
    fun apiBodyTransportFailureRemainsFetchFailedWithoutFallback() = runBlocking {
        apiResponse = { invalidGzip() }
        val result = matchingLegacy { apiToken().apply { webCookies["SESSION"] = "unused-web" } }
        assertTermFailure(result, DataState.STATE.FETCH_FAILED)
        assertTrace(result, apiPaths = listOf(API_TERMS))
    }

    @Test
    fun apiCookieUpdatesPreserveTokenGenerationWithoutWebCallback() = runBlocking {
        apiResponse = { json("""{"content":[]}""").addHeader("Set-Cookie", "api-cookie=renewed-api; Path=/") }
        val result = matchingLegacy { apiToken().apply { webCookies["SESSION"] = "untouched-web" } }
        assertTermSuccess(result)
        assertEquals("renewed-api", result.token.cookies["api-cookie"])
        assertEquals("untouched-web", result.token.webCookies["SESSION"])
        assertEquals(0, result.callbackCount)
    }

    @Test
    fun absentSessionStillMakesTheHistoricalApiRequest() = runBlocking {
        apiResponse = { json("{}") }
        val result = matchingLegacy { EASToken() }
        assertTermFailure(result, DataState.STATE.NOT_LOGGED_IN)
        assertTrace(result, apiPaths = listOf(API_TERMS))
        assertEquals("bearer null", result.apiRequests.single().getHeader("Authorization"))
    }

    @Test
    fun webSelectionCurrentTermOverridesDropdownAndUsesBothPublicOnceEntries() = runBlocking {
        webSuccess("""{"p_xn":"2026-2027","p_xq":"1"}""")
        listOf(false, true).forEach { webOnly ->
            val result = matchingLegacy(webOnly = webOnly, token = ::webToken)
            val terms = assertTermSuccess(result, "2026-2027-1", "2025-2026-3")
            assertEquals(listOf(true, false), terms.map { it.isCurrent })
            assertEquals(listOf("2026-2027", "2026-2027学年", "1", "秋季", "2026-2027学年秋季", "2026-2027-1", true), termFields(terms[0]))
            assertTrace(result, webPaths = listOf(WEB_TERMS, WEB_CURRENT))
            result.webRequests.forEach { assertWebPost(it, requireNotNull(it.path), "/Xsxk/query/1") }
            assertEquals(mapOf("p_pylx" to "1"), form(result.webRequests.last()))
        }
    }

    @Test
    fun webFallbackPreservesRequestOrderRefererAndDropdownFlags() = runBlocking {
        listOf("not-json" to 200, "{}" to 503).forEach { (body, status) ->
            webResponse = { request -> when (request.path) {
                WEB_TERMS -> json(body, status)
                WEB_FALLBACK -> json(WEB_LIST)
                WEB_CURRENT -> json("{}")
                else -> error("Unexpected path ${request.path}")
            } }
            val result = matchingLegacy(token = ::webToken)
            assertEquals(listOf(false, true), assertTermSuccess(result, "2026-2027-1", "2025-2026-3").map { it.isCurrent })
            assertTrace(result, webPaths = listOf(WEB_TERMS, WEB_FALLBACK, WEB_CURRENT))
            assertWebPost(result.webRequests[1], WEB_FALLBACK, "/authentication/main")
        }
    }

    @Test
    fun webPrimaryAuthenticationStopsBeforeFallbackForStatusAndLoginHtml() = runBlocking {
        listOf("""{"code":401}""" to 401,
            "<html><title>统一身份认证</title><form action='/login'><input name='password'></form></html>" to 200
        ).forEach { (body, status) ->
            webResponse = { json(body, status) }
            val result = matchingLegacy(token = ::webToken)
            assertTermFailure(result, DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
            assertTrace(result, webPaths = listOf(WEB_TERMS))
        }
    }

    @Test
    fun webFallbackAuthenticationAndServerErrorsStopBeforeCurrentQuery() = runBlocking {
        listOf(403, 500).forEach { status ->
            webResponse = { request -> when (request.path) {
                WEB_TERMS -> json("{}")
                WEB_FALLBACK -> if (status == 403) json("""{"code":403}""", status) else json(WEB_LIST, status)
                else -> error("Unexpected path ${request.path}")
            } }
            val result = matchingLegacy(token = ::webToken)
            assertTermFailure(result, if (status == 403) DataState.STATE.NOT_LOGGED_IN else DataState.STATE.FETCH_FAILED)
            assertTrace(result, webPaths = listOf(WEB_TERMS, WEB_FALLBACK))
        }
    }

    @Test
    fun webEmptyDropdownsRemainFailureNotEmptySuccess() = runBlocking {
        webResponse = { json("[]") }
        val result = matchingLegacy(token = ::webToken)
        assertTermFailure(result, DataState.STATE.FETCH_FAILED, "学期列表解析失败")
        assertTrace(result, webPaths = listOf(WEB_TERMS, WEB_FALLBACK))
    }

    @Test
    fun webAuxiliaryUnknownMalformedAndAuthenticationKeepTheValidDropdown() = runBlocking {
        listOf("""{"p_xn":"2099-2100","p_xq":"1"}""" to 200,
            "not-json" to 200, """{"code":401}""" to 401).forEach { (body, status) ->
            webResponse = { request -> when (request.path) {
                WEB_TERMS -> json(WEB_LIST)
                WEB_CURRENT -> json(body, status)
                else -> error("Unexpected path ${request.path}")
            } }
            val result = matchingLegacy(token = ::webToken)
            assertEquals(listOf(false, true), assertTermSuccess(result, "2026-2027-1", "2025-2026-3").map { it.isCurrent })
            assertTrace(result, webPaths = listOf(WEB_TERMS, WEB_CURRENT))
        }
    }

    @Test
    fun webAuxiliaryNonAuthHttpErrorStillUsesItsCurrentFields() = runBlocking {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json(WEB_LIST)
            WEB_CURRENT -> json("""{"p_xn":"2026-2027","p_xq":"1"}""", 500)
            else -> error("Unexpected path ${request.path}")
        } }
        val result = matchingLegacy(token = ::webToken)
        assertEquals(listOf(true, false), assertTermSuccess(result, "2026-2027-1", "2025-2026-3").map { it.isCurrent })
    }

    @Test
    fun webPrimaryAndAuxiliaryTransportErrorsKeepWholeQueryFailure() = runBlocking {
        listOf(WEB_TERMS, WEB_CURRENT).forEach { failedPath ->
            webResponse = { request -> when (request.path) {
                failedPath -> invalidGzip()
                WEB_TERMS -> json(WEB_LIST)
                else -> error("Unexpected path ${request.path}")
            } }
            val result = matchingLegacy(token = ::webToken)
            assertTermFailure(result, DataState.STATE.FETCH_FAILED)
            assertTrace(result, webPaths = if (failedPath == WEB_TERMS) listOf(WEB_TERMS) else listOf(WEB_TERMS, WEB_CURRENT))
        }
    }

    @Test
    fun webCookieCallbacksKeepIdentityGenerationAndForwardEachRenewal() = runBlocking {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json(WEB_LIST).addHeader("Set-Cookie", "SESSION=renewed-web; Path=/")
            WEB_CURRENT -> json("{}").addHeader("Set-Cookie", "term-extra=final-cookie; Path=/")
            else -> error("Unexpected path ${request.path}")
        } }
        val result = matchingLegacy { webToken().apply { cookies["api-cookie"] = "untouched-api" } }
        assertTermSuccess(result, "2026-2027-1", "2025-2026-3")
        assertEquals(2, result.callbackCount)
        assertEquals("renewed-web", result.token.webCookies["SESSION"])
        assertEquals("final-cookie", result.token.webCookies["term-extra"])
        assertEquals("untouched-api", result.token.cookies["api-cookie"])
        assertTrue(result.webRequests.last().getHeader("Cookie").orEmpty().contains("SESSION=renewed-web"))
        assertFalse(result.webRequests.last().getHeader("Cookie").orEmpty().contains("api-cookie"))
    }

    @Test
    fun directWebOnceRejectsMissingSessionWithoutHttp() = runBlocking {
        val result = matchingLegacy(webOnly = true, token = ::apiToken)
        assertTermFailure(result, DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务")
        assertTrace(result)
    }

    @Test
    fun webGraduateRequestKeepsGraduateTypeInAuxiliaryForm() = runBlocking {
        webSuccess()
        val result = matchingLegacy { webToken().apply { stutype = EASToken.TYPE.GRAD } }
        assertTermSuccess(result, "2026-2027-1", "2025-2026-3")
        assertEquals(mapOf("p_pylx" to "2"), form(result.webRequests.last()))
    }

    @Test
    fun regularCampusesKeepMergeOrderCurrentFlagsAndScoreLabels() = runBlocking {
        apiResponse = { request -> when (request.path) {
            SCORE_TERMS -> html(termPage("pageXnxq", """
                <option value='2025-20263'>2025-2026夏季学期</option>
                <option value='2025-20262' selected>2025-2026春季学期</option>
            """))
            TIMETABLE_TERMS -> html(termPage("xnxq", """
                <option value='2025-20262' selected>课表别名</option>
                <option value='2024-20251'>2024-2025秋季学期</option>
            """))
            else -> error("Unexpected path ${request.path}")
        } }
        regularCampuses.forEach { campus ->
            val result = matchingLegacy(campus) { legacyToken(campus) }
            val terms = assertTermSuccess(result, "2024-2025-1", "2025-2026-2", "2025-2026-3")
            assertEquals(listOf(false, true, false), terms.map { it.isCurrent })
            assertEquals("2025-2026 春季学期", terms[1].name)
            assertTrace(result, apiPaths = listOf(SCORE_TERMS, TIMETABLE_TERMS))
            result.apiRequests.forEach { assertLegacyGet(it, requireNotNull(it.path)) }
        }
    }

    @Test
    fun regularCampusesKeepEitherValidPageWhenTheOtherSessionExpires() = runBlocking {
        regularCampuses.forEach { campus ->
            listOf(SCORE_TERMS, TIMETABLE_TERMS).forEach { failedPath ->
                apiResponse = { request -> if (request.path == failedPath) html("<input name='password'>", 401)
                    else html(singleTermPage(regularSelector(request.path))) }
                val result = matchingLegacy(campus) { legacyToken(campus) }
                assertTermSuccess(result, "2025-2026-1")
                assertTrace(result, apiPaths = listOf(SCORE_TERMS, TIMETABLE_TERMS))
            }
        }
    }

    @Test
    fun regularCampusesPreserveAuthMissingSelectorAndServerErrorClassification() = runBlocking {
        regularCampuses.forEach { campus ->
            listOf(200, 401, 503).forEach { status ->
                apiResponse = { html("no selector", status) }
                val result = matchingLegacy(campus) { legacyToken(campus) }
                assertTermFailure(result, DataState.STATE.NOT_LOGGED_IN)
                assertTrace(result, apiPaths = listOf(SCORE_TERMS, TIMETABLE_TERMS))
            }
        }
    }

    @Test
    fun regularCampusesPreserveInvalidOptionAndTransportFailure() = runBlocking {
        regularCampuses.forEach { campus ->
            listOf(false, true).forEach { transport ->
                apiResponse = { request -> if (transport) invalidGzip()
                    else html(termPage(regularSelector(request.path), "<option value='invalid'>请选择</option>")) }
                val result = matchingLegacy(campus) { legacyToken(campus) }
                assertTermFailure(result, DataState.STATE.FETCH_FAILED, "未获取到学期列表")
                assertTrace(result, apiPaths = listOf(SCORE_TERMS, TIMETABLE_TERMS))
            }
        }
    }

    @Test
    fun regularCookieCallbacksKeepIdentityGenerationAndFeedTheSecondRequest() = runBlocking {
        apiResponse = { request -> html(singleTermPage(regularSelector(request.path))).addHeader("Set-Cookie",
            if (request.path == SCORE_TERMS) "JSESSIONID=renewed-term; Path=/" else "term-extra=second-page; Path=/") }
        regularCampuses.forEach { campus ->
            val result = matchingLegacy(campus) { legacyToken(campus) }
            assertTermSuccess(result, "2025-2026-1")
            assertEquals(2, result.callbackCount)
            assertEquals("renewed-term", result.token.cookies["JSESSIONID"])
            assertEquals("second-page", result.token.cookies["term-extra"])
            assertTrue(result.apiRequests.last().getHeader("Cookie").orEmpty().contains("JSESSIONID=renewed-term"))
        }
    }

    @Test
    fun weihaiKeepsOpenedSummerButFiltersFutureAcademicYears(): Unit = runBlocking {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val current = "$year-${year + 1}"
        val future = "${year + 5}-${year + 6}"
        apiResponse = { request -> html(termPage(regularSelector(request.path), """
            <option value='${current}1' selected>${current}秋季学期</option>
            <option value='${current}3'>${current}夏季学期</option>
            <option value='${future}1'>${future}秋季学期</option>
        """)) }
        val result = matchingLegacy(EASToken.Campus.WEIHAI) { legacyToken(EASToken.Campus.WEIHAI) }
        assertTermSuccess(result, "$current-1", "$current-3")
    }

    @Test
    fun weihaiAllFutureUnmarkedTermsRetainTheOriginalNonemptyList() = runBlocking {
        val year = Calendar.getInstance().get(Calendar.YEAR) + 5
        val future = "$year-${year + 1}"
        apiResponse = { request -> html(termPage(regularSelector(request.path), """
            <option value='invalid' selected>请选择</option>
            <option value='${future}1'>${future}秋季学期</option>
            <option value='${future}3'>${future}夏季学期</option>
        """)) }
        val result = matchingLegacy(EASToken.Campus.WEIHAI) { legacyToken(EASToken.Campus.WEIHAI) }
        assertTrue(assertTermSuccess(result, "$future-1", "$future-3").none { it.isCurrent })
    }

    @Test
    fun graduateSemesterJsonKeepsCurrentFirstOrderAndDoesNotPersistResponseCookies() = runBlocking {
        apiResponse = { json("""{"data":[
            {"xnxq":"2026-20271"},
            {"xnxq":"2025-20262","current":true}
        ]}""").addHeader("Set-Cookie", "JSESSIONID=not-adopted; Path=/") }
        val result = matchingLegacy(EASToken.Campus.BENBU, token = ::graduateTermToken)
        val terms = assertTermSuccess(result, "2025-2026-2", "2026-2027-1")
        assertEquals(listOf(true, false), terms.map { it.isCurrent })
        assertEquals("local-term-session", result.token.cookies["JSESSIONID"])
        assertEquals(0, result.callbackCount)
        assertTrace(result, apiPaths = listOf(GRADUATE_TERMS))
        assertEquals("POST", result.apiRequests.single().method)
    }

    @Test
    fun graduateEmptySemesterUsesRedirectedHtmlAndMergesBridgeCookies() = runBlocking {
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
        val result = matchingLegacy(EASToken.Campus.BENBU, token = ::graduateTermToken)
        assertEquals(listOf(false, true), assertTermSuccess(result, "2025-2026-3", "2025-2026-1").map { it.isCurrent })
        assertTrace(result, apiPaths = listOf(GRADUATE_TERMS, GRADUATE_BRIDGE, GRADUATE_PAGE))
        assertEquals("graduate-course-session", result.token.cookies["JSESSIONID"])
        assertEquals("from-redirect", result.token.cookies["bridge-session"])
        assertEquals(0, result.callbackCount)
    }

    @Test
    fun graduateMalformedSemesterStillUsesHtmlAndEmptyHtmlHasSpecificFailure() = runBlocking {
        listOf(false, true).forEach { emptyHtml ->
            apiResponse = { request -> when (request.path) {
                GRADUATE_TERMS -> json("not-json")
                GRADUATE_BRIDGE -> html(if (emptyHtml) "no terms" else singleTermPage("xnxq"))
                else -> error("Unexpected graduate path ${request.path}")
            } }
            val result = matchingLegacy(EASToken.Campus.BENBU, token = ::graduateTermToken)
            if (emptyHtml) assertTermFailure(result, DataState.STATE.FETCH_FAILED, "未获取到研究生学期列表")
            else assertTermSuccess(result, "2025-2026-1")
            assertTrace(result, apiPaths = listOf(GRADUATE_TERMS, GRADUATE_BRIDGE))
        }
    }

    @Test
    fun graduateSemesterHttpAndTransportFailuresDoNotEnterTheBridge() = runBlocking {
        listOf(401, 503, -1).forEach { status ->
            apiResponse = { if (status == -1) invalidGzip() else json("[]", status) }
            val result = matchingLegacy(EASToken.Campus.BENBU, token = ::graduateTermToken)
            assertTermFailure(result, DataState.STATE.FETCH_FAILED, if (status == -1) null else "HTTP $status")
            assertTrace(result, apiPaths = listOf(GRADUATE_TERMS))
        }
    }

    @Test
    fun graduateBridgeHttpAndTransportFailuresRemainWholeQueryFailures() = runBlocking {
        listOf(false, true).forEach { transport ->
            apiResponse = { request -> when (request.path) {
                GRADUATE_TERMS -> json("[]")
                GRADUATE_BRIDGE -> if (transport) invalidGzip() else html("unavailable", 503)
                else -> error("Unexpected graduate path ${request.path}")
            } }
            val result = matchingLegacy(EASToken.Campus.BENBU, token = ::graduateTermToken)
            assertTermFailure(result, DataState.STATE.FETCH_FAILED, if (transport) null else "研究生课表桥接失败 HTTP 503")
            assertTrace(result, apiPaths = listOf(GRADUATE_TERMS, GRADUATE_BRIDGE))
        }
    }

    @Test
    fun graduateWrongHostRedirectRetainsTheExistingRejection() = runBlocking {
        val alternateHost = if (api.url("/").host == "127.0.0.1") "localhost" else "127.0.0.1"
        webResponse = { html(singleTermPage("xnxq")) }
        apiResponse = { request -> when (request.path) {
            GRADUATE_TERMS -> json("[]")
            GRADUATE_BRIDGE -> MockResponse().setResponseCode(302)
                .addHeader("Location", web.url(GRADUATE_PAGE).newBuilder().host(alternateHost).build().toString())
            else -> error("Unexpected graduate path ${request.path}")
        } }
        val result = matchingLegacy(EASToken.Campus.BENBU, token = ::graduateTermToken)
        assertTermFailure(result, DataState.STATE.FETCH_FAILED, "研究生课表会话建立失败")
        assertTrace(result, apiPaths = listOf(GRADUATE_TERMS, GRADUATE_BRIDGE), webPaths = listOf(GRADUATE_PAGE))
    }

    private val regularCampuses = listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI)
}
