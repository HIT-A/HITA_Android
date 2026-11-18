package cn.limpu.hita.ui.main.timeline

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.ui.design.HitaComposeTheme
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayScopePickerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val first = TodayScopeOption(DisplayScope.Single("a"), "深圳 2026秋季", 1, true)
    private val second = TodayScopeOption(DisplayScope.Group("b"), "课程与个人安排".repeat(5), 2, false)
    private fun host(test: (() -> TodayAction?) -> Unit) {
        val window = TodayDateWindow.from(Instant.parse("2026-11-18T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val state = TodayUiState(window.start, window, scopeName = first.name, status = TodayPageStatus.READY,
            scopeOptions = listOf(first, second))
        var action: TodayAction? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme { Box(Modifier.width(320.dp)) {
                    TodayScopePicker(state, action = { action = it })
                } } }
            }) }
            test { action }
        }
    }
    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
        return null
    }
    private fun await(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, match)?.let { return it }
            Thread.sleep(30)
        }
        throw AssertionError("Scope picker node missing")
    }
    private fun text(value: String) = await { it.text?.toString() == value || it.contentDescription?.toString() == value }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun open() = click(text(context.getString(R.string.today_switch_scope, first.name)))
    @Test fun currentSelectionAndLongNamesRemainAccessibleAndSelectionUsesObject() = host { action ->
        open()
        text(context.getString(R.string.today_choose))
        await { (it.isSelected || (it.isCheckable && it.isChecked)) && (it.text?.toString()?.contains(first.name) == true ||
            find(it) { child -> child.text?.toString() == first.name } != null) }
        val long = text(second.name)
        val bounds = android.graphics.Rect().also(long::getBoundsInScreen)
        assertTrue("Long name should wrap", bounds.height() > 28 * context.resources.displayMetrics.density)
        click(long)
        assertEquals(TodayAction.SelectScope(second.scope), action())
        text(context.getString(R.string.today_switch_scope, first.name))
    }
    @Test fun managementUsesExistingRouteAndCurrentSelectionOnlyCloses() = host { action ->
        open(); click(text(first.name)); assertNull(action())
        open(); click(text(context.getString(R.string.title_timetable_manager)))
        assertEquals(TodayAction.SelectTimetable, action())
    }
}
