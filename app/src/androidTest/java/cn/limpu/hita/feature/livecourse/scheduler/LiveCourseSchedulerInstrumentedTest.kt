package cn.limpu.hita.feature.livecourse.scheduler

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.WorkManagerTestInitHelper
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.DisplayScopeResolver
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

        assertEquals(0, notifications.activeNotifications.size)
    }

    @Test
    fun enabledSettingPublishesDuringClassTime() {
        val now = System.currentTimeMillis()
        seedClassAt(start = now - TimeUnit.MINUTES.toMillis(5), end = now + TimeUnit.MINUTES.toMillis(55))
        settings.setEnabled(true)

        LiveCourseScheduler(app).reconcile(nowMillis = now)

        assertTrue(
            "课中必须发布直播课程通知",
            notifications.activeNotifications.isNotEmpty(),
        )
    }

    @Test
    fun cancelClearsPublishedNotification() {
        val now = System.currentTimeMillis()
        seedClassAt(start = now - TimeUnit.MINUTES.toMillis(5), end = now + TimeUnit.MINUTES.toMillis(55))
        settings.setEnabled(true)
        val scheduler = LiveCourseScheduler(app)
        scheduler.reconcile(nowMillis = now)
        assertTrue(notifications.activeNotifications.isNotEmpty())

        scheduler.cancel()

        assertEquals(0, notifications.activeNotifications.size)
    }


    @Test
    fun noUpcomingClassKeepsNotificationCleared() {
        settings.setEnabled(true)

        LiveCourseScheduler(app).reconcile(nowMillis = System.currentTimeMillis())

        assertEquals(0, notifications.activeNotifications.size)
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
