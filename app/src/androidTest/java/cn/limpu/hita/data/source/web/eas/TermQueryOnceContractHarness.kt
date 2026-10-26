package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame

/** New API contracts reuse the frozen HTTP fixtures, never redirect the old test entry points. */
abstract class TermQueryOnceContractHarness : TermQueryContractHarness() {
    protected fun termService(
        campus: EASToken.Campus,
        callback: (EASToken) -> Unit = {},
    ): EASService = when (campus) {
        EASToken.Campus.SHENZHEN -> shenzhenTermService(callback)
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
    }

    protected fun shenzhenTermService(callback: (EASToken) -> Unit = {}) = EASWebSource(
        onTokenRefreshed = callback,
        apiHostOverride = api.url("/").toString(),
        webHostOverride = web.url("/").toString(),
    )

    protected fun graduateTermToken() = legacyToken(EASToken.Campus.BENBU).apply {
        stutype = EASToken.TYPE.GRAD
    }

    protected fun regularSelector(path: String?) = when (path) {
        SCORE_TERMS -> "pageXnxq"
        TIMETABLE_TERMS -> "xnxq"
        else -> error("Unexpected regular term path $path")
    }

    protected fun regularSuccess() {
        apiResponse = { html(singleTermPage(regularSelector(it.path))) }
    }

    protected fun webSuccess(current: String = "{}") {
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> json(WEB_LIST)
            WEB_CURRENT -> json(current)
            else -> error("Unexpected web term path ${request.path}")
        } }
    }

    /** A deterministic body/transport decoding error, without platform-dependent retry counts. */
    protected fun invalidGzip(): MockResponse = json("invalid-gzip").setHeader("Content-Encoding", "gzip")

    protected data class MatchedTerms(
        val state: DataState<List<TermItem>>,
        val token: EASToken,
        val apiRequests: List<RecordedRequest>,
        val webRequests: List<RecordedRequest>,
        val callbackCount: Int,
    )

    protected suspend fun matchingLegacy(
        campus: EASToken.Campus = EASToken.Campus.SHENZHEN,
        webOnly: Boolean = false,
        token: () -> EASToken,
    ): MatchedTerms {
        val oldToken = token().apply { sessionGeneration = 71L }
        val onceToken = token().apply { sessionGeneration = 71L }
        val oldCallbacks = CopyOnWriteArrayList<EASToken>()
        val onceCallbacks = CopyOnWriteArrayList<EASToken>()
        // Separate sources avoid accidental reuse of mutable authentication/session state.
        val oldSource = termService(campus) { oldCallbacks.add(it) }
        val onceSource = termService(campus) { onceCallbacks.add(it) }
        val apiStart = apiRequests.size
        val webStart = webRequests.size
        val oldState = awaitState {
            if (webOnly) (oldSource as EASWebSource).getShenzhenWebTerms(oldToken)
            else oldSource.getAllTerms(oldToken)
        }
        val apiMiddle = apiRequests.size
        val webMiddle = webRequests.size
        val onceState = withContext(Dispatchers.Main) {
            if (webOnly) (onceSource as EASWebSource).getShenzhenWebTermsOnce(onceToken)
            else onceSource.getAllTermsOnce(onceToken)
        }
        assertEquals(oldState.state, onceState.state)
        assertEquals(oldState.message, onceState.message)
        assertEquals(oldState.data?.map(::termFields), onceState.data?.map(::termFields))
        assertEquals(oldState.listAction, onceState.listAction)
        assertEquals(oldState.fromCache, onceState.fromCache)
        assertEquals(oldState.stateRetried, onceState.stateRetried)
        assertEquals(oldToken.cookies, onceToken.cookies)
        assertEquals(oldToken.webCookies, onceToken.webCookies)
        assertEquals(71L, oldToken.sessionGeneration)
        assertEquals(71L, onceToken.sessionGeneration)
        assertEquals(oldCallbacks.size, onceCallbacks.size)
        oldCallbacks.forEach { assertSame(oldToken, it); assertEquals(71L, it.sessionGeneration) }
        onceCallbacks.forEach { assertSame(onceToken, it); assertEquals(71L, it.sessionGeneration) }
        val onceApiRequests = apiRequests.drop(apiMiddle)
        val onceWebRequests = webRequests.drop(webMiddle)
        assertEquals(apiRequests.subList(apiStart, apiMiddle).map(::requestFields), onceApiRequests.map(::requestFields))
        assertEquals(webRequests.subList(webStart, webMiddle).map(::requestFields), onceWebRequests.map(::requestFields))
        return MatchedTerms(onceState, onceToken, onceApiRequests, onceWebRequests, onceCallbacks.size)
    }

    protected fun assertTermSuccess(result: MatchedTerms, vararg expectedIds: String): List<TermItem> {
        assertEquals(result.state.message, DataState.STATE.SUCCESS, result.state.state)
        val terms = requireNotNull(result.state.data)
        assertEquals(expectedIds.toList(), terms.map { it.id })
        assertEquals(DataState.LIST_ACTION.REPLACE_ALL, result.state.listAction)
        assertFalse(result.state.fromCache)
        assertEquals(DataState.STATE.NOTHING, result.state.stateRetried)
        return terms
    }

    protected fun assertTermFailure(result: MatchedTerms, expected: DataState.STATE, message: String? = null) {
        assertEquals(expected, result.state.state)
        assertEquals(null, result.state.data)
        if (message != null) assertEquals(message, result.state.message)
    }

    protected fun assertTrace(result: MatchedTerms, apiPaths: List<String> = emptyList(), webPaths: List<String> = emptyList()) {
        assertEquals(apiPaths, result.apiRequests.map { it.path })
        assertEquals(webPaths, result.webRequests.map { it.path })
    }

    private fun requestFields(request: RecordedRequest) = listOf(
        request.method, request.path, request.body.clone().readUtf8(),
        request.getHeader("Authorization"), request.getHeader("RoleCode"), request.getHeader("_lang"),
        request.getHeader("Cookie"), request.getHeader("Referer"), request.getHeader("Origin"),
        request.getHeader("Content-Type"), request.getHeader("Accept"), request.getHeader("Accept-Language"),
        request.getHeader("User-Agent"),
    )
}
