package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyTeachingBuildingsContractTest : TeachingBuildingsContractHarness() {
    @Test
    fun benbuIsEagerWithNothingAndCombinesCampusesInOrderWithoutFilteringOrDeduplication() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = legacyToken(EASToken.Campus.BENBU)
        apiResponse = { request ->
            when (form(request)["id"]) {
                "1" -> {
                    hold(entered, release)
                    json("""[{"DM":"B","MC":"一校区乙"},{"DM":"A","MC":"一校区甲"},{"MC":"空编号保留"}]""")
                        .addHeader("Set-Cookie", "JSESSIONID=ignored-replacement; Path=/")
                }
                "2" -> json("""[{"DM":"B","MC":"二校区乙"},{"DM":"C","MC":"二校区丙"}]""")
                else -> throw AssertionError("Unexpected campus ${form(request)}")
            }
        }

        val state = eagerResult(DataState.STATE.NOTHING, entered, release) { benbuSource().getTeachingBuildings(token) }

        assertBuildings(state, "B" to "一校区乙", "A" to "一校区甲", "" to "空编号保留", "B" to "二校区乙", "C" to "二校区丙")
        assertEquals(listOf("1", "2"), apiRequests.map { form(it)["id"] })
        apiRequests.zip(listOf("1", "2")).forEach { (request, id) -> assertLegacyRequest(request, id) }
        assertEquals("local-legacy-session", token.cookies["JSESSIONID"])
        assertEquals(0, legacyCookieCallbacks.get())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun benbuNon200AndMalformedResponsesAreSuccessfulEmptyWithoutAuthMapping() {
        apiResponse = { request ->
            if (form(request)["id"] == "1") json("[]", 401) else json("<html>unexpected</html>")
        }

        assertBuildings(awaitState { benbuSource().getTeachingBuildings(legacyToken(EASToken.Campus.BENBU)) })

        assertEquals(listOf("1", "2"), apiRequests.map { form(it)["id"] })
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun benbuParserKeepsPrefixBeforeMalformedRowAndStillQueriesSecondCampus() {
        apiResponse = { request ->
            if (form(request)["id"] == "1") {
                json("""[{"DM":"A","MC":"有效前缀"},7,{"DM":"B","MC":"不会解析"}]""")
            } else {
                json("""[{"DM":"C","MC":"二校区"}]""")
            }
        }

        assertBuildings(
            awaitState { benbuSource().getTeachingBuildings(legacyToken(EASToken.Campus.BENBU)) },
            "A" to "有效前缀", "C" to "二校区",
        )
    }

    @Test
    fun benbuTransportFailureAbortsBeforeSecondCampusAndKeepsErrorMessage() {
        apiResponse = { json("invalid-gzip").setHeader("Content-Encoding", "gzip") }

        val state = awaitState { benbuSource().getTeachingBuildings(legacyToken(EASToken.Campus.BENBU)) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertTrue(!state.message.isNullOrBlank())
        assertEquals(listOf("1"), apiRequests.map { form(it)["id"] })
    }

    @Test
    fun weihaiIsEagerWithNothingAndUsesValid01WithoutFallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        apiResponse = {
            hold(entered, release)
            json("""[{"DM":"B","MC":"首个乙"},{"DM":" ","MC":"空编号过滤"},{"DM":"B","MC":"重复乙过滤"},{"DM":"A","MC":"甲"}]""")
        }

        val state = eagerResult(DataState.STATE.NOTHING, entered, release) {
            weihaiSource().getTeachingBuildings(legacyToken(EASToken.Campus.WEIHAI))
        }

        assertBuildings(state, "B" to "首个乙", "A" to "甲")
        assertLegacyRequest(apiRequests.single(), "01")
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun weihaiFallsBackFromOnlyBlankIdsThenMergesOneAndTwoWithFirstIdWinning() {
        apiResponse = { request ->
            when (form(request)["id"]) {
                "01" -> json("""[{"MC":"无编号"},{"DM":" ","MC":"空编号"}]""")
                "1" -> json("""[{"DM":"B","MC":"首个乙"},{"DM":"A","MC":"甲"}]""")
                "2" -> json("""[{"DM":"B","MC":"重复乙"},{"DM":"C","MC":"丙"}]""")
                else -> throw AssertionError("Unexpected campus ${form(request)}")
            }
        }

        assertBuildings(
            awaitState { weihaiSource().getTeachingBuildings(legacyToken(EASToken.Campus.WEIHAI)) },
            "B" to "首个乙", "A" to "甲", "C" to "丙",
        )

        assertEquals(listOf("01", "1", "2"), apiRequests.map { form(it)["id"] })
        apiRequests.zip(listOf("01", "1", "2")).forEach { (request, id) -> assertLegacyRequest(request, id) }
    }

    @Test
    fun weihaiNon200AndMalformedFallbacksRemainSuccessfulEmpty() {
        apiResponse = { request ->
            when (form(request)["id"]) {
                "01" -> json("[]", 401)
                "1" -> json("<html>unexpected</html>")
                "2" -> json("[]", 503)
                else -> throw AssertionError("Unexpected campus ${form(request)}")
            }
        }

        assertBuildings(awaitState { weihaiSource().getTeachingBuildings(legacyToken(EASToken.Campus.WEIHAI)) })

        assertEquals(listOf("01", "1", "2"), apiRequests.map { form(it)["id"] })
        assertEquals(0, legacyCookieCallbacks.get())
    }

    @Test
    fun weihaiTransportFailureAbortsBeforeFallbackAndKeepsErrorMessage() {
        apiResponse = { json("invalid-gzip").setHeader("Content-Encoding", "gzip") }

        val state = awaitState { weihaiSource().getTeachingBuildings(legacyToken(EASToken.Campus.WEIHAI)) }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertTrue(!state.message.isNullOrBlank())
        assertEquals(listOf("01"), apiRequests.map { form(it)["id"] })
    }
}
