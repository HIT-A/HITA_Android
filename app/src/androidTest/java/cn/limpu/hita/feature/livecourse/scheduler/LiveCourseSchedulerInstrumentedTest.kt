package cn.limpu.hita.feature.livecourse.scheduler

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.WorkManagerTestInitHelper
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.feature.livecourse.notification.LiveCourseNotificationManager
import cn.limpu.hita.feature.livecourse.settings.LiveCourseSettings
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 直播课程调度：设置关闭时必须取消而非发布；启用后按注入时间解析状态。
 *
 * 该调度器此前没有任何测试引用；[LiveCourseScheduler.reconcile] 接受注入的 `nowMillis`，
 * 因此可以确定性地覆盖「课前/课中/结束」边界，不需要 sleep。
 */
@RunWith(AndroidJUnit4::class)
class LiveCourseSchedulerInstrumentedTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val database get() = AppDatabase.getDatabase(app)
    private val settings get() = LiveCourseSettings(app)
    private val notifications get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 只统计本应用直播课程频道下的通知，避免同批次其它测试遗留通知造成顺序依赖。 */
    private fun liveCourseNotifications(): Int = notifications.activeNotifications.count {
        it.notification.channelId == LiveCourseNotificationManager.CHANNEL_ID
    }

    private val timetableId = "live-test-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // 通知发布需要运行时权限；用 uiAutomation 直接授予，避免依赖 test:rules。
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        settings.setEnabled(false)
        notifications.cancelAll()
        // LiveCourseScheduler 在 reconcile 中会 enqueue 周期 Work；测试进程默认没有
        // WorkManager 初始化器，必须显式装配，否则 reconcile 抛出 IllegalStateException，
        // 通知永远不会发布（那是测试环境缺陷，不是生产缺陷）。
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        database.timetableDao().saveTimetableSync(timetable())
        DisplayScopeResolver(app).persistSelection(DisplayScope.Single(timetableId))
    }

    @After
    fun tearDown() {
        LiveCourseScheduler(app).cancel()
        settings.setEnabled(false)
        notifications.cancelAll()
        database.eventItemDao().deleteEventsFromTimetablesSync(listOf(timetableId))
        database.timetableDao().deleteTimetablesInIdsSync(listOf(timetableId))
    }

    @Test
    fun disabledSettingCancelsWithoutPublishingNotification() {
        seedClass(startOffsetMinutes = -5, durationMinutes = 60)
        settings.setEnabled(false)

        LiveCourseScheduler(app).reconcile(nowMillis = System.currentTimeMillis())

        assertEquals(0, liveCourseNotifications())
    }

    @Test
    fun enabledSettingPublishesDuringClassTime() {
        val now = System.currentTimeMillis()
        seedClassAt(start = now - TimeUnit.MINUTES.toMillis(5), end = now + TimeUnit.MINUTES.toMillis(55))
        settings.setEnabled(true)

        LiveCourseScheduler(app).reconcile(nowMillis = now)

        assertTrue(
            "课中必须发布直播课程通知",
            liveCourseNotifications() > 0,
        )
    }

    @Test
    fun cancelClearsPublishedNotification() {
        val now = System.currentTimeMillis()
        seedClassAt(start = now - TimeUnit.MINUTES.toMillis(5), end = now + TimeUnit.MINUTES.toMillis(55))
        settings.setEnabled(true)
        val scheduler = LiveCourseScheduler(app)
        scheduler.reconcile(nowMillis = now)
        assertTrue("课中必须先发布通知", liveCourseNotifications() > 0)

        scheduler.cancel()

        // 通知由被测进程发布/取消；测试进程的 activeNotifications 会缓存旧视图，
        // 且系统侧清理是异步的。因此轮询系统中立视图，给出有界等待窗口，
        // 而不是立即断言（立即断言实测会捕获到清理中的瞬时状态）。
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        var remaining = liveCourseNotificationsOnDevice()
        while (remaining > 0 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            remaining = liveCourseNotificationsOnDevice()
        }
        assertEquals(
            "cancel 后系统侧不得残留该频道通知（等待窗口 3000ms 后）",
            0,
            remaining,
        )
    }

    /** 通过 dumpsys 读取系统中立视图（不受本进程缓存影响）。 */
    private fun liveCourseNotificationsOnDevice(): Int {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys notification --noredact | grep -c 'channel=hita_live_course'")
        val out = java.io.ByteArrayOutputStream()
        java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        }
        descriptor.close()
        // 设备端 `grep -c` 只输出一个数字，直接解析即可。
        val text = out.toString(Charsets.UTF_8.name())
        return text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull() ?: 0
    }

    @Test
    fun noUpcomingClassKeepsNotificationCleared() {
        settings.setEnabled(true)

        LiveCourseScheduler(app).reconcile(nowMillis = System.currentTimeMillis())

        assertEquals(0, liveCourseNotifications())
    }

    private fun seedClass(startOffsetMinutes: Int, durationMinutes: Int) {
        val start = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(startOffsetMinutes.toLong())
        seedClassAt(start, start + TimeUnit.MINUTES.toMillis(durationMinutes.toLong()))
    }

    private fun seedClassAt(start: Long, end: Long) {
        val event = EventItem().apply {
            id = "live-${UUID.randomUUID()}"
            type = EventItem.TYPE.CLASS
            name = "直播测试课程"
            place = "B203"
            timetableId = this@LiveCourseSchedulerInstrumentedTest.timetableId
            subjectId = "subject-${UUID.randomUUID()}"
            from = Timestamp(start)
            to = Timestamp(end)
        }
        database.eventItemDao().insertEventSync(event)
    }

    private fun timetable() = Timetable().apply {
        id = timetableId
        name = "直播测试课表"
        startTime = Timestamp(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        endTime = Timestamp(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(120))
    }
}
