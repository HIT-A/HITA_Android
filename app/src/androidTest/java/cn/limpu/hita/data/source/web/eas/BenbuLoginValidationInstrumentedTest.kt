package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenbuLoginValidationInstrumentedTest {
    private val server = MockWebServer()
    private lateinit var source: BenbuEASWebSource

    @Before fun setUp() {
        server.start()
        source = BenbuEASWebSource(
            teachingBuildingsHostOverride = null,
            onCookiesUpdated = null,
            loginHostOverride = server.url("/").toString(),
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun loginFormReturning200IsNotASuccessfulLogin() {
        server.enqueue(MockResponse().setBody("<html><title>统一身份认证</title><form action='/login'><input name='password'></form></html>"))
        assertRejected(login())
    }

    @Test fun redirectToLoginReturning200IsRejected() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/authserver/login"))
        server.enqueue(MockResponse().setBody("<html><title>登录</title><input name='password'></html>"))
        assertRejected(login())
    }

    @Test fun javascriptChallengeDoesNotCreateAToken() {
        server.enqueue(MockResponse().setBody("<html><title>Just a moment</title><body>Please enable JavaScript</body></html>"))
        assertRejected(login())
    }

    @Test fun unauthorizedAndServerFailuresAreRejected() {
        for (status in listOf(401, 403, 500)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("[]"))
            assertRejected(login())
        }
    }

    @Test fun teachingBuildingJsonKeepsLoginWorkingAndSavesRefreshedCookie() {
        server.enqueue(MockResponse().setBody("""[{"id":"1","name":"正心楼"}]""")
            .addHeader("Set-Cookie", "JSESSIONID=refreshed; Path=/"))
        val result = login()
        assertEquals(DataState.STATE.SUCCESS, result.state)
        assertEquals(EASToken.Campus.BENBU, result.data?.campus)
        assertEquals("refreshed", result.data?.cookies?.get("JSESSIONID"))
        assertEquals("test-hit", result.data?.cookies?.get("HIT"))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertTrue(request.path!!.startsWith("/kjscx/queryJxlListBySjid"))
    }

    @Test fun emptyBuildingListStillAllowsAnAuthenticatedLogin() {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(DataState.STATE.SUCCESS, login().state)
    }

    private fun assertRejected(result: DataState<EASToken>) {
        assertEquals(DataState.STATE.FETCH_FAILED, result.state)
        assertNull(result.data)
    }

    private fun login(): DataState<EASToken> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        var result: DataState<EASToken>? = null
        lateinit var live: LiveData<DataState<EASToken>>
        val observer = Observer<DataState<EASToken>> {
            result = it
            latch.countDown()
        }
        instrumentation.runOnMainSync {
            live = source.login("""{"JSESSIONID":"test-session","HIT":"test-hit"}""", "", null)
            live.observeForever(observer)
        }
        try {
            assertTrue("Login response was not delivered", latch.await(10, TimeUnit.SECONDS))
            return requireNotNull(result)
        } finally {
            instrumentation.runOnMainSync { live.removeObserver(observer) }
        }
    }
}
