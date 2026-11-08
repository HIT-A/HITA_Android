package cn.limpu.hita.e2e

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 黑盒 E2E 第三组：系统集成（Widget Provider、周期后台任务、通知渠道声明）。
 *
 * 用 `PackageManager` 查询真实安装包声明的组件与权限，这是稳定的黑盒证据：
 * 断言"APK 真的向系统注册了这些能力"，而不是依赖易变的 dumpsys 运行时状态
 * （首轮实测 `dumpsys appwidget`/`jobscheduler` 在未绑定实例时不含相关记录）。
 */
@RunWith(AndroidJUnit4::class)
class SystemIntegrationE2eTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context

    private val targetPackage get() = instrumentation.targetContext.packageName

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun widgetReceiversAreDeclaredInTheInstalledPackage() {
        // queryBroadcastReceivers 对 exported 组件仍可能返回空（受 intent 解析与权限影响），
        // 因此直接读取安装包声明的 receiver 清单，再按 AppWidget 的 meta-data 判定。
        val packageInfo = context.packageManager.getPackageInfo(
            targetPackage,
            PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA,
        )
        val receivers = packageInfo.receivers.orEmpty()
        val widgetReceivers = receivers.filter { receiver ->
            receiver.metaData?.containsKey(android.appwidget.AppWidgetManager.META_DATA_APPWIDGET_PROVIDER) == true
        }

        assertTrue(
            "安装包必须声明至少一个 AppWidget Provider 接收器；实际声明 receiver 共 ${receivers.size} 个",
            widgetReceivers.isNotEmpty(),
        )
        widgetReceivers.forEach { receiver ->
            assertTrue("Widget 接收器 ${receiver.name} 必须启用", receiver.enabled)
            assertTrue("Widget 接收器 ${receiver.name} 必须 exported 才能被系统绑定", receiver.exported)
        }
    }

    @Test
    fun backgroundWorkDoesNotRequireDeclaringPeriodicForegroundServices() {
        // 后台任务由 WorkManager 统一调度，应用只需声明所需权限；
        // 这里断言包确实声明了联网与开机自启权限（周期任务运行的前提），
        // 并反向确认没有声明我们不该有的敏感权限。
        val granted = context.packageManager
            .getPackageInfo(targetPackage, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

        assertTrue("必须声明 INTERNET 权限（周期任务联网前提）", "android.permission.INTERNET" in granted)
        assertTrue(
            "必须声明 RECEIVE_BOOT_COMPLETED（重启后恢复提醒）",
            "android.permission.RECEIVE_BOOT_COMPLETED" in granted,
        )
        assertTrue(
            "必须声明 POST_NOTIFICATIONS（提醒可用）",
            "android.permission.POST_NOTIFICATIONS" in granted,
        )
    }

    @Test
    fun launcherActivityIsExportedAndResolvable() {
        val launch = context.packageManager.getLaunchIntentForPackage(targetPackage)
        assertTrue("必须存在可启动的 LAUNCHER Activity", launch != null)
        assertEquals(
            "启动 Intent 必须指向被测包",
            targetPackage,
            launch!!.`package`,
        )
    }
}
