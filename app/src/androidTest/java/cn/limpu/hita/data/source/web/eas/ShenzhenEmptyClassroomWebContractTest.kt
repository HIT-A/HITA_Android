package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShenzhenEmptyClassroomWebContractTest : ShenzhenEmptyClassroomContractHarness() {
    @Test
    fun webIsEagerUsesOnlyLeftAndRightAndKeepsEveryParsedFieldAndCookieCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = webToken().apply {
            accessToken = " "
            cookies["api-cookie"] = "local-api-cookie"
        }
        webResponse = { request -> when (request.path) {
            LEFT_PATH -> {
                hold(entered, release)
                json("""{"list":[
                    {"DM":" B ","MC":" 教室乙 ","ZWS":"60.9","SFKJ":"1"},
                    {"dm":"A","mc":"教室甲","zws":"bad","sfjtjs":"0"},
                    {"DM":"B","MC":"乙重复"}, {"DM":"C"}, 7, {"DM":" "}
                ]}""").addHeader("Set-Cookie", "left=ready; Path=/")
            }
            RIGHT_PATH -> json("""{"content":{"list":[
                {"CDDM":"B","XQJ":"2","XJ":"3","PKBJ":"1"},
                {"cddm":"B","xqj":"1","xj":"2","jybj":"借用"},
                {"CDDM":"B","XQJ":"1","XJ":"2","PKJYBJ":"1"},
                {"CDDM":"A","XQJ":"1","XJ":"1","PKBJ":"0","JYBJ":"否","PKJYBJ":" "},
                {"CDDM":"A","XQJ":"3","XJ":"4","PKBJ":"0","PKJYBJ":"占用"},
                {"CDDM":"C","XQJ":"bad","XJ":"1","PKBJ":"1"},
                {"CDDM":"C","XQJ":"1","XJ":"bad","PKBJ":"1"},
                {"CDDM":"missing","XQJ":"1","XJ":"1","PKBJ":"1"}, 7
            ]}}""").addHeader("Set-Cookie", "right=ready; Path=/")
            else -> error("Unexpected ${request.path}")
        } }

        val state = eagerResult(entered, release) { query(token, listOf("2", "9")) }

        val occupiedB = listOf(Slot(1, 2), Slot(1, 2), Slot(2, 3))
        assertRooms(state,
            Room("B", "教室乙", 60, "1", occupiedB),
            Room("A", "教室甲", 0, "0", listOf(Slot(3, 4))),
            Room("B", "乙重复", special = null, schedule = occupiedB),
            Room("C", special = null),
        )
        assertEquals(listOf(LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
        webRequests.forEach { assertWebPost(it, 2) }
        assertTrue(webRequests.last().getHeader("Cookie").orEmpty().contains("left=ready"))
        assertEquals("ready", token.webCookies["left"])
        assertEquals("ready", token.webCookies["right"])
        assertEquals(mapOf("api-cookie" to "local-api-cookie"), token.cookies)
        assertSame(token, refreshedToken.get())
        assertEquals(2, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webWeekMaskUsesOnlyFirstUntrimmedWeekAndClampsBothEnds() {
        webResponse = { request ->
            assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
            json("[]")
        }
        val cases = listOf(
            emptyList<String>() to 1, listOf(" ") to 1, listOf(" 2 ") to 1,
            listOf("invalid") to 1, listOf("0") to 1, listOf("99") to 34, listOf("2", "9") to 2,
        )
        cases.forEach { (weeks, week) ->
            assertRooms(awaitState { query(webToken(), weeks) })
            webRequests.takeLast(2).forEach { assertWebPost(it, week) }
        }
        assertEquals(List(cases.size) { listOf(LEFT_PATH, RIGHT_PATH) }.flatten(), webRequests.map { it.path })
        assertEquals(cases.size * 2, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webValidEmptyArraysAndObjectsRemainSuccess() {
        listOf("[]", "{}", "{\"data\":[]}").forEach { body ->
            webResponse = { request ->
                assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
                json(body)
            }
            assertRooms(awaitState { query(webToken()) })
        }
        assertEquals(6, webRequests.size)
    }

    @Test
    fun webLeftAuthenticationStopsBeforeRightAndKeepsSessionMessage() {
        webResponse = { request ->
            assertEquals(LEFT_PATH, request.path)
            json("{}", 401)
        }

        assertFailure(awaitState { query(webToken()) }, DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")

        assertEquals(listOf(LEFT_PATH), webRequests.map { it.path })
        assertEquals(1, refreshCount.get())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun webRightAuthenticationStopsWithSessionMessageAndPreservesLeftCookies() {
        val token = webToken()
        webResponse = { request -> when (request.path) {
            LEFT_PATH -> json("[]").addHeader("Set-Cookie", "left=ready; Path=/")
            RIGHT_PATH -> json("{}", 403)
            else -> error("Unexpected ${request.path}")
        } }

        assertFailure(awaitState { query(token) }, DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")

        assertEquals(listOf(LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
        assertEquals("ready", token.webCookies["left"])
        assertEquals(2, refreshCount.get())
    }

    @Test
    fun webOrdinaryNon200LeftStillQueriesRightBeforeParseFailure() {
        webResponse = { request -> when (request.path) {
            LEFT_PATH -> json("""[{"DM":"A","MC":"教室甲"}]""", 503)
            RIGHT_PATH -> json("[]")
            else -> error("Unexpected ${request.path}")
        } }

        assertFailure(awaitState { query(webToken()) }, message = "空教室数据解析失败")

        assertEquals(listOf(LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
        assertEquals(2, refreshCount.get())
    }

    @Test
    fun webRightNon200RemainsParseFailureEvenWithValidRows() {
        webResponse = { request -> when (request.path) {
            LEFT_PATH -> json("""[{"DM":"A"}]""")
            RIGHT_PATH -> json("[]", 503)
            else -> error("Unexpected ${request.path}")
        } }

        assertFailure(awaitState { query(webToken()) }, message = "空教室数据解析失败")

        assertEquals(listOf(LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
    }

    @Test
    fun webMalformedLeftAndRightBodiesKeepSpecificFailureAndRequestSequence() {
        listOf(LEFT_PATH, RIGHT_PATH).forEach { badPath ->
            webRequests.clear()
            webResponse = { request ->
                assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
                json(if (request.path == badPath) "{\"invalid\":" else "[]")
            }
            assertFailure(awaitState { query(webToken()) }, message = "空教室数据解析失败")
            assertEquals(listOf(LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
        }
    }

    @Test
    fun webTransportFailureKeepsMessageAndStopsAtFailedSide() {
        listOf(LEFT_PATH, RIGHT_PATH).forEach { badPath ->
            webRequests.clear()
            refreshCount.set(0)
            webResponse = { request ->
                assertTrue(request.path == LEFT_PATH || request.path == RIGHT_PATH)
                if (request.path == badPath) badGzip() else json("[]")
            }
            val state = awaitState { query(webToken()) }
            assertEquals(DataState.STATE.FETCH_FAILED, state.state)
            assertNull(state.data)
            assertFalse(state.message.isNullOrBlank())
            assertStateMetadata(state)
            assertEquals(if (badPath == LEFT_PATH) listOf(LEFT_PATH) else listOf(LEFT_PATH, RIGHT_PATH), webRequests.map { it.path })
            assertEquals(if (badPath == LEFT_PATH) 0 else 1, refreshCount.get())
        }
    }

    @Test
    fun webLegacyCallbackCancellationMapsToFailureAndDoesNotRequestRight() {
        val token = webToken()
        val count = AtomicInteger()
        val failingSource = classroomSource {
            assertSame(token, it)
            count.incrementAndGet()
            throw CancellationException("legacy classroom callback cancelled")
        }
        webResponse = { request ->
            assertEquals(LEFT_PATH, request.path)
            json("[]").addHeader("Set-Cookie", "left=ready; Path=/")
        }

        assertFailure(awaitState { query(token, querySource = failingSource) }, message = "legacy classroom callback cancelled")

        assertEquals(listOf(LEFT_PATH), webRequests.map { it.path })
        assertEquals(1, count.get())
        assertEquals("ready", token.webCookies["left"])
    }
}
