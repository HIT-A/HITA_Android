package cn.limpu.hita.perf

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 性能专项：冷启动到主界面可交互的耗时，带回归阈值。
 *
 * 这是任务书"性能专项测试集"的真实载体；它以黑盒方式测量，不依赖生产代码内埋点。
 * 阈值取实测值的宽松上界（模拟器较慢），目的是拦住显著回退（例如启动链新增阻塞 IO），
 * 而不是追求精确基准数字。
 */
@RunWith(AndroidJUnit4::class)
class StartupLatencyPerfTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private val targetPackage get() = instrumentation.targetContext.packageName
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        context = instrumentation.targetContext
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun firstLaunchToInteractiveSurfaceStaysUnderBudget() {
        // 注意：不能用 `am force-stop $targetPackage` 制造冷启动——被测包与仪器测试进程
        // 同属一个应用组（<pkg> 与 <pkg>.test），force-stop 会一并杀掉测试进程
        // （实测报 "Instrumentation run failed due to Process crashed"）。
        // 因此在测试进程存活的前提下测量"从发出启动 Intent 到主窗口出现"的耗时，
        // 这仍是启动路径的真实耗时，只是不含系统进程冷加载部分。
        SystemClock.sleep(COLD_GAP_MS)

        val start = SystemClock.elapsedRealtime()
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        checkNotNull(intent) { "诊断包必须可被系统解析启动" }
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })

        val shown = device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), BUDGET_MS)
        val elapsed = SystemClock.elapsedRealtime() - start

        assertTrue("主界面必须在预算 ${BUDGET_MS}ms 内出现，实际 ${elapsed}ms", shown)
        assertTrue(
            "启动到首窗耗时 ${elapsed}ms 超过预算 ${BUDGET_MS}ms（可能引入启动阻塞）",
            elapsed <= BUDGET_MS,
        )
        // 记录实测值，便于在报告里横向对比。
        println("PERF launch-to-window-ms=$elapsed")
    }

    @Test
    fun warmStartToInteractiveSurfaceStaysUnderBudget() {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)!!
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), BUDGET_MS)

        // 已在栈中：再启动一次。
        device.pressHome()
        SystemClock.sleep(COLD_GAP_MS)
        val start = SystemClock.elapsedRealtime()
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        val shown = device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), BUDGET_MS)
        val elapsed = SystemClock.elapsedRealtime() - start

        assertTrue("热启动也必须出现主界面", shown)
        assertTrue("热启动 ${elapsed}ms 不应超过预算 ${BUDGET_MS}ms", elapsed <= BUDGET_MS)
        println("PERF warm-start-to-window-ms=$elapsed")
    }

    private companion object {
        /** 启动预算：模拟器（swiftshader 软渲染）实测数秒，取 8s 作为回归上界。 */
        const val BUDGET_MS = 8_000L
        const val COLD_GAP_MS = 1_000L
    }
}
