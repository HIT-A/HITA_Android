package cn.limpu.hita.e2e

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.limpu.hita.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 黑盒 E2E 第二组：未登录态的可见性策略与空数据稳定性。
 *
 * 覆盖任务书要求的：主要导航（未登录可见项）、课表页面可进入、AI 入口在未登录时不可见、
 * 空数据/无网络时页面不崩溃。
 *
 * 登录后流程（课表导入、成绩查询、AI 会话）受硬编码 WebVPN 基地址限制，
 * 无法在真实 APK 上注入 MockWebServer（见 docs/testing/emulator-verification.md）。
 */
@RunWith(AndroidJUnit4::class)
class LoggedOutVisibilityE2eTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var device: UiDevice
    private lateinit var context: Context

    private val targetPackage get() = instrumentation.targetContext.packageName

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        context = instrumentation.targetContext
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun loggedOutTabSetHidesAccountOnlyEntries() {
        launch()

        // 真实策略（MainTab.visibleFor + OptionalTabFeatures 默认值）：
        //   始终可见：今日、时间表、更多
        //   待办：默认开启（OptionalTabFeatures.homework = true），与登录无关
        //   助手 / 资讯：默认关闭，且资讯还要求已登录校区
        val alwaysVisible = listOf("今日", "时间表", "更多")
        val accountOnly = listOf("助手", "资讯")

        // 底栏是 Compose 首帧后才出现，冷启动在 CI 模拟机上可能耗时十几秒。
        // 逐个标签串行 wait 会把预算按标签数放大，且任意一个慢就失败；这里共用
        // 一个截止时间，直到整组标签齐备或超时，再统一断言。
        val ready = waitForTabs(alwaysVisible, TAB_BUDGET_MS)
        val visibleFound = alwaysVisible.filter(::hasNavTab)
        assertTrue(
            "未登录时必须显示今日/时间表/更多；超时 ${TAB_BUDGET_MS}ms 后实际 $visibleFound",
            ready && visibleFound.size == alwaysVisible.size,
        )
        val accountOnlyFound = accountOnly.filter(::hasNavTab)
        assertFalse(
            "未登录时不应显示需账号/默认关闭的 Tab；却找到 $accountOnlyFound",
            accountOnlyFound.isNotEmpty(),
        )
    }

    /**
     * 底栏标签的可达性。
     *
     * `DockTabBar` 用 `semantics(mergeDescendants = true) { contentDescription = titles[index] }`
     * 把图标与文字合并成一个节点，标签文本因此不再是独立的 text 节点，只留在
     * `content-desc` 上；而 `MainPillTabBar` 仍暴露 Text。两种都要认。
     */
    private fun hasNavTab(title: String): Boolean =
        device.hasObject(By.textContains(title)) ||
            device.hasObject(By.descContains(title))

    /** 轮询直到给定标签全部出现；返回是否全部就绪，不抛异常，交由调用方统一断言。 */
    private fun waitForTabs(titles: List<String>, budgetMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + budgetMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (titles.all(::hasNavTab)) return true
            SystemClock.sleep(TAB_POLL_MS)
        }
        return false
    }

    @Test
    fun timetableSurfaceOpensWithoutAccount() {
        launch()
        // 点「时间表」后必须仍停留在被测包且不崩溃（未登录时课表为空态）。
        // 底栏是 Compose 首帧后才出现，先等标签就绪再找，避免启动时序竞争。
        waitForTabs(listOf("时间表"), TAB_BUDGET_MS)
        // 标签可能只在 content-desc 上（DockTabBar 合并了语义），两种都要找。
        val tab = device.wait(Until.findObject(By.textContains("时间表")), SHORT_MS)
            ?: device.wait(Until.findObject(By.descContains("时间表")), SHORT_MS)
        assertNotNull("未登录时底栏必须存在「时间表」入口", tab)
        tab!!.click()
        device.waitForIdle()
        SystemClock.sleep(SETTLE_MS)
        assertEquals("点击时间表后窗口必须仍属于被测包", targetPackage, device.currentPackageName)
        assertTrue(
            "课表区域必须渲染出内容",
            device.wait(Until.hasObject(By.pkg(targetPackage)), SHORT_MS),
        )
    }

    @Test
    fun appDoesNotCrashWithNoAccountAndNoNetwork() {
        // 关闭网络后启动：页面必须停留在应用内（显示空态或错误提示），不得崩溃退出。
        device.executeShellCommand("svc data disable")
        device.executeShellCommand("svc wifi disable")
        try {
            launch()
            assertEquals("断网启动后窗口必须属于被测包", targetPackage, device.currentPackageName)
            SystemClock.sleep(SETTLE_MS)
            assertEquals("断网静置后窗口必须仍属于被测包（不得崩溃）", targetPackage, device.currentPackageName)
        } finally {
            device.executeShellCommand("svc data enable")
            device.executeShellCommand("svc wifi enable")
        }
    }

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        checkNotNull(intent) { "诊断包必须可被系统解析启动" }
        context.startActivity(intent.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        // 先等窗口出现，再等它真正成为前台：`startActivity` 返回时往往还停在
        // 桌面或 Splash，此时 dump 里没有 Compose 底栏。
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_MS)
        val deadline = SystemClock.uptimeMillis() + LAUNCH_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.currentPackageName == targetPackage) break
            SystemClock.sleep(TAB_POLL_MS)
        }
        if (device.currentPackageName != targetPackage) {
            fail("应用未进入前台：currentPackageName=${device.currentPackageName}")
        }
        // 首次启动会弹「新公告」对话框，遮住整个界面（含底栏）。这不是被测行为，
        // 但会挡住后续 UI 查找，所以先按真实按钮文案关掉它。
        dismissStartupNoticeIfPresent()
    }

    /** 若存在启动公告弹窗，点「知道了」关闭；没有就什么都不做。 */
    private fun dismissStartupNoticeIfPresent() {
        val ack = context.getString(R.string.notice_popup_ack)
        val button = device.wait(Until.findObject(By.text(ack)), NOTICE_MS) ?: return
        button.click()
        device.waitForIdle()
        device.wait(Until.gone(By.text(ack)), NOTICE_MS)
    }

    private companion object {
        const val LAUNCH_MS = 15_000L
        const val SHORT_MS = 6_000L
        const val SETTLE_MS = 2_000L

        /** 启动公告弹窗出现与消失的等待窗口。 */
        const val NOTICE_MS = 8_000L

        /** 冷启动到 Compose 底栏首帧的总预算。CI 只用 2 核 4G 且串行跑四模块，冷启动明显更慢。 */
        const val TAB_BUDGET_MS = 45_000L
        const val TAB_POLL_MS = 200L
    }
}
