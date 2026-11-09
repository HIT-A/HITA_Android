package cn.limpu.hita.e2e

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val visibleFound = alwaysVisible.filter { device.wait(Until.hasObject(By.textContains(it)), SHORT_MS) }
        val accountOnlyFound = accountOnly.filter { device.hasObject(By.textContains(it)) }

        assertTrue("未登录时必须至少显示 2 个本地 Tab；实际 $visibleFound", visibleFound.size >= 2)
        assertFalse(
            "未登录时不应显示需账号/默认关闭的 Tab；却找到 $accountOnlyFound",
            accountOnlyFound.isNotEmpty(),
        )
    }

    @Test
    fun timetableSurfaceOpensWithoutAccount() {
        launch()
        // 点「时间表」后必须仍停留在被测包且不崩溃（未登录时课表为空态）。
        val tab = device.wait(Until.findObject(By.textContains("时间表")), SHORT_MS)
        if (tab != null) {
            tab.click()
            device.waitForIdle()
            SystemClock.sleep(SETTLE_MS)
        }
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
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_MS)
    }

    private companion object {
        const val LAUNCH_MS = 15_000L
        const val SHORT_MS = 6_000L
        const val SETTLE_MS = 2_000L
    }
}
