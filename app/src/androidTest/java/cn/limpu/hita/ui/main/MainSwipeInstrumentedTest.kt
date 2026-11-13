package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cn.limpu.hita.data.repository.EasSettingsRepository
import cn.limpu.hita.data.source.preference.OptionalTabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主界面左右滑动切页的仪器测试。
 *
 * 断言两类约束：
 * - 结构：时间表页滑不动、首尾页不回绕、关掉的 Tab 不出现在可切列表。
 * - 步进：一次滑动只推进/回退一页。收尾动画期间手势会被忽略（`settling` 闸门），
 *   因此 `input swipe` 注入的连续事件不会叠加出两页。
 *
 * 精确的判定阈值由 `TabSwipeTest` 用纯函数覆盖（`commitDirection` 只返回 -1/0/1），
 * 这里只验证端到端的落点。
 */
@RunWith(AndroidJUnit4::class)
class MainSwipeInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun timetableDoesNotParticipateInPaging() = launch { scenario ->
        val visible = selectTab(scenario, MainTab.TIMETABLE)
        // 时间表的左右拖动是切周次，这里既不能前进也不能后退。
        swipe(scenario, left = true)
        swipe(scenario, left = false)
        // 时间表不参与翻页：等一个足够长的窗口后再确认它没变。
        SystemClock.sleep(600)
        assertEquals(MainTab.TIMETABLE, awaitSelectedTab(scenario, MainTab.TIMETABLE))
        assertTrue(visible.contains(MainTab.TIMETABLE))
    }

    @Test fun firstPageNeverWrapsToTheLastPage() = launch { scenario ->
        val visible = selectTab(scenario, MainTab.TIMELINE)
        swipe(scenario, left = false)
        SystemClock.sleep(600)
        assertEquals(MainTab.TIMELINE, awaitSelectedTab(scenario, MainTab.TIMELINE))
        assertEquals(visible.first(), MainTab.TIMELINE)
    }

    @Test fun lastPageNeverWrapsToTheFirstPage() = launch { scenario ->
        val visible = selectTab(scenario, MainTab.NAVIGATION)
        swipe(scenario, left = true)
        SystemClock.sleep(600)
        assertEquals(MainTab.NAVIGATION, awaitSelectedTab(scenario, MainTab.NAVIGATION))
        assertEquals(visible.last(), MainTab.NAVIGATION)
    }

    @Test fun oneSwipeAdvancesExactlyOnePage() = launch { scenario ->
        val visible = selectTab(scenario, MainTab.TIMETABLE)
        val neighbours = visible.takeLast(2)
        // 时间表自身不参与翻页，从它的右邻起测：该页两侧都有真实相邻页。
        selectTabDirect(scenario, neighbours.first())
        assertEquals(neighbours.first(), awaitSelectedTab(scenario, neighbours.first()))
        swipe(scenario, left = true)
        assertEquals(neighbours.last(), awaitSelectedTab(scenario, neighbours.last()))
    }

    @Test fun oneSwipeGoesBackExactlyOnePage() = launch { scenario ->
        val visible = selectTab(scenario, MainTab.TIMETABLE)
        val neighbours = visible.takeLast(2)
        selectTabDirect(scenario, neighbours.last())
        assertEquals(neighbours.last(), awaitSelectedTab(scenario, neighbours.last()))
        swipe(scenario, left = false)
        assertEquals(neighbours.first(), awaitSelectedTab(scenario, neighbours.first()))
    }

    @Test fun disabledOptionalTabNeverAppearsAsSwipeTarget() = launch { scenario ->
        // 关掉助手与资讯后，底栏里不应再有这两页，滑动也不可能落到它们身上。
        val store = OptionalTabStore(app)
        val original = store.features()
        try {
            store.setHomework(true)
            store.setAgent(false)
            store.setInfo(false)
            val visible = applyVisibleTabs(scenario)
            assertTrue(MainTab.AGENT !in visible)
            assertTrue(MainTab.BLOG !in visible)

            selectTabDirect(scenario, MainTab.TIMELINE)
            // 可切列表必须只含真实可见页：滑动目标由它推导，因此关掉的 Tab 天然不可达。
            assertEquals(4, visible.size)
            assertTrue(visible.containsAll(listOf(MainTab.TIMELINE, MainTab.TIMETABLE, MainTab.HOMEWORK, MainTab.NAVIGATION)))
            swipe(scenario, left = true)
            SystemClock.sleep(600)
            val reached = currentTab(scenario)
            assertTrue(reached != MainTab.AGENT && reached != MainTab.BLOG)
        } finally {
            // 设备级偏好会被后续测试（含 E2E 未登录可见性）继承，必须还原。
            store.setHomework(original.homework)
            store.setInfo(original.info)
            store.setAgent(original.agent)
        }
    }

    private fun selectTab(scenario: ActivityScenario<MainActivity>, tab: MainTab): List<MainTab> {
        val visible = applyVisibleTabs(scenario)
        selectTabDirect(scenario, tab)
        assertEquals(tab, awaitSelectedTab(scenario, tab))
        return visible
    }

    private fun applyVisibleTabs(scenario: ActivityScenario<MainActivity>): List<MainTab> {
        var tabs: List<MainTab> = emptyList()
        scenario.onActivity {
            val model = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]
            model.refreshVisibleTabs()
            tabs = model.state.value.navigation.visibleTabs
        }
        return tabs
    }

    private fun selectTabDirect(scenario: ActivityScenario<MainActivity>, tab: MainTab) {
        scenario.onActivity {
            val model = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]
            val index = model.state.value.navigation.visibleTabs.indexOf(tab)
            if (index >= 0) model.selectTab(index)
        }
        instrumentation.waitForIdleSync()
    }

    private fun currentTab(scenario: ActivityScenario<MainActivity>): MainTab {
        var selected = MainTab.TIMELINE
        scenario.onActivity {
            selected = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]
                .state.value.navigation.selectedTab
        }
        return selected
    }

    /** 切页含两段 150ms 收尾动画、状态经 ViewModel 回流，且 Fragment 随后要切到 RESUMED。 */
    private fun awaitSelectedTab(
        scenario: ActivityScenario<MainActivity>,
        expected: MainTab? = null,
    ): MainTab {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var selected = MainTab.TIMELINE
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            selected = currentTab(scenario)
            val settled = expected == null || selected == expected
            val mounted = selected.let { tab ->
                var attached = false
                scenario.onActivity { activity ->
                    attached = activity.supportFragmentManager
                        .findFragmentByTag(MainTabHost.tag(tab))?.view?.isAttachedToWindow == true
                }
                attached
            }
            if (settled && mounted) return selected
            Thread.sleep(50)
        }
        return selected
    }

    /**
     * 在内容区中部做一次横向滑动，走的是主界面 Compose 手势的完整路径。
     *
     * 坐标取自窗口本身而不是某个子 View 的可见矩形：分页容器由 Compose 的 AndroidView
     * 托管，挂载前后可见性会短暂为 false，按子 View 取矩形会落到 (0,0) 而点在状态栏上。
     * 纵向取 55% 高度：避开顶部工具栏与底部导航栏，正落在页面内容区。
     */
    private fun swipe(scenario: ActivityScenario<MainActivity>, left: Boolean) {
        val device = UiDevice.getInstance(instrumentation)
        val size = device.displayWidth to device.displayHeight
        val y = (size.second * 0.55f).toInt()
        val from = (size.first * if (left) 0.82f else 0.18f).toInt()
        val to = (size.first * if (left) 0.18f else 0.82f).toInt()
        // 走 `input swipe`：与真机手测同一条路径，能稳定驱动 Compose 手势。
        // `UiDevice.swipe` 与手工 injectInputEvent 在这台模拟器上会与活动生命周期
        // 竞争（ActivityScenario 的 idle 等待插在 MOVE 之间），事件序列被打断。
        instrumentation.uiAutomation.executeShellCommand(
            "input swipe $from $y $to $y $SWIPE_DURATION_MS"
        ).close()
        // 两段 150ms 收尾 + 状态回流与 Fragment 事务，等它走完再断言，避免读到中间态。
        SystemClock.sleep(900)
    }

    private companion object {
        /** 与真机手测一致的 250ms 滑动时长，足以越过接管阈值并形成可提交的速度。 */
        const val SWIPE_DURATION_MS = 250
    }

    private fun launch(block: (ActivityScenario<MainActivity>) -> Unit) {
        val settings = EasSettingsRepository(app)
        val enabled = settings.isAutoReimportEnabled()
        settings.setAutoReimport(false)
        try {
            ActivityScenario.launch<MainActivity>(
                Intent(app, MainActivity::class.java).putExtra("live_course_id", "external-course")
            ).use(block)
        } finally {
            settings.setAutoReimport(enabled)
        }
    }
}
