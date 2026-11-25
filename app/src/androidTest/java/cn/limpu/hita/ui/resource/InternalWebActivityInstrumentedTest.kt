package cn.limpu.hita.ui.resource

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InternalWebActivityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun redirectAndLinkHistorySurviveRecreationAndConsumeBack() = MockWebServer().use { server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/entry" -> MockResponse().setResponseCode(302).addHeader("Location", "/home")
                "/home" -> page("Home", "<a id='next' href='/next'>Next page</a>")
                "/next" -> page("Next")
                else -> MockResponse().setResponseCode(404)
            }
        }
        launch(server.url("/entry").toString()).use { scenario ->
            awaitPage(scenario, "/home", "Home")
            scenario.onActivity { activity -> reader(activity.window.decorView)?.evaluateJavascript(
                "document.getElementById('next').click()", null,
            ) }
            awaitPage(scenario, "/next", "Next")
            scenario.recreate()
            awaitPage(scenario, "/next", "Next")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitPage(scenario, "/home", "Home")
        }
    }

    @Test fun failedPageCanBeRetriedInPlace() = MockWebServer().use { server ->
        val attempts = AtomicInteger()
        val shouldFail = AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/article") return MockResponse().setResponseCode(404)
                attempts.incrementAndGet()
                return if (shouldFail.get()) page("Unavailable").setResponseCode(503) else page("Recovered")
            }
        }
        launch(server.url("/article").toString()).use { scenario ->
            val device = UiDevice.getInstance(instrumentation)
            val retry = device.wait(Until.findObject(By.text("重试")), 10_000)
            if (retry == null) {
                val hierarchy = ByteArrayOutputStream()
                device.dumpWindowHierarchy(hierarchy)
                throw AssertionError("A failed main page should offer retry; served=${attempts.get()}\n$hierarchy")
            }
            val beforeRetry = attempts.get()
            shouldFail.set(false)
            retry.click()
            awaitPage(scenario, "/article", "Recovered")
            assertEquals(beforeRetry + 1, attempts.get())
        }
    }

    private fun launch(url: String): ActivityScenario<InternalWebActivity> = ActivityScenario.launch(
        Intent(instrumentation.targetContext, InternalWebActivity::class.java).putExtra("url", url),
    )

    private fun awaitPage(scenario: ActivityScenario<InternalWebActivity>, path: String, title: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var observed: Pair<String?, String?>? = null
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val web = reader(activity.window.decorView)
                observed = web?.let { it.url to it.title }
            }
            if (observed?.first?.endsWith(path) == true && observed?.second == title) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Expected $path ($title); observed $observed")
    }

    private fun reader(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            reader(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun page(title: String, body: String = title): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("<!doctype html><title>$title</title><body>$body</body>")
}
