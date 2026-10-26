package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real campus sources and refresh callbacks behind the real repository facade. */
@RunWith(AndroidJUnit4::class)
class TermRepositoryHttpContractTest : TermRepositoryContractHarness() {
    @Test
    fun regularCampusesExposeNothingOnlyOnCacheMissAndThenSaveHttpTerms() {
        listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI).forEach { campus ->
            listOf(false, true).forEach { cacheHit ->
                apiRequests.clear()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                apiResponse = { request -> when (request.path) {
                    SCORE_TERMS -> {
                        hold(entered, release)
                        html(singleTermPage("pageXnxq"))
                    }
                    TIMETABLE_TERMS -> html(singleTermPage("xnxq"))
                    else -> error("Unexpected term path ${request.path}")
                } }
                val fixture = fixture(accountToken(campus).apply { cookies["HIT"] = "local-term-hit" }, realHttp = true)
                val owner = "${campus.name}:student"
                if (cacheHit) fixture.seed(cachedRow(owner = owner))
                val observed = fixture.query()
                try {
                    fixture.awaitCacheRead()
                    assertTrue(entered.await(20, TimeUnit.SECONDS))
                    flushMain()
                    assertEquals(listOf(if (cacheHit) DataState.STATE.SUCCESS else DataState.STATE.NOTHING),
                        observed.states.map { it.state })
                    assertEquals(cacheHit, observed.states.single().fromCache)
                    release.countDown()
                    val remote = observed.await { it.state == DataState.STATE.SUCCESS && !it.fromCache }
                    fixture.awaitCacheSave()
                    assertEquals(listOf("2025-2026-1"), remote.data?.map { it.id })
                    assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
                    apiRequests.forEach { assertLegacyGet(it, requireNotNull(it.path)) }
                    assertTrue(webRequests.isEmpty())
                    assertTrue(fixture.scripted.calls.isEmpty())
                    assertEquals(listOf("1"), fixture.rows(owner).map { it.termTermCode })
                    assertEquals(2, observed.states.size)
                } finally {
                    release.countDown()
                    observed.close()
                }
            }
        }
    }

    @Test
    fun shenzhenApiHasNoInitialStateAndUsesTheOrdinaryOwnerCache() {
        listOf(false, true).forEach { cacheHit ->
            apiRequests.clear()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            apiResponse = { request ->
                assertEquals(API_TERMS, request.path)
                hold(entered, release)
                json("""{"content":[{"XN":"2025-2026","XQ":"2","XNXQMC":"春季","SFDQXQ":"1"}]}""")
            }
            val fixture = fixture(accountToken(EASToken.Campus.SHENZHEN), realHttp = true)
            if (cacheHit) fixture.seed(cachedRow(owner = "SHENZHEN:student"))
            val observed = fixture.query()
            try {
                fixture.awaitCacheRead()
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                flushMain()
                assertEquals(if (cacheHit) listOf(DataState.STATE.SUCCESS) else emptyList<DataState.STATE>(),
                    observed.states.map { it.state })
                if (cacheHit) assertTrue(observed.states.single().fromCache)
                release.countDown()
                val remote = observed.await { it.state == DataState.STATE.SUCCESS && !it.fromCache }
                fixture.awaitCacheSave()
                assertEquals("2025-2026-2", remote.data?.single()?.id)
                assertEquals(listOf(API_TERMS), apiRequests.map { it.path })
                assertEquals("bearer local-api-token", apiRequests.single().getHeader("Authorization"))
                assertEquals("06", apiRequests.single().getHeader("RoleCode"))
                assertTrue(webRequests.isEmpty())
                assertEquals("2", fixture.rows("SHENZHEN:student").single().termTermCode)
            } finally {
                release.countDown()
                observed.close()
            }
        }
    }

    @Test
    fun ordinaryShenzhenWebRouteHasNoInitialStateAndStillUsesOwnerCache() {
        listOf(false, true).forEach { cacheHit ->
            webRequests.clear()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            webResponse = { request -> when (request.path) {
                WEB_TERMS -> {
                    hold(entered, release)
                    json(WEB_LIST)
                }
                WEB_CURRENT -> json("""{"p_xn":"2026-2027","p_xq":"1"}""")
                else -> error("Unexpected Web term path ${request.path}")
            } }
            val fixture = fixture(accountToken(EASToken.Campus.SHENZHEN).apply {
                accessToken = null
                webCookies["SESSION"] = "local-web-session"
            }, realHttp = true)
            if (cacheHit) fixture.seed(cachedRow(owner = "SHENZHEN:student"))
            val observed = fixture.query()
            try {
                fixture.awaitCacheRead()
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                flushMain()
                assertEquals(if (cacheHit) listOf(DataState.STATE.SUCCESS) else emptyList<DataState.STATE>(),
                    observed.states.map { it.state })
                if (cacheHit) assertTrue(observed.states.single().fromCache)
                release.countDown()
                val remote = observed.await { it.state == DataState.STATE.SUCCESS && !it.fromCache }
                fixture.awaitCacheSave()
                assertEquals(listOf("2026-2027-1", "2025-2026-3"), remote.data?.map { it.id })
                assertEquals(listOf(WEB_TERMS, WEB_CURRENT), webRequests.map { it.path })
                assertTrue(apiRequests.isEmpty())
                assertEquals(listOf("1", "3"), fixture.rows("SHENZHEN:student").map { it.termTermCode })
            } finally {
                release.countDown()
                observed.close()
            }
        }
    }

    @Test
    fun webOnlyFacadeIgnoresBearerAndOrdinaryCacheAndPersistsItsRealCookieCallbacks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        webResponse = { request -> when (request.path) {
            WEB_TERMS -> {
                hold(entered, release)
                json(WEB_LIST).addHeader("Set-Cookie", "term-first=renewed-first; Path=/")
            }
            WEB_CURRENT -> json("""{"p_xn":"2026-2027","p_xq":"1"}""")
                .addHeader("Set-Cookie", "term-last=renewed-last; Path=/")
            else -> error("Unexpected Web term path ${request.path}")
        } }
        val fixture = fixture(accountToken(EASToken.Campus.SHENZHEN).apply {
            cookies["api-cookie"] = "untouched-api"
            webCookies["SESSION"] = "local-web-session"
        }, realHttp = true)
        fixture.seed(cachedRow(owner = "SHENZHEN:student"))
        fixture.clearSql()
        val observed = fixture.queryWeb()
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            flushMain()
            assertTrue(observed.states.isEmpty())
            release.countDown()
            val result = observed.await { it.state == DataState.STATE.SUCCESS }
            assertFalse(result.fromCache)
            assertEquals(listOf(true, false), result.data?.map { it.isCurrent })
            assertEquals(listOf(WEB_TERMS, WEB_CURRENT), webRequests.map { it.path })
            webRequests.forEach { assertWebPost(it, requireNotNull(it.path), "/Xsxk/query/1") }
            assertTrue(webRequests.last().getHeader("Cookie").orEmpty().contains("term-first=renewed-first"))
            assertTrue(apiRequests.isEmpty())
            assertTrue("Unexpected SQL for Web-only terms: ${fixture.sql}", fixture.sql.isEmpty())
            val stored = fixture.preferences.getEasToken()
            assertEquals("renewed-first", stored.webCookies["term-first"])
            assertEquals("renewed-last", stored.webCookies["term-last"])
            assertEquals("local-web-session", stored.webCookies["SESSION"])
            assertEquals("untouched-api", stored.cookies["api-cookie"])
            assertEquals("local-api-token", stored.accessToken)
            assertEquals(211L, stored.sessionGeneration)
            assertEquals("cached", fixture.rows("SHENZHEN:student").single().termName)
        } finally {
            release.countDown()
            observed.close()
        }
    }

    @Test
    fun webOnlyFacadeWithoutWebSessionRetainsItsSpecialLoginMessageAndDoesNoIo() {
        val fixture = fixture(accountToken(EASToken.Campus.SHENZHEN), realHttp = true)
        fixture.seed(cachedRow(owner = "SHENZHEN:student"))
        fixture.clearSql()
        val observed = fixture.queryWeb()
        val result = observed.await { true }
        assertEquals(DataState.STATE.NOT_LOGGED_IN, result.state)
        assertEquals("请先连接深圳 Web 教务", result.message)
        assertFalse(result.fromCache)
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
        assertTrue("Unexpected SQL without Web session: ${fixture.sql}", fixture.sql.isEmpty())
        assertEquals("cached", fixture.rows("SHENZHEN:student").single().termName)
    }

    @Test
    fun regularSourceCookieCallbacksReachRepositoryPreferencesAndTheSecondRequest() {
        listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI).forEach { campus ->
            apiRequests.clear()
            apiResponse = { request -> when (request.path) {
                SCORE_TERMS -> html(singleTermPage("pageXnxq"))
                    .addHeader("Set-Cookie", "JSESSIONID=renewed-term; Path=/")
                TIMETABLE_TERMS -> html(singleTermPage("xnxq"))
                    .addHeader("Set-Cookie", "term-extra=second-page; Path=/")
                else -> error("Unexpected term path ${request.path}")
            } }
            val fixture = fixture(accountToken(campus), realHttp = true)
            val observed = fixture.query(useCache = false)
            observed.await { it.state == DataState.STATE.SUCCESS }
            assertEquals(listOf(SCORE_TERMS, TIMETABLE_TERMS), apiRequests.map { it.path })
            assertTrue(apiRequests.last().getHeader("Cookie").orEmpty().contains("JSESSIONID=renewed-term"))
            val stored = fixture.preferences.getEasToken()
            assertEquals("renewed-term", stored.cookies["JSESSIONID"])
            assertEquals("second-page", stored.cookies["term-extra"])
            assertEquals(211L, stored.sessionGeneration)
            assertEquals(campus, stored.campus)
            assertEquals("student", stored.stuId)
            assertEquals("login-name", stored.username)
            assertTrue(fixture.sql.isEmpty())
            assertTrue(webRequests.isEmpty())
            observed.close()
        }
    }
}
