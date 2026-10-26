package com.limpu.hitauser.data.source.web

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpu.component.web.LiveDataCallAdapter
import com.limpu.hitauser.R
import com.limpu.hitauser.data.model.ApiResponse
import com.limpu.hitauser.data.model.LoginResult
import com.limpu.hitauser.data.model.SignUpResult
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.source.web.service.UserService
import com.limpu.hitauser.testing.ObservedValues
import com.limpu.hitauser.testing.onMain
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UserWebSourceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loginPreservesParametersAndMapsRepeatedResponsesWithoutAnotherRequest() {
        val service = RecordingService()
        val source = UserWebSource(context, service)
        ObservedValues(source.login("name + 中文", "pass&=" )).use { values ->
            assertEquals(listOf("name + 中文", "pass&="), service.loginArguments)
            val userWithoutToken = UserLocal().apply { id = "synthetic" }
            onMain { service.loginResult.value = ApiResponse(200, "ok", userWithoutToken) }
            val success = requireNotNull(values.next())
            assertEquals(LoginResult.STATES.SUCCESS, success.state)
            assertEquals(R.string.login_success, success.message)
            assertSame(userWithoutToken, success.userLocal)
            val cases = listOf(
                Triple(303, LoginResult.STATES.WRONG_USERNAME, R.string.wrong_username),
                Triple(304, LoginResult.STATES.WRONG_PASSWORD, R.string.wrong_password),
                Triple(200, LoginResult.STATES.ERROR, R.string.login_failed),
                Triple(599, LoginResult.STATES.ERROR, R.string.login_failed)
            )
            for ((code, expected, message) in cases) {
                onMain { service.loginResult.value = ApiResponse(code, "fixture", null) }
                val result = requireNotNull(values.next())
                assertEquals(expected, result.state)
                assertEquals(message, result.message)
                assertNull(result.userLocal)
            }
            onMain { service.loginResult.value = null }
            assertEquals(LoginResult.STATES.ERROR, values.next()?.state)
            assertEquals(1, service.loginCalls)
        }
    }

    @Test
    fun signupPreservesAllFieldsAndLegacyFailureMessages() {
        val service = RecordingService()
        val source = UserWebSource(context, service)
        ObservedValues(source.signUp("u", "p", "OTHER", "同学")).use { values ->
            assertEquals(listOf("u", "p", "OTHER", "同学"), service.signupArguments)
            val user = UserLocal().apply { id = "created" }
            onMain { service.signupResult.value = ApiResponse(200, "ok", user) }
            val success = requireNotNull(values.next())
            assertEquals(SignUpResult.STATES.SUCCESS, success.state)
            assertEquals(R.string.sign_up_success, success.message)
            assertSame(user, success.userLocal)
            for ((code, state, message) in listOf(
                Triple(301, SignUpResult.STATES.USER_EXISTS, R.string.user_already_exists),
                Triple(200, SignUpResult.STATES.ERROR, R.string.signup_confirm_password),
                Triple(599, SignUpResult.STATES.ERROR, R.string.sign_up_failed)
            )) {
                onMain { service.signupResult.value = ApiResponse(code, "fixture", null) }
                val result = requireNotNull(values.next())
                assertEquals(state, result.state)
                assertEquals(message, result.message)
                assertNull(result.userLocal)
            }
            onMain { service.signupResult.value = null }
            val empty = requireNotNull(values.next())
            assertEquals(SignUpResult.STATES.ERROR, empty.state)
            assertEquals(R.string.sign_up_failed, empty.message)
            assertEquals(1, service.signupCalls)
        }
    }

    @Test
    fun nullableSignupArgumentsReachServiceUnchanged() {
        val service = RecordingService()
        UserWebSource(context, service).signUp(null, null, null, null)
        assertEquals(listOf<String?>(null, null, null, null), service.signupArguments)
        assertEquals(1, service.signupCalls)
    }

    @Test
    fun actualHttpLoginAndSignupEncodeFormsAndDecodeUserPayload() {
        withServer { server, source ->
            server.enqueue(MockResponse().setBody("""{"code":200,"msg":"ok","data":{"id":"17","username":"student","token":"fixture-token","publicKey":"fixture-key","gender":"OTHER"}}"""))
            ObservedValues(source.login("a + 中文", "p&=+ /" )).use { values ->
                val result = requireNotNull(values.next())
                assertEquals(LoginResult.STATES.SUCCESS, result.state)
                val user = requireNotNull(result.userLocal)
                assertEquals("17", user.id)
                assertEquals("student", user.username)
                assertEquals("fixture-token", user.token)
                assertEquals("fixture-key", user.publicKey)
                assertEquals(UserLocal.GENDER.OTHER, user.gender)
            }
            assertForm(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)), "/user/log_in",
                mapOf("username" to "a + 中文", "password" to "p&=+ /"))
            server.enqueue(MockResponse().setBody("""{"code":200,"msg":"ok","data":{"id":"18"}}"""))
            ObservedValues(source.signUp("new name", "x&y", "FEMALE", "名字 +")).use {
                assertEquals(SignUpResult.STATES.SUCCESS, it.next()?.state)
            }
            assertForm(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)), "/user/sign_up",
                mapOf("username" to "new name", "password" to "x&y", "gender" to "FEMALE", "nickname" to "名字 +"))
        }
    }

    @Test
    fun actualHttpAndMalformedPayloadFailuresReachLoginErrorState() {
        withServer { server, source ->
            for (response in listOf(
                MockResponse().setResponseCode(503).setBody("offline"),
                MockResponse().setResponseCode(204),
                MockResponse().setBody("{malformed")
            )) {
                server.enqueue(response)
                ObservedValues(source.login("fixture", "fixture")).use {
                    val result = requireNotNull(it.next())
                    assertEquals(LoginResult.STATES.ERROR, result.state)
                    assertEquals(R.string.login_failed, result.message)
                    assertNull(result.userLocal)
                }
            }
        }
    }

    private fun withServer(test: (MockWebServer, UserWebSource) -> Unit) {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val service = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(LiveDataCallAdapter.LiveDataCallAdapterFactory.INSTANCE)
                .build().create(UserService::class.java)
            test(server, UserWebSource(context, service))
        } finally { server.shutdown() }
    }

    private fun assertForm(request: RecordedRequest, path: String, expected: Map<String, String>) {
        assertEquals("POST", request.method)
        assertEquals(path, request.path)
        assertTrue(request.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") == true)
        val decoded = requireNotNull(HttpUrl.parse("http://localhost/?${request.body.readUtf8()}"))
        assertEquals(expected.size, decoded.querySize())
        expected.forEach { (name, value) -> assertEquals(value, decoded.queryParameter(name)) }
    }

    private class RecordingService : UserService {
        val loginResult = MutableLiveData<ApiResponse<UserLocal>?>()
        val signupResult = MutableLiveData<ApiResponse<UserLocal>?>()
        var loginArguments: List<String?> = emptyList()
        var signupArguments: List<String?> = emptyList()
        var loginCalls = 0
        var signupCalls = 0
        override fun login(username: String?, password: String?): LiveData<ApiResponse<UserLocal>?> {
            loginCalls++
            loginArguments = listOf(username, password)
            return loginResult
        }
        override fun signUp(username: String?, password: String?, gender: String?, nickname: String?): LiveData<ApiResponse<UserLocal>?> {
            signupCalls++
            signupArguments = listOf(username, password, gender, nickname)
            return signupResult
        }
    }
}
