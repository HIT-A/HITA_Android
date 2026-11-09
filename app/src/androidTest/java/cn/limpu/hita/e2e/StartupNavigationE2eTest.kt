package cn.limpu.hita.e2e

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
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
 * 黑盒模拟机 E2E：安装后的真实 APK（诊断包 `cn.limpu.hita.diag`）启动与主导航。
 *
 * 与既有 849 个仪器测试的区别：那些测试驱动 ViewModel/Repository，
 * 本类通过 UI Automator 观察真实界面元素，属于任务书第四类测试。
 */
@RunWith(AndroidJUnit4::class)
class StartupNavigationE2eTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var device: UiDevice
    private lateinit var context: Context

    private val targetPackage get() = instrumentation.targetContext.packageName

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        context = instrumentation.targetContext
        device.pressHome()
        // 清理可能残留的前一进程
        device.waitForIdle()
    }

    @Test
    fun coldStartShowsDiagnosticLauncherAndMainSurface() {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        assertNotNull("诊断包必须可被系统解析启动", intent)
        context.startActivity(intent!!.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })

        assertTrue(
            "应用窗口必须在超时前出现",
            device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_TIMEOUT_MS),
        )
        // 主界面必须渲染出至少一个文本控件；用无深度约束的查询，避免绑定具体布局层级。
        val hasText = device.wait(
            Until.hasObject(By.pkg(targetPackage).clazz("android.widget.TextView")),
            CONTENT_TIMEOUT_MS,
        )
        assertTrue("主界面必须渲染出文本控件", hasText)
    }

    @Test
    fun applicationStaysForegroundWithoutCrash() {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)!!
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_TIMEOUT_MS)
        assertEquals(
            "启动后当前窗口必须属于被测包",
            targetPackage,
            device.currentPackageName,
        )

        // 静置一小段真实时间后，窗口仍属于被测包（未崩溃、未被踢出）
        SystemClock.sleep(SETTLE_MS)
        assertEquals(
            "静置后窗口必须仍属于被测包",
            targetPackage,
            device.currentPackageName,
        )
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 15_000L
        const val CONTENT_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 2_000L
    }
}
