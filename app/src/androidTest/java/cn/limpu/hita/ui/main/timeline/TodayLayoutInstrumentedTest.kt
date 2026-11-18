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

/** Uses a reduced test density so the exact dp budgets fit even on CI's 320px host. */
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
        return TodayUiState(now, window, scopeKey = key, status = TodayPageStatus.READY, agenda = agenda,
            header = TodayHeaderResolver.resolve(now, window.zoneId, agenda, emptyList()))
    }
    private fun host(height: Int = 674, scale: Float = 1f, test: (androidx.compose.runtime.MutableState<TodayUiState>, ActivityScenario<ComponentActivity>) -> Unit) {
        val rendered = mutableStateOf(state())
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(0.75f, scale)) {
                        Box(Modifier.requiredSize(360.dp, height.dp)) {
                            TodayScreen(rendered.value, emptyList(), {}, {}, bottomClearance = 76.dp)
                        }
                    }
                } }
            }) }
            test(rendered, scenario)
        }
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
            find(instrumentation.uiAutomation.rootInActiveWindow, match)?.let { return it }
            Thread.sleep(30)
        }
        throw AssertionError("Expected accessibility node did not appear")
    }
    private fun tag(value: String) = await { it.viewIdResourceName == value }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun scroll() { assertTrue(tag("today-agenda-scroll").performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); instrumentation.waitForIdleSync() }

    @Test fun summariesStayFixedAndLastEventDoesNotOverlapThem() = host { _, _ ->
        val before = bounds(tag("today-fixed-summaries"))
        scroll()
        assertEquals(before, bounds(tag("today-fixed-summaries")))
        repeat(25) { tag("today-agenda-scroll").performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); instrumentation.waitForIdleSync() }
        val last = await { it.text?.toString() == "a-14" || it.contentDescription?.toString()?.contains("a-14") == true }
        assertTrue(bounds(last).bottom <= before.top)
    }
    @Test fun shortScreensUseCompactSummaries() = host(height = 538) { _, _ ->
        tag("today-fixed")
        assertEquals(51f, bounds(tag("today-fixed-summaries")).height().toFloat(), 1f)
    }
    @Test fun largerTextUsesCompactSummaries() = host(scale = 1.3f) { _, _ ->
        tag("today-fixed")
        assertEquals(60f, bounds(tag("today-fixed-summaries")).height().toFloat(), 1f)
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
        val visible = await { it.text?.toString()?.startsWith("a-") == true && bounds(it).top >= listBefore.top }
        val name = visible.text.toString()
        scenario.onActivity { rendered.value = state("b").copy(status = TodayPageStatus.LOADING, agenda = null) }
        tag("today-fixed-summaries")
        scenario.onActivity { rendered.value = state("b") }
        await { it.text?.toString() == "b-00" || it.contentDescription?.toString()?.contains("b-00") == true }
        scenario.onActivity { rendered.value = state("a").copy(status = TodayPageStatus.LOADING, agenda = null) }
        tag("today-fixed-summaries")
        scenario.onActivity { rendered.value = state("a") }
        val restored = await { it.text?.toString() == name && bounds(it).top >= listBefore.top }
        assertEquals(bounds(visible).top, bounds(restored).top)
    }
}
