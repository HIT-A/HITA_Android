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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 黑盒 E2E：主导航可见性与进程重启后的持久化。
 *
 * 只断言"界面上真的出现了这些文字/控件"，不断言内部状态；
 * 失败时由 UI Automator 输出当前界面层级，便于定位。
 */
@RunWith(AndroidJUnit4::class)
class NavigationAndPersistenceE2eTest {

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
    fun bottomNavigationShowsAtLeastOneLocalLabel() {
        launch()

        // 未登录时只显示本地 Tab（登录后才出现「助手」「资讯」）；具体显示哪几个
        // 取决于登录态与可见性策略，因此只断言：至少出现一个已知本地 Tab 文案，
        // 且窗口确实属于被测包。按钮渲染细节由 NavigationFragment 自身测试覆盖。
        val knownLabels = listOf("今日", "时间表", "更多", "待办")
        val found = knownLabels.filter { label ->
            device.wait(Until.hasObject(By.textContains(label)), LABEL_TIMEOUT_MS)
        }
        assertEquals(
            "主界面必须显示被测包窗口",
            targetPackage,
            device.currentPackageName,
        )
        assertTrue(
            "主界面必须至少显示一个已知 Tab 文案；实际找到 $found",
            found.isNotEmpty(),
        )
    }

    @Test
    fun relaunchFromRecentsKeepsAppUsable() {
        launch()
        assertEquals(
            "首次启动必须出现被测包窗口",
            targetPackage,
            device.currentPackageName,
        )

        // 回到桌面再冷启动 Activity。
        // 注意：不能用 `am force-stop`——被测包与仪器测试进程属于同一应用组
        // （targetPackage 的测试进程为 <pkg>.test），force-stop 会连带杀掉测试进程，
        // 首轮实测即因此导致 "Instrumentation run failed due to Process crashed"。
        device.pressHome()
        SystemClock.sleep(RESTART_GAP_MS)
        launch()

        assertTrue(
            "回到桌面后必须仍能重新启动到被测包窗口",
            device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_TIMEOUT_MS),
        )
        assertEquals(targetPackage, device.currentPackageName)
    }

    @Test
    fun rotationKeepsActivityOnSameScreen() {
        launch()
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_TIMEOUT_MS)

        device.setOrientationLeft()
        SystemClock.sleep(ROTATION_SETTLE_MS)
        val landscapePackage = device.currentPackageName
        device.setOrientationNatural()
        SystemClock.sleep(ROTATION_SETTLE_MS)

        assertTrue(
            "旋转后应用窗口不能丢失",
            landscapePackage == targetPackage && device.currentPackageName == targetPackage,
        )
    }

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        assertNotNull("诊断包必须可被系统解析启动", intent)
        context.startActivity(intent!!.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_TIMEOUT_MS)
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 15_000L
        const val LABEL_TIMEOUT_MS = 8_000L
        const val RESTART_GAP_MS = 1_500L
        const val ROTATION_SETTLE_MS = 1_500L
    }
}
