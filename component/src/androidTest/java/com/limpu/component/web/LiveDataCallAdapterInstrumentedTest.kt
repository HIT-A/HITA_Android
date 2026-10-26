package com.limpu.component.web

import androidx.lifecycle.LiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpu.component.testing.ObservedValues
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.IOException
import java.lang.reflect.ParameterizedType
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LiveDataCallAdapterInstrumentedTest {
    @Test
    fun adaptationEnqueuesOnceBeforeObservationAndDeliversOriginalBody() {
        val call = RecordingCall()
        val live = LiveDataCallAdapter(String::class.java).adapt(call)
        assertEquals(1, call.enqueues)
        val payload = Any()
        ObservedValues(live).use { values ->
            call.respond(Response.success(payload))
            assertSame(payload, values.next())
        }
        assertEquals(1, call.enqueues)
        assertFalse(call.isCanceled)
    }

    @Test
    fun emptyHttpFailureAndTransportFailureEachDeliverAnActualNullEvent() {
        val responses = listOf<(RecordingCall) -> Unit>(
            { it.respond(Response.success<Any>(null)) },
            { it.respond(Response.error(503, ResponseBody.create(MediaType.parse("text/plain"), "offline"))) },
            { it.fail(IOException("connection closed")) }
        )
        for (respond in responses) {
            val call = RecordingCall()
            ObservedValues(LiveDataCallAdapter(String::class.java).adapt(call)).use { values ->
                respond(call)
                assertNull(values.next())
            }
        }
    }

    @Test
    fun factoryPreservesNestedTypeAndRejectsUnsupportedReturns() {
        val factory = LiveDataCallAdapter.LiveDataCallAdapterFactory.INSTANCE
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").build()
        val type = ReturnTypes::class.java.getMethod("list").genericReturnType as ParameterizedType
        val adapter = requireNotNull(factory.get(type, emptyArray(), retrofit))
        assertEquals(type.actualTypeArguments.single(), adapter.responseType())
        assertNull(factory.get(String::class.java, emptyArray(), retrofit))
        assertNull(factory.get(LiveData::class.java, emptyArray(), retrofit))
    }

    @Test
    fun baseWebSourceUsesRealRetrofitJsonAndHttpFailureCallbacks() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val source = FixtureSource(server.url("/").toString())
            server.enqueue(MockResponse().setBody("{\"value\":\"中文\"}"))
            ObservedValues(source.fetch()).use { assertEquals("中文", it.next()?.value) }
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/fixture", request.path)
            for (response in listOf(
                MockResponse().setResponseCode(503).setBody("unavailable"),
                MockResponse().setResponseCode(204),
                MockResponse().setBody("{broken-json")
            )) {
                server.enqueue(response)
                ObservedValues(source.fetch()).use { assertNull(it.next()) }
            }
        } finally {
            server.shutdown()
        }
    }

    interface ReturnTypes { fun list(): LiveData<List<String>> }
    class Payload { var value: String? = null }
    interface FixtureService { @GET("fixture") fun fetch(): LiveData<Payload> }
    private class FixtureSource(url: String) : BaseWebSource<FixtureService>(
        InstrumentationRegistry.getInstrumentation().targetContext, url
    ) {
        override fun getServiceClass() = FixtureService::class.java
        fun fetch() = service.fetch()
    }

    private class RecordingCall : Call<Any> {
        var enqueues = 0
        private var canceled = false
        private lateinit var callback: Callback<Any>
        override fun enqueue(callback: Callback<Any>) { enqueues++; this.callback = callback }
        fun respond(response: Response<Any>) = callback.onResponse(this, response)
        fun fail(error: Throwable) = callback.onFailure(this, error)
        override fun execute(): Response<Any> = throw AssertionError("Unexpected synchronous request")
        override fun cancel() { canceled = true }
        override fun isCanceled() = canceled
        override fun isExecuted() = enqueues != 0
        override fun clone(): Call<Any> = RecordingCall()
        override fun request(): Request = Request.Builder().url("http://localhost/").build()
    }
}
