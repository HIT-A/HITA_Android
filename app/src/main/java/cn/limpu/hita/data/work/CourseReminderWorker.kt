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
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.source.preference.CourseReminderStore
import cn.limpu.hita.ui.main.MainActivity
import cn.limpu.hita.utils.TimeTools
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * 课程提醒 Worker。
 *
 * 一次运行只处理当前已经到期的（事件, 档位），然后把下一次触发点排进同一条一次性链。
 */
class CourseReminderWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val store = CourseReminderStore(applicationContext)
        if (!store.isEnabled()) return Result.success()

        val app = applicationContext as? Application ?: return Result.success()
        val rules = store.rules()
        val now = System.currentTimeMillis()
        val scopeIds = DisplayScopeResolver(app).currentSync()?.timetableIds.orEmpty()
        val events = runBlocking {
            TimetableRepository(app).getUpcomingEventsSync(now, now + LOOKAHEAD_WINDOW_MS, scopeIds)
        }.filter { it.type == EventItem.TYPE.CLASS }
            .map { it.toReminderEvent() }

        val sent = getSentReminders().toMutableSet()
        val due = CourseReminderSchedule.dueNow(
            candidates = events.flatMap { event -> CourseReminderSchedule.candidatesFor(event, rules) },
            now = now,
            sentKeys = sent,
        )
        due.forEach { reminder ->
            val event = events.first { it.id == reminder.eventId }
            sendNotification(event, reminder)
            sent += reminder.key
        }
        if (due.isNotEmpty()) saveSentReminders(sent)

        val next = CourseReminderSchedule.nextDue(events, now, rules, sent)
        CourseReminderScheduler.scheduleNextCheck(
            applicationContext,
            next?.delayMs(now) ?: DEFAULT_RECHECK_DELAY_MS,
        )
        return Result.success()
    }

    private fun sendNotification(event: ReminderEvent, reminder: ReminderDue) {
        val lead = reminder.lead
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            event.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 课前按下课剩余时长描述，下课前同样的文案会说不通，因此按 phase 分开算。
        val isEnd = reminder.phase == ReminderPhase.END
        val anchorMillis = if (isEnd) (event.endMillis ?: event.startMillis) else event.startMillis
        val remaining = ((anchorMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
        val whenText = when {
            isEnd && remaining == 0L -> "即将下课"
            isEnd -> "将在 ${remaining}分钟后下课"
            remaining == 0L -> "即将开始"
            else -> "将在 ${remaining}分钟后开始"
        }
        val content = buildString {
            append(event.name)
            if (event.place.isNotBlank()) append(" @ ${event.place}")
            append(" ")
            append(whenText)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_today)
            .setContentTitle(
                when {
                    isEnd && lead == 0 -> "课程结束"
                    isEnd -> "即将下课"
                    lead == 0 -> "课程开始"
                    else -> "即将上课"
                }
            )
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        manager.notify(reminder.key.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "课程提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "在课程开始前提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
        )
    }

    private fun getSentReminders(): Set<String> {
        val prefs = applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val today = TimeTools.getDateString(Calendar.getInstance())
        if (prefs.getString(KEY_DATE, "") != today) return emptySet()
        return prefs.getStringSet(KEY_SENT_IDS, emptySet()).orEmpty()
    }

    private fun saveSentReminders(ids: Set<String>) {
        val prefs = applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_SENT_IDS, HashSet(ids))
            .putString(KEY_DATE, TimeTools.getDateString(Calendar.getInstance()))
            .commit()
    }

    private fun EventItem.toReminderEvent() = ReminderEvent(
        id = id,
        startMillis = from.time,
        section = fromNumber.takeIf { it > 0 },
        name = name,
        place = place.orEmpty(),
        endMillis = to.time,
    )

    companion object {
        private const val CHANNEL_ID = "course_reminder"
        private const val SP_NAME = "course_reminder_sent"
        private const val KEY_SENT_IDS = "sent_ids"
        private const val KEY_DATE = "date"
        private const val LOOKAHEAD_WINDOW_MS = 24L * 60L * 60L * 1000L
        private const val DEFAULT_RECHECK_DELAY_MS = 6L * 60L * 60L * 1000L
    }
}
