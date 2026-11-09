package cn.limpu.hita.data.work

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import cn.limpu.hita.ui.widgets.WidgetUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Widget 刷新链：Worker 调用 [WidgetUtils.sendRefreshToAll]，只对已安装组件广播。
 *
 * 该链路此前没有任何测试引用。测试在真实 AppWidgetManager 上运行，
 * 通过注册动态接收器观察真实广播，不伪造发送结果。
 */
@RunWith(AndroidJUnit4::class)
class WidgetRefreshWorkerInstrumentedTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = AppWidgetManager.getInstance(app)

    private fun installedWidgetIds(): List<Int> =
        WidgetUtils.widgets.flatMap { manager.getAppWidgetIds(ComponentName(app, it)).toList() }

    @Test
    fun workerReturnsSuccessAndDoesNotBroadcastWhenNoWidgetInstalled() {
        val received = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WidgetUtils.EVENT_REFRESH) received.countDown()
            }
        }
        ContextCompatRegister(app, receiver, IntentFilter(WidgetUtils.EVENT_REFRESH))
        try {
            // 前提检查：本测试环境确实没有安装任何 Widget，否则断言不成立。
            assertTrue(
                "测试前提：不得有已安装 Widget",
                installedWidgetIds().isEmpty(),
            )
            val worker: WidgetRefreshWorker =
                TestListenableWorkerBuilder<WidgetRefreshWorker>(app).build()

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertFalse(
                "无 Widget 时不得广播刷新",
                received.await(300, TimeUnit.MILLISECONDS),
            )
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    @Before
    fun setUp() {
        // Worker 内部会 enqueue 后续 Work；测试进程必须显式初始化 WorkManager。
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test
    fun refreshBroadcastIsSentOnlyWhenAWidgetIsInstalled() {
        val received = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WidgetUtils.EVENT_REFRESH) received.countDown()
            }
        }
        // 显式注册为 exported=false 的动态接收器，避免依赖隐式广播策略。
        ContextCompatRegister(app, receiver, IntentFilter(WidgetUtils.EVENT_REFRESH))
        try {
            WidgetUtils.sendRefreshToAll(app)
            val hasWidgets = installedWidgetIds().isNotEmpty()
            if (hasWidgets) {
                assertTrue(
                    "存在已安装 Widget 时必须广播刷新",
                    received.await(3, TimeUnit.SECONDS),
                )
            } else {
                assertFalse(
                    "没有已安装 Widget 时不应广播",
                    received.await(300, TimeUnit.MILLISECONDS),
                )
            }
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    @Test
    fun hasAnyWidgetMatchesInstalledProviders() {
        assertEquals(installedWidgetIds().isNotEmpty(), WidgetUtils.hasAnyWidget(app))
    }

    private fun ContextCompatRegister(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        // API 33+ 要求显式标志；本项目 minSdk 26，故按版本分支。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
