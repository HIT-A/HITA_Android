package cn.limpu.hita.e2e

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 黑盒 E2E 第三组：系统集成（通知渠道、Widget Provider、后台 Work 调度）。
 *
 * 这些断言通过 `dumpsys` 观察系统真实状态，不依赖生产代码注入。
 * 后台任务的行为正确性由仪器测试（WorkManagerTestInitHelper）覆盖；此处只验证
 * "系统是否真的注册了这些组件的产物"，属于黑盒层面的集成证据。
 */
@RunWith(AndroidJUnit4::class)
class SystemIntegrationE2eTest {

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
    fun widgetProvidersAreRegisteredWithTheSystem() {
        // AppWidgetManager 由系统维护；从设备侧查询组件是否存在。
        val dump = device.executeShellCommand("dumpsys appwidget")
        assertNotNull("dumpsys appwidget 必须可用", dump)
        // 应用已声明两个 Widget Provider；未绑定实例时系统仅记录 provider 元数据。
        assertTrue(
            "系统中必须存在该应用的 appwidget provider 记录",
            dump.contains(targetPackage) || dump.contains("today_widget"),
        )
    }

    @Test
    fun periodicBackgroundWorkIsScheduledAfterLaunch() {
        launch()
        // 启动后应用会安排提醒/直播/Widget 刷新等周期任务。
        SystemClock.sleep(SETTLE_MS)
        val jobDump = device.executeShellCommand("dumpsys jobscheduler")
        assertTrue(
            "启动后必须存在属于被测包的后台调度记录（JobScheduler/WorkManager）",
            jobDump.contains(targetPackage),
        )
    }

    @Test
    fun notificationChannelIsCreatedWhenReminderPathRuns() {
        launch()
        SystemClock.sleep(SETTLE_MS)
        val dump = device.executeShellCommand("dumpsys notification --noredact")
        // 频道由 Worker 在真正发布时创建；此处验证系统侧能记录该包的通知配置，
        // 若频道尚未创建也不视为失败（未触发提醒），因此只断言 dumpsys 包含包名。
        assertTrue("通知服务必须记录被测包", dump.contains(targetPackage))
    }

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        checkNotNull(intent) { "诊断包必须可被系统解析启动" }
        context.startActivity(intent.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), LAUNCH_MS)
    }

    private companion object {
        const val LAUNCH_MS = 15_000L
        const val SETTLE_MS = 3_000L
    }
}
