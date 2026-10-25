package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import java.io.Closeable
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before

/** 本地双端口学业契约测试；所有未配置的请求均使测试失败。 */
abstract class ShenzhenAcademicContractHarness {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val api = MockWebServer()
    protected val web = MockWebServer()
    protected val apiRequests = CopyOnWriteArrayList<RecordedRequest>()
    protected val webRequests = CopyOnWriteArrayList<RecordedRequest>()
    protected val refreshedToken = AtomicReference<EASToken>()
    protected val refreshCount = AtomicInteger()
    protected var apiResponse: (RecordedRequest) -> MockResponse = { unexpected("API", it) }
    protected var webResponse: (RecordedRequest) -> MockResponse = { unexpected("Web", it) }
    protected lateinit var source: EASWebSource
    protected val term = TermItem("2026-2027", "2026-2027", "1", "秋季")

    private val requestFailures = CopyOnWriteArrayList<String>()
    private val observations = CopyOnWriteArrayList<Closeable>()
    private val callbackThreads = LinkedBlockingQueue<Thread>()

    @Before
    fun setUpAcademicServers() {
        api.dispatcher = dispatcher(apiRequests) { apiResponse(it) }
        web.dispatcher = dispatcher(webRequests) { webResponse(it) }
        val loopback = InetAddress.getByName("127.0.0.1")
        api.start(loopback, 0)
        web.start(loopback, 0)
        source = EASWebSource(
            onTokenRefreshed = {
                refreshedToken.set(it)
                refreshCount.incrementAndGet()
                callbackThreads.offer(Thread.currentThread())
            },
            apiHostOverride = api.url("/").toString(),
            webHostOverride = web.url("/").toString(),
        )
    }

    @After
    fun tearDownAcademicServers() {
        try {
            observations.forEach { it.close() }
        } finally {
            try {
                api.shutdown()
            } finally {
                web.shutdown()
            }
        }
        assertTrue("本地请求异常：$requestFailures", requestFailures.isEmpty())
    }

    protected fun apiToken() = EASToken().apply {
        accessToken = "local-api-token"
        cookies["api-cookie"] = "local-api-cookie"
    }

    protected fun webToken() = EASToken().apply {
        webCookies["SESSION"] = "local-web-session"
    }

    protected fun json(body: String, status: Int = 200): MockResponse =
        MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    protected fun form(request: RecordedRequest): Map<String, String> =
        request.body.clone().readUtf8().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val parts = pair.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to
                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

    protected fun jsonBody(request: RecordedRequest) = JSONObject(request.body.clone().readUtf8())

    /** 用已知会通知 Token 的查询线程确认结束，供“后续失败仍保留结果”的负向断言使用。 */
    protected fun awaitWorkerCompletion() {
        val worker = callbackThreads.poll(20, TimeUnit.SECONDS)
            ?: throw AssertionError("未收到查询线程的 Token 回调")
        assertTrue("不能等待当前测试线程", worker !== Thread.currentThread())
        worker.join(TimeUnit.SECONDS.toMillis(20))
        assertTrue("学业查询线程未结束", !worker.isAlive)
        callbackThreads.removeIf { it === worker }
        instrumentation.waitForIdleSync()
    }

    protected fun fixture(name: String): String = instrumentation.context.assets
        .open("shenzhen/academic/$name").bufferedReader().use { it.readText() }

    protected fun <T> success(block: () -> LiveData<DataState<T>>): T {
        val state = awaitState(block)
        assertEquals(state.message, DataState.STATE.SUCCESS, state.state)
        return requireNotNull(state.data)
    }

    protected fun <T> awaitState(block: () -> LiveData<DataState<T>>): DataState<T> =
        observe(block).use { observed ->
            observed.await {
                it.state != DataState.STATE.NOTHING && it.state != DataState.STATE.LOADING
            }
        }

    protected fun <T> observe(block: () -> LiveData<DataState<T>>): Observed<T> {
        lateinit var result: Observed<T>
        instrumentation.runOnMainSync {
            result = Observed(block())
            result.start()
        }
        observations.add(result)
        return result
    }

    protected inner class Observed<T>(private val liveData: LiveData<DataState<T>>) : Closeable {
        val values = CopyOnWriteArrayList<DataState<T>>()
        private val queue = LinkedBlockingQueue<DataState<T>>()
        private val observer = Observer<DataState<T>> { state ->
            values.add(state)
            queue.offer(state)
        }

        fun start() {
            liveData.observeForever(observer)
        }

        fun await(predicate: (DataState<T>) -> Boolean): DataState<T> {
            values.lastOrNull(predicate)?.let { return it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw AssertionError("等待学业结果超时，已观察结果：$values")
                val state = queue.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("等待学业结果超时，已观察结果：$values")
                if (predicate(state)) return state
            }
        }

        override fun close() {
            instrumentation.runOnMainSync { liveData.removeObserver(observer) }
            observations.remove(this)
        }
    }

    private fun unexpected(server: String, request: RecordedRequest): MockResponse {
        requestFailures.add("未配置 $server ${request.method} ${request.path}")
        return json("""{"code":500,"message":"unconfigured local response"}""", 500)
    }

    private fun dispatcher(
        requests: MutableList<RecordedRequest>,
        respond: (RecordedRequest) -> MockResponse,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            return try {
                respond(request)
            } catch (error: Throwable) {
                requestFailures.add("${request.path}: ${error.stackTraceToString()}")
                json("""{"code":500,"message":"local response assertion failed"}""", 500)
            }
        }
    }
}
