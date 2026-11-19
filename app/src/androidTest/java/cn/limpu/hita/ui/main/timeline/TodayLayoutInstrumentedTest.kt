package cn.limpu.hita.ui.main.timeline

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.ui.design.HitaComposeTheme
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Use native density when the host fits; reduce only for smaller CI displays. */
class TodayLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val window = TodayDateWindow.from(Instant.parse("2026-11-18T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val now = window.start + 12 * 3_600_000L
    private fun state(key: String = "a") : TodayUiState {
        val events = (0..14).map { i -> EventItem().apply {
            id = "$key-${i.toString().padStart(2, '0')}"; name = id
            from = Timestamp(now - 60_000); to = Timestamp(now + 3_600_000)
        } }
        val agenda = TodayProjection.project(window, events, now)
        return TodayUiState(now, window, scopeKey = key, status = TodayPageStatus.READY, agenda = agenda)
    }
    private fun host(height: Int = 674, scale: Float = 1f, test: (androidx.compose.runtime.MutableState<TodayUiState>, ActivityScenario<ComponentActivity>) -> Unit) {
        val rendered = mutableStateOf(state())
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme {
                    val native = LocalDensity.current
                    val metrics = activity.resources.displayMetrics
                    val testDensity = minOf(native.density, metrics.widthPixels / 360f, metrics.heightPixels / height.toFloat())
                    CompositionLocalProvider(LocalDensity provides Density(testDensity, scale)) {
                        Box(Modifier.requiredSize(360.dp, height.dp)) {
                            TodayScreen(rendered.value, {}, bottomClearance = 76.dp)
                        }
                    }
                } }
            }) }
            test(rendered, scenario)
        }
    }
    private fun testDensity(): Float {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        return minOf(metrics.density, metrics.widthPixels / 360f, metrics.heightPixels / 674f)
    }
    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
        return null
    }
    private fun await(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            instrumentation.waitForIdleSync()
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, match)?.let { return it }
            Thread.sleep(30)
        }
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null || depth > 16) return ""
            return buildString {
                append("  ".repeat(depth)); append("${node.viewIdResourceName} text=${node.text} description=${node.contentDescription} bounds=${bounds(node)} scroll=${node.isScrollable}\n")
                for (index in 0 until node.childCount) append(describe(node.getChild(index), depth + 1))
            }
        }
        throw AssertionError("Expected accessibility node did not appear\n" + describe(instrumentation.uiAutomation.rootInActiveWindow))
    }
    private fun tag(value: String) = await { it.viewIdResourceName == value }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun settle() {
        instrumentation.waitForIdleSync()
        // Main-queue idleness does not wait for Compose scroll animations or virtual-node updates.
        instrumentation.uiAutomation.waitForIdle(150, 3000)
        // Accessibility can report idleness between fling updates. Record a baseline only
        // after the visible text geometry stays unchanged, preserving the 1px restoration check.
        fun geometry(node: AccessibilityNodeInfo?): List<Pair<String, Rect>> {
            if (node == null) return emptyList()
            return buildList {
                node.text?.toString()?.let { if (node.isVisibleToUser) add(it to bounds(node)) }
                for (i in 0 until node.childCount) addAll(geometry(node.getChild(i)))
            }
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        var previous: List<Pair<String, Rect>>? = null
        var stableSince = System.nanoTime()
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val current = geometry(instrumentation.uiAutomation.rootInActiveWindow)
            if (current != previous) {
                previous = current
                stableSince = System.nanoTime()
            } else if (current.isNotEmpty() && System.nanoTime() - stableSince >= 350_000_000L) return
            Thread.sleep(50)
        }
        throw AssertionError("Visible geometry did not settle after scrolling")
    }
    private fun scroll() {
        val viewport = bounds(tag("today-agenda-scroll"))
        // Drive a real gesture; accessibility scroll actions can reject overlapping animation requests.
        val command = "input swipe ${viewport.centerX()} ${viewport.bottom - viewport.height() / 5} " +
            "${viewport.centerX()} ${viewport.top + viewport.height() / 5} 300"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        settle()
    }

    @Test fun summariesStayFixedAndLastEventDoesNotOverlapThem() = host { _, _ ->
        val before = bounds(tag("today-fixed-summaries"))
        scroll()
        assertEquals(before, bounds(tag("today-fixed-summaries")))
        for (step in 0 until 25) {
            val viewport = bounds(tag("today-agenda-scroll"))
            val lastVisible = find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == "a-14" && bounds(it).bottom <= viewport.bottom - 16
            }
            if (lastVisible != null) break
            scroll()
        }
        val last = await { it.text?.toString() == "a-14" || it.contentDescription?.toString()?.contains("a-14") == true }
        assertTrue(bounds(last).bottom <= before.top)
    }
    @Test fun shortScreensUseCompactSummaries() = host(height = 440) { _, _ ->
        tag("today-fixed")
        assertEquals(68f, bounds(tag("today-fixed-summaries")).height() / testDensity(), 1f)
    }
    @Test fun largerTextUsesCompactSummaries() = host(scale = 1.3f) { _, _ ->
        tag("today-fixed")
        assertEquals(80f, bounds(tag("today-fixed-summaries")).height() / testDensity(), 1f)
    }
    @Test fun landscapeHeightUsesOnePageList() = host(height = 240) { _, _ -> tag("today-page-scroll") }
    @Test fun loadingAndReadyKeepIdenticalSummaryHeight() = host { rendered, scenario ->
        val before = bounds(tag("today-fixed-summaries"))
        scenario.onActivity { rendered.value = rendered.value.copy(status = TodayPageStatus.LOADING, agenda = null) }
        await { it.text?.toString() == "正在读取…" || it.contentDescription?.toString()?.contains("正在读取") == true }
        assertEquals(before, bounds(tag("today-fixed-summaries")))
    }
    @Test fun scopeRoundTripThroughLoadingRestoresTheVisibleEvent() = host { rendered, scenario ->
        scroll(); scroll()
        val listBefore = bounds(tag("today-agenda-scroll"))
        val visible = await { it.text?.toString()?.startsWith("a-") == true && bounds(it).top >= listBefore.top + 16 && bounds(it).bottom < listBefore.bottom }
        val name = visible.text.toString()
        val top = bounds(visible).top
        scenario.onActivity { rendered.value = state("b").copy(status = TodayPageStatus.LOADING, agenda = null) }
        await { it.text?.toString() == "正在读取…" }
        scenario.onActivity { rendered.value = state("b") }
        await { it.text?.toString() == "b-00" || it.contentDescription?.toString()?.contains("b-00") == true }
        scenario.onActivity { rendered.value = state("a").copy(status = TodayPageStatus.LOADING, agenda = null) }
        await { it.text?.toString() == "正在读取…" }
        scenario.onActivity { rendered.value = state("a") }
        settle()
        val restored = await { it.text?.toString() == name && bounds(it).top >= listBefore.top }
        assertEquals(top.toFloat(), bounds(restored).top.toFloat(), 1f)
    }
}
