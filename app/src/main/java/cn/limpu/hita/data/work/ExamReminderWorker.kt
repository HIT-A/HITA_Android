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
import cn.limpu.hita.utils.TimeTools
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * 考试提醒 Worker。
 *
 * 与 iOS `fixedExamReminderDates` 对齐：考前 24h / 12h / 1h / 30m 四个固定档位，
 * 不跟随课程的「课前几分钟」设置，也不提供独立开关——总开关复用课程提醒的
 * `CourseReminderStore.isEnabled()`，这是原来就有的语义。
 *
 * 已错过的档位不补发：一次性自调度链可能在睡眠或断网后恢复，
 * 补发「考试还有 24 小时」只会误导。
 */
class ExamReminderWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val store = CourseReminderStore(applicationContext)
        if (!store.isEnabled()) return Result.success()

        val app = applicationContext as? Application ?: return Result.success()
        val now = System.currentTimeMillis()
        val scopeIds = DisplayScopeResolver(app).currentSync()?.timetableIds.orEmpty()
        val exams = runBlocking {
            TimetableRepository(app).getUpcomingEventsSync(now, now + LOOKAHEAD_WINDOW_MS, scopeIds)
        }.filter { it.type == EventItem.TYPE.EXAM }
            .map { it.toReminderEvent() }

        val sent = getSentReminders().toMutableSet()
        val due = ExamReminderSchedule.dueNow(exams, now, EXAM_LEADS, sent)
        due.forEach { reminder ->
            val exam = exams.first { it.id == reminder.eventId }
            sendNotification(exam)
            sent += reminder.key
        }
        if (due.isNotEmpty()) saveSentReminders(sent)

        val next = ExamReminderSchedule.nextDue(exams, now, EXAM_LEADS, sent)
        ExamReminderScheduler.scheduleNextCheck(
            applicationContext,
            next?.delayMs(now) ?: DEFAULT_RECHECK_DELAY_MS,
        )
        return Result.success()
    }

    private fun sendNotification(exam: ReminderEvent) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        // 不直接引用 MainActivity：data 层依赖 ui 层会被 scripts/check_repository.py 拦下。
        // 用包的启动意图拿同一个入口，行为等价且不引入跨层依赖。
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            exam.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildString {
            append(exam.name)
            if (exam.place.isNotBlank()) append(" @ ${exam.place}")
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_today)
            .setContentTitle("考试提醒")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        manager.notify(exam.id.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "考试提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "在考试开始前提醒"
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
        // 考试没有节次，显式传 null：ReminderEvent 的 section 参数没有默认值。
        section = null,
        name = name,
        place = place.orEmpty(),
    )

    companion object {
        private const val CHANNEL_ID = "exam_reminder"
        private const val SP_NAME = "exam_reminder_sent"
        private const val KEY_SENT_IDS = "sent_ids"
        private const val KEY_DATE = "date"

        /** 与 iOS `fixedExamReminderDates` 一致：24h / 12h / 1h / 30m。 */
        private val EXAM_LEADS = listOf(24 * 60, 12 * 60, 60, 30)

        private const val LOOKAHEAD_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
        private const val DEFAULT_RECHECK_DELAY_MS = 6L * 60L * 60L * 1000L
    }
}
