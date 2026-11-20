package cn.limpu.hita.data.repository.geniusta

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cn.limpu.hita.data.source.web.geniusta.GeniusTaAssignment
import cn.limpu.hita.data.source.web.geniusta.GeniusTaConnection
import cn.limpu.hita.data.source.web.geniusta.GeniusTaSession
import cn.limpu.hita.data.source.web.geniusta.GeniusTaWebSource
import com.google.gson.JsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeniusTaRepositoryTest {
    private val accountId = "test-account-a"
    private val sessionId = "test-session"
    private val cookieValue = "synthetic-cookie"
    private val cookie = "gta_prod_session_$sessionId=$cookieValue"
    private val previous = GeniusTaAssignment("cached-assignment", "Synthetic Cache", "Synthetic Course", 1L)
    private var disk: String? = null
    private var refuseCommit = false
    private fun store() = GeniusTaStore({ disk }) { next ->
        if (refuseCommit) false else { disk = next; true }
    }

    @Before fun immediateLiveData() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
    }

    @After fun restoreLiveData() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test fun loginVerifiesIdentityBeforeReadingAndPersistsOnlyMatchedCredentials() = runBlocking {
        val paths = mutableListOf<String>()
        val password = "synthetic-test-password"
        val repository = repository { request ->
            assertEquals("https", request.url.scheme)
            assertEquals("geniusta.hitsz.edu.cn", request.url.host)
            assertEquals(10086, request.url.port)
            assertEquals("application/json", request.header("Accept"))
            val path = request.url.encodedPath
            paths += path
            if (path == "/api/v1/auth/login") {
                assertEquals("POST", request.method)
                assertEquals("https://geniusta.hitsz.edu.cn:10086", request.header("Origin"))
                assertEquals("https://geniusta.hitsz.edu.cn:10086/login", request.header("Referer"))
                assertNull(request.header("Cookie"))
                assertNull(request.header("X-GeniusTA-Session"))
                val body = Buffer().apply { requireNotNull(request.body).writeTo(this) }
                val payload = JsonParser().parse(body.readUtf8()).asJsonObject
                assertEquals("synthetic-test-user", payload["username"].asString)
                assertEquals(password, payload["password"].asString)
                loginResponse(request, accountId)
            } else {
                assertEquals("GET", request.method)
                assertNull(request.header("Origin"))
                assertNull(request.header("Referer"))
                assertEquals(cookie, request.header("Cookie"))
                assertEquals(sessionId, request.header("X-GeniusTA-Session"))
                when (path) {
                    "/api/v1/auth/me" -> response(request, identity(accountId))
                    "/api/v1/courses" -> response(request, courses("test-course"))
                    "/api/v1/courses/test-course/assignments" -> response(request, newAssignments())
                    else -> error("Unexpected request route")
                }
            }
        }
        assertTrue(repository.connect("synthetic-test-user", password))
        assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/me", "/api/v1/courses", "/api/v1/courses/test-course/assignments"), paths)
        assertEquals(accountId, repository.state.value?.accountId)
        assertEquals("new-assignment", repository.state.value?.tasks?.single()?.id)
        assertEquals(sessionId, store().load().session?.id)
        assertFalse(disk.orEmpty().contains(password))
        assertFalse(disk.orEmpty().contains("synthetic-test-user"))
    }

    @Test fun partialRefreshKeepsOldSnapshotOnDiskAndInUi() = runBlocking {
        seed()
        val durable = disk
        val repository = repository { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/me" -> response(request, identity(accountId))
                "/api/v1/courses" -> response(request, courses("first-course", "second-course"))
                "/api/v1/courses/first-course/assignments" -> response(request, newAssignments())
                "/api/v1/courses/second-course/assignments" -> response(request, "{}", 503)
                else -> error("Unexpected request route")
            }
        }
        assertTrue(repository.reload())
        assertFalse(repository.refresh())
        assertEquals(GeniusTaError.NETWORK, repository.state.value?.error)
        assertEquals(previous.id, repository.state.value?.tasks?.single()?.id)
        assertEquals(durable, disk)
    }

    @Test fun mismatchedAuthenticatedIdentityDurablyInvalidatesSessionWithoutChangingCache() = runBlocking {
        seed()
        val repository = repository { request ->
            assertEquals("/api/v1/auth/me", request.url.encodedPath)
            response(request, identity("test-account-b"))
        }
        assertFalse(repository.refresh())
        val rejected = requireNotNull(repository.state.value)
        assertTrue(rejected.needsLogin)
        assertEquals(GeniusTaError.AUTHENTICATION, rejected.error)
        assertEquals(accountId, rejected.accountId)
        assertEquals(previous.id, rejected.tasks.single().id)
        assertNull(store().load().session)
        val restarted = repository { error("Removed credential must not be reused") }
        assertTrue(restarted.reload())
        assertTrue(requireNotNull(restarted.state.value).needsLogin)
        assertFalse(restarted.refresh())
    }

    @Test fun failedReconnectRestoresOnlyCacheForProvenSameAccount() = runBlocking {
        seed()
        var candidate = accountId
        val repository = repository { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> loginResponse(request, candidate)
                "/api/v1/auth/me" -> response(request, identity(candidate))
                "/api/v1/courses" -> response(request, "{}", 503)
                else -> error("Unexpected request route")
            }
        }
        assertTrue(repository.reload())
        assertTrue(repository.setCompleted(accountId, previous.id, true))
        assertFalse(repository.connect("synthetic-user", "synthetic-password"))
        val recovered = requireNotNull(repository.state.value)
        assertEquals(accountId, recovered.accountId)
        assertEquals(previous.id, recovered.tasks.single().id)
        assertTrue(recovered.tasks.single().completed)
        assertTrue(recovered.needsLogin)
        assertNull(store().load().session)

        candidate = "test-account-b"
        assertFalse(repository.connect("synthetic-other-user", "synthetic-password"))
        assertNull(repository.state.value?.accountId)
        assertTrue(requireNotNull(repository.state.value).tasks.isEmpty())
        assertFalse(repository.setCompleted(accountId, previous.id, false))
        assertNull(store().load().accountId)
    }

    @Test fun localLogoutSupersedesAnInFlightRefresh() = runBlocking {
        seed()
        val requestStarted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val repository = repository { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/me" -> response(request, identity(accountId))
                "/api/v1/courses" -> response(request, courses("test-course"))
                "/api/v1/courses/test-course/assignments" -> {
                    requestStarted.countDown()
                    check(allowResponse.await(10, TimeUnit.SECONDS))
                    response(request, newAssignments())
                }
                else -> error("Unexpected request route")
            }
        }
        val refresh = async { repository.refresh() }
        try {
            assertTrue(withContext(Dispatchers.IO) { requestStarted.await(10, TimeUnit.SECONDS) })
            assertTrue(repository.disconnect())
            assertNull(repository.state.value?.accountId)
            assertEquals(cookieValue, store().load().session?.cookieValue)
        } finally {
            allowResponse.countDown()
        }
        assertFalse(refresh.await())
        assertTrue(requireNotNull(repository.state.value).tasks.isEmpty())
        val restored = store().load()
        assertTrue(restored.disconnected)
        assertNull(restored.activeAccountId)
        assertEquals(listOf(previous), restored.cache[accountId]?.assignments)
    }

    @Test fun failedLogoutDoesNotReshowOrUseOldSessionAndReloadRetriesDurableDisconnect() = runBlocking {
        seed()
        val repository = repository { error("Logout is local") }
        assertTrue(repository.reload())
        refuseCommit = true
        assertFalse(repository.disconnect())
        assertEquals(GeniusTaError.STORAGE, repository.state.value?.error)
        assertNull(repository.state.value?.accountId)
        assertTrue(requireNotNull(repository.state.value).tasks.isEmpty())
        assertFalse(repository.refresh())
        refuseCommit = false
        assertTrue(repository.reload())
        val restored = store().load()
        assertTrue(restored.disconnected)
        assertEquals(cookieValue, restored.session?.cookieValue)
        assertNull(repository.state.value?.accountId)
    }

    @Test fun restartedDormantConnectionRequiresSuccessfulExplicitLoginForTheExpectedAccount() = runBlocking {
        seed()
        val first = repository { error("Local disconnect never contacts the platform") }
        assertTrue(first.disconnect())
        val dormant = disk
        val requests = mutableListOf<String>()
        val freshValue = "synthetic-new-cookie"
        var candidate = "test-account-b"
        var coursesAvailable = false
        val restarted = repository { request ->
            val path = request.url.encodedPath
            requests += path
            if (path != "/api/v1/auth/login") {
                assertEquals("gta_prod_session_$sessionId=$freshValue", request.header("Cookie"))
            }
            when (path) {
                "/api/v1/auth/login" -> loginResponse(request, candidate, "gta_prod_session_$sessionId=$freshValue; Path=/; Secure; HttpOnly")
                "/api/v1/auth/me" -> response(request, identity(candidate))
                "/api/v1/courses" -> if (coursesAvailable) response(request, courses("test-course")) else response(request, "{}", 503)
                "/api/v1/courses/test-course/assignments" -> response(request, newAssignments())
                else -> error("Unexpected request route")
            }
        }
        assertTrue(restarted.reload())
        assertFalse(restarted.refresh())
        assertFalse(restarted.reconnect("synthetic-user", "synthetic-password", accountId))
        assertTrue(requests.isEmpty())
        assertNull(restarted.state.value?.accountId)
        assertTrue(requireNotNull(restarted.state.value).tasks.isEmpty())

        assertFalse(restarted.connect("synthetic-user", "synthetic-password", accountId))
        assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/me"), requests)
        assertEquals(GeniusTaError.AUTHENTICATION, restarted.state.value?.error)
        assertNull(restarted.state.value?.accountId)
        assertEquals(dormant, disk)

        candidate = accountId
        assertFalse(restarted.connect("synthetic-user", "synthetic-password", accountId))
        assertEquals(GeniusTaError.NETWORK, restarted.state.value?.error)
        assertNull(restarted.state.value?.accountId)
        assertTrue(requireNotNull(restarted.state.value).tasks.isEmpty())
        assertEquals(dormant, disk)

        coursesAvailable = true
        assertTrue(restarted.connect("synthetic-user", "synthetic-password", accountId))
        assertEquals(accountId, restarted.state.value?.accountId)
        assertEquals("new-assignment", restarted.state.value?.tasks?.single()?.id)
        val restored = store().load()
        assertFalse(restored.disconnected)
        assertEquals(freshValue, restored.session?.cookieValue)
    }

    @Test fun recoveryRequiresMatchingExpiredAccountAndDoesNotDuplicateNewerAuthentication() = runBlocking {
        seed(expiresAt = 1)
        val authStarted = CountDownLatch(1)
        val allowAuth = CountDownLatch(1)
        val requests = mutableListOf<String>()
        val repository = repository { request ->
            val path = request.url.encodedPath
            requests += path
            when (path) {
                "/api/v1/auth/login" -> {
                    authStarted.countDown()
                    check(allowAuth.await(10, TimeUnit.SECONDS))
                    loginResponse(request, accountId)
                }
                "/api/v1/auth/me" -> response(request, identity(accountId))
                "/api/v1/courses" -> response(request, courses("test-course"))
                "/api/v1/courses/test-course/assignments" -> response(request, newAssignments())
                else -> error("Unexpected request route")
            }
        }
        assertTrue(repository.reload())
        assertTrue(requireNotNull(repository.state.value).needsLogin)
        assertEquals(accountId, repository.state.value?.accountId)
        assertFalse(repository.reconnect("synthetic-user", "synthetic-password", "test-account-b"))
        assertTrue(requests.isEmpty())
        val recovery = async { repository.reconnect("synthetic-user", "synthetic-password", accountId) }
        try {
            assertTrue(withContext(Dispatchers.IO) { authStarted.await(10, TimeUnit.SECONDS) })
            assertFalse(repository.reconnect("synthetic-user", "synthetic-password", accountId))
            assertFalse(repository.refresh())
        } finally {
            allowAuth.countDown()
        }
        assertTrue(recovery.await())
        assertFalse(repository.reconnect("synthetic-user", "synthetic-password", accountId))
        assertEquals(1, requests.count { it == "/api/v1/auth/login" })
        assertEquals(accountId, repository.state.value?.accountId)
        assertFalse(requireNotNull(repository.state.value).needsLogin)
    }

    @Test fun loginRejectsUnpairedOrInsecureCookie() = runBlocking {
        for (header in listOf("wrong_name=$cookieValue; Path=/; Secure; HttpOnly", "$cookie; Path=/; HttpOnly")) {
            val repository = repository { request ->
                assertEquals("/api/v1/auth/login", request.url.encodedPath)
                loginResponse(request, accountId, header)
            }
            assertFalse(repository.connect("synthetic-user", "synthetic-password"))
            assertEquals(GeniusTaError.INVALID_RESPONSE, repository.state.value?.error)
            assertNull(store().load().session)
        }
    }

    private fun seed(expiresAt: Long = Long.MAX_VALUE) {
        store().replace(GeniusTaConnection(accountId, GeniusTaSession(sessionId, cookieValue, expiresAt)), listOf(previous))
    }

    private fun repository(serve: (Request) -> Response): GeniusTaRepository {
        val testClient = OkHttpClient.Builder().addInterceptor { serve(it.request()) }.build()
        return GeniusTaRepository(store(), GeniusTaWebSource(testClient), Dispatchers.Unconfined)
    }

    private fun loginResponse(request: Request, id: String, header: String = "$cookie; Path=/; Secure; HttpOnly"): Response =
        response(request, """{"data":{"user":{"public_id":"$id","role":"student"},"session":{
            "id":"$sessionId","expires_at":"2099-01-01T00:00:00Z","idle_expires_at":"2098-01-01T00:00:00Z"
        }}}""").newBuilder().addHeader("Set-Cookie", header).build()

    private fun identity(id: String) = """{"data":{"public_id":"$id","role":"student"}}"""

    private fun courses(vararg ids: String): String = ids.joinToString(prefix = """{"data":[""", postfix = "]}") {
        """{"public_id":"$it","name":"Synthetic Course","current_user_role":"student","current_user_section_id":null}"""
    }

    private fun newAssignments() = """{"data":[{
        "public_id":"new-assignment","title":"Synthetic Assignment","is_released":true,
        "due_date":"2026-04-04T00:15:00+08:00","late_due_date":null,"section_schedules":[],
        "ungrouped_schedule":null,"submission_state":{"status":"not_submitted"}
    }]}"""

    private fun response(request: Request, body: String, code: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic response")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}
