package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShenzhenTeachingBuildingsContractTest : TeachingBuildingsContractHarness() {
    @Test
    fun apiIsEagerWithoutInitialStateAndKeepsFieldOrderDuplicatesAndBlankIds() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = apiToken().apply { webCookies["SESSION"] = "local-web-session" }
        apiResponse = {
            hold(entered, release)
            json("""{"content":[{"DM":"B","MC":"教学楼乙"},7,{"DM":"A","MC":"教学楼甲"},{"DM":"B","MC":"重名乙"},{"MC":"缺少编号"}]}""")
                .addHeader("Set-Cookie", "api-refreshed=updated; Path=/")
        }

        val state = eagerResult(null, entered, release) { source.getTeachingBuildings(token) }

        assertBuildings(state, "B" to "教学楼乙", "A" to "教学楼甲", "B" to "重名乙", "" to "缺少编号")
        val request = apiRequests.single()
        assertEquals(API_PATH, request.path)
        assertEquals("POST", request.method)
        assertEquals(emptyMap<String, String>(), form(request))
        assertEquals("bearer local-api-token", request.getHeader("authorization"))
        assertEquals("06", request.getHeader("rolecode"))
        assertEquals("cn", request.getHeader("_lang"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("api-cookie=local-api-cookie"))
        assertFalse(request.getHeader("Cookie").orEmpty().contains("local-web-session"))
        assertEquals("updated", token.cookies["api-refreshed"])
        assertFalse(token.webCookies.containsKey("api-refreshed"))
        assertEquals(0, refreshCount.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiMalformedBodyKeepsHistoricalSuccessfulEmptyMapping() {
        apiResponse = { json("not-json") }

        assertBuildings(awaitState { source.getTeachingBuildings(apiToken()) })

        assertEquals(listOf(API_PATH), apiRequests.map { it.path })
        assertEquals(0, refreshCount.get())
    }

    @Test
    fun apiExpiredSessionWithoutCredentialsKeepsSuccessfulEmptyMapping() {
        apiResponse = { json("""{"code":401,"message":"unauthorized"}""", 401) }

        assertBuildings(awaitState { source.getTeachingBuildings(apiToken()) })

        assertEquals(listOf(API_PATH), apiRequests.map { it.path })
        assertEquals(0, refreshCount.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun apiNon200StillParsesContent() {
        apiResponse = { json("""{"content":[{"DM":"A","MC":"教学楼甲"}]}""", 503) }

        assertBuildings(awaitState { source.getTeachingBuildings(apiToken()) }, "A" to "教学楼甲")
    }

    @Test
    fun apiTransportFailureMapsToFetchFailedWithoutMessage() {
        apiResponse = { json("invalid-gzip").setHeader("Content-Encoding", "gzip") }

        val state = awaitState { source.getTeachingBuildings(apiToken()) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertNull(state.message)
        assertEquals(listOf(API_PATH), apiRequests.map { it.path })
    }

    @Test
    fun webIsEagerInitializesInOrderAndPropagatesOnlyWebCookiesAtEveryStep() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = webToken().apply {
            accessToken = " "
            cookies["api-cookie"] = "local-api-cookie"
        }
        webResponse = { request ->
            when (request.path) {
                WEB_INITIALIZATION_PATHS[0] -> {
                    hold(entered, release)
                    json("[]").addHeader("Set-Cookie", "context=ready; Path=/")
                }
                WEB_INITIALIZATION_PATHS[1], WEB_INITIALIZATION_PATHS[2] -> json("[]")
                WEB_PATH -> json("""[{"DM":"B","MC":"教学楼乙"},{"dm":"A","mc":"教学楼甲"},{"DM":"C"},{"DM":"","MC":"过滤"},{"DM":"B","MC":"重复保留"}]""")
                    .addHeader("Set-Cookie", "buildings=ready; Path=/")
                else -> throw AssertionError("Unexpected path ${request.path}")
            }
        }

        val state = eagerResult(null, entered, release) { source.getTeachingBuildings(token) }

        assertBuildings(state, "B" to "教学楼乙", "A" to "教学楼甲", "C" to "C", "B" to "重复保留")
        assertEquals(WEB_INITIALIZATION_PATHS + WEB_PATH, webRequests.map { it.path })
        webRequests.forEach { assertWebBuildingPost(it, requireNotNull(it.path)) }
        webRequests.drop(1).forEach {
            assertTrue(it.getHeader("Cookie").orEmpty().contains("context=ready"))
            assertFalse(it.getHeader("Cookie").orEmpty().contains("local-api-cookie"))
        }
        assertEquals("ready", token.webCookies["context"])
        assertEquals("ready", token.webCookies["buildings"])
        assertEquals(mapOf("api-cookie" to "local-api-cookie"), token.cookies)
        assertSame(token, refreshedToken.get())
        assertEquals(4, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webEmptyArrayIsSuccessful() {
        webResponse = { json("[]") }

        assertBuildings(awaitState { source.getTeachingBuildings(webToken()) })

        assertEquals(WEB_INITIALIZATION_PATHS + WEB_PATH, webRequests.map { it.path })
    }

    @Test
    fun webMalformedBodyKeepsSpecificParseFailureMessage() {
        webResponse = { request -> json(if (request.path == WEB_PATH) "{\"invalid\":" else "[]") }

        val state = awaitState { source.getTeachingBuildings(webToken()) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("教学楼列表解析失败", state.message)
    }

    @Test
    fun webNon200KeepsSpecificParseFailureMessage() {
        webResponse = { request -> json("[]", if (request.path == WEB_PATH) 503 else 200) }

        val state = awaitState { source.getTeachingBuildings(webToken()) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("教学楼列表解析失败", state.message)
    }

    @Test
    fun webExpiredInitializationStopsRemainingContextCallsButStillChecksBuildings() {
        webResponse = { json("""{"code":401,"message":"unauthorized"}""", 401) }

        val state = awaitState { source.getTeachingBuildings(webToken()) }

        assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
        assertEquals("深圳 Web 会话已失效", state.message)
        assertEquals(listOf(WEB_INITIALIZATION_PATHS.first(), WEB_PATH), webRequests.map { it.path })
        assertEquals(2, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }
}
