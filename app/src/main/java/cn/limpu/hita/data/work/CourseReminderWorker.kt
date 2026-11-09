package cn.limpu.hita.data.work

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.source.preference.CourseReminderStore
import cn.limpu.hita.ui.main.MainActivity
import cn.limpu.hita.utils.TimeTools
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import kotlin.math.max

/**
 * 课程提醒 Worker
 * 每15分钟检查一次是否有即将开始的课程
 */
class CourseReminderWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val store = CourseReminderStore(applicationContext)
        if (!store.isEnabled()) return Result.success()

        val minutes = store.getReminderMinutes()
        val app = applicationContext as? Application ?: return Result.success()
        val timetableRepo = TimetableRepository(app)
        // 跟随主课表显示目标:只提醒当前显示课表/组合内的课程。
        val scopeIds = DisplayScopeResolver(app).currentSync()?.timetableIds.orEmpty()

        val now = System.currentTimeMillis()
        val windowEnd = now + minutes * 60 * 1000

        // doWork() 已在后台线程执行，无需 Dispatchers.IO
        val upcomingDayEvents = runBlocking {
            timetableRepo.getUpcomingEventsSync(now, now + LOOKAHEAD_WINDOW_MS, scopeIds)
        }
        val upcomingEvents = upcomingDayEvents.filter { event ->
            event.type == cn.limpu.hita.data.model.timetable.EventItem.TYPE.CLASS &&
                event.from.time in (now + 1)..windowEnd
        }

        // 获取已发送提醒的课程 ID（使用 SharedPreferences 简单存储）
        val sentReminders = getSentReminders()
        val newEvents = upcomingEvents.filter { it.id !in sentReminders }

        if (newEvents.isNotEmpty()) {
            newEvents.forEach { sendNotification(it) }
            // 记录已发送提醒
            saveSentReminders(sentReminders + newEvents.map { it.id })
        }

        scheduleNextReminderCheck(upcomingDayEvents, now, minutes, sentReminders + newEvents.map { it.id })
        return Result.success()
    }

    private fun scheduleNextReminderCheck(
        events: List<cn.limpu.hita.data.model.timetable.EventItem>,
        now: Long,
        reminderMinutes: Int,
        sentReminders: Set<String>
    ) {
        val nextEvent = events
            .asSequence()
            .filter { it.type == cn.limpu.hita.data.model.timetable.EventItem.TYPE.CLASS }
            .filter { it.id !in sentReminders }
            .filter { it.from.time > now }
            .minByOrNull { it.from.time }

        val delayMs = if (nextEvent == null) {
            DEFAULT_RECHECK_DELAY_MS
        } else {
            max(0L, nextEvent.from.time - reminderMinutes * 60_000L - now)
        }
        CourseReminderScheduler.scheduleNextCheck(applicationContext, delayMs)
    }

    private fun sendNotification(event: cn.limpu.hita.data.model.timetable.EventItem) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            event.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actualMinutes = ((event.from.time - System.currentTimeMillis()) / 60_000L)
            .coerceAtLeast(1L)

        val title = "即将上课"
        val content = buildString {
            append("${event.name}")
            if (!event.place.isNullOrBlank()) {
                append(" @ ${event.place}")
            }
            append(" 将在 ${actualMinutes}分钟后开始")
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_today)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        manager.notify(event.id.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "课程提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "在课程开始前提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun getSentReminders(): Set<String> {
        val prefs = applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val today = TimeTools.getDateString(Calendar.getInstance())
        val savedDate = prefs.getString(KEY_DATE, "")
        
        // 如果是新的一天，清空记录
        return if (savedDate != today) {
            emptySet()
        } else {
            prefs.getStringSet(KEY_SENT_IDS, emptySet()) ?: emptySet()
        }
    }

    private fun saveSentReminders(ids: Set<String>) {
        val prefs = applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val today = TimeTools.getDateString(Calendar.getInstance())
        // doWork() runs on a WorkManager background thread, so a blocking write is allowed here
        // and is the correct choice: `apply` may lose the "already notified" set if the process
        // dies right after posting a notification, which would re-notify the same course.
        prefs.edit()
            .putStringSet(KEY_SENT_IDS, HashSet(ids))
            .putString(KEY_DATE, today)
            .commit()
    }

    companion object {
        private const val CHANNEL_ID = "course_reminder"
        private const val SP_NAME = "course_reminder_sent"
        private const val KEY_SENT_IDS = "sent_ids"
        private const val KEY_DATE = "date"
        private const val LOOKAHEAD_WINDOW_MS = 24L * 60L * 60L * 1000L
        private const val DEFAULT_RECHECK_DELAY_MS = 6L * 60L * 60L * 1000L
    }
}
