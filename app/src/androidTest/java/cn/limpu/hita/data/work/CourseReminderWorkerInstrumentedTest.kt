package cn.limpu.hita.data.work

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.source.preference.CourseReminderStore
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 课程提醒 Worker 的端到端行为：开关、显示范围过滤、去重发送、窗口外忽略。
 *
 * [CourseReminderWorker] 此前没有任何测试引用；本测试通过真实单例 Room + 真实 SharedPreferences
 * 驱动生产代码路径，只把"当前时间"间接控制为相对偏移，不注入假实现。
 */
@RunWith(AndroidJUnit4::class)
class CourseReminderWorkerInstrumentedTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val database get() = AppDatabase.getDatabase(app)
    private val notificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val timetableId = "worker-test-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        // Worker 内部会 enqueue 后续 Work；测试进程必须显式初始化 WorkManager。
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        clearReminderState()
        database.timetableDao().saveTimetableSync(testTimetable(timetableId))
        DisplayScopeResolver(app).persistSelection(DisplayScope.Single(timetableId))
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        clearReminderState()
        database.eventItemDao().deleteEventsFromTimetablesSync(listOf(timetableId))
        database.timetableDao().deleteTimetablesInIdsSync(listOf(timetableId))
    }

    private fun clearReminderState() {
        app.getSharedPreferences("course_reminder", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(SENT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun disabledSettingReturnsSuccessWithoutNotifying() {
        insertClass(timetableId, minutesFromNow = 10)
        CourseReminderStore(app).setEnabled(false)

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        assertEquals("不得记录任何发送", 0, sentReminderCount())
    }

    @Test
    fun enabledSettingRecordsSentReminderAfterPublishing() {
        insertClass(timetableId, minutesFromNow = 10)
        CourseReminderStore(app).apply {
            setEnabled(true)
            setReminderMinutes(15)
        }

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        // 通知由被测应用进程发出，仪器测试进程无法通过 getActiveNotifications()
        // 观察其他进程的通知；用 Worker 自己写回的发送记录作为可观测证据。
        assertEquals("Worker 必须记录本次已发送提醒", 1, sentReminderCount())
    }

    @Test
    fun eventIsNotNotifiedTwiceWithinOneDay() {
        insertClass(timetableId, minutesFromNow = 10)
        CourseReminderStore(app).apply {
            setEnabled(true)
            setReminderMinutes(15)
        }

        buildWorker().doWork()
        buildWorker().doWork()

        assertEquals("同一天同一事件只应记录一次发送", 1, sentReminderCount())
    }

    @Test
    fun eventOutsideReminderWindowIsIgnored() {
        insertClass(timetableId, minutesFromNow = 40)
        CourseReminderStore(app).apply {
            setEnabled(true)
            setReminderMinutes(15)
        }

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        assertEquals("不得记录任何发送", 0, sentReminderCount())
    }

    @Test
    fun eventInOtherTimetableIsIgnoredByDisplayScopeFilter() {
        val otherId = "worker-other-${UUID.randomUUID()}"
        try {
            database.timetableDao().saveTimetableSync(testTimetable(otherId))
            insertClass(otherId, minutesFromNow = 10)
            CourseReminderStore(app).apply {
                setEnabled(true)
                setReminderMinutes(15)
            }

            buildWorker().doWork()

            assertEquals("不得记录任何发送", 0, sentReminderCount())
        } finally {
            database.eventItemDao().deleteEventsFromTimetablesSync(listOf(otherId))
            database.timetableDao().deleteTimetablesInIdsSync(listOf(otherId))
        }
    }

    private fun sentReminderCount(): Int =
        app.getSharedPreferences(SENT_PREFS, Context.MODE_PRIVATE)
            .getStringSet("sent_ids", emptySet()).orEmpty().size

    private fun buildWorker(): CourseReminderWorker =
        TestListenableWorkerBuilder<CourseReminderWorker>(app).build()

    private fun insertClass(targetTimetableId: String, minutesFromNow: Int): String {
        val start = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutesFromNow.toLong())
        val event = EventItem().apply {
            id = "event-${UUID.randomUUID()}"
            type = EventItem.TYPE.CLASS
            name = "测试课程"
            place = "A101"
            this.timetableId = targetTimetableId
            subjectId = "subject-${UUID.randomUUID()}"
            from = Timestamp(start)
            to = Timestamp(start + TimeUnit.HOURS.toMillis(1))
        }
        database.eventItemDao().insertEventSync(event)
        return event.id
    }

    private companion object {
        const val SENT_PREFS = "course_reminder_sent"
    }

    private fun testTimetable(id: String) = Timetable().apply {
        this.id = id
        name = "Worker 测试课表"
        startTime = Timestamp(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        endTime = Timestamp(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(120))
    }
}
