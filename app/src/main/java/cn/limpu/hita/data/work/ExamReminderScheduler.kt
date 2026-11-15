package cn.limpu.hita.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 考试提醒调度器。
 *
 * 与 [CourseReminderScheduler] 同构：一次性任务自调度链，每次检查后排下一次。
 * 使用独立的唯一名，避免和课程提醒互相替换掉对方的在途检查。
 */
object ExamReminderScheduler {
    private const val UNIQUE_NEXT_WORK_NAME = "exam_reminder_next"

    fun ensureScheduled(context: Context) {
        enqueue(context, 0L, ExistingWorkPolicy.KEEP)
    }

    fun scheduleNextCheck(context: Context, delayMs: Long) {
        enqueue(context, delayMs, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ExamReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NEXT_WORK_NAME,
            policy,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NEXT_WORK_NAME)
    }

    /** 跟随课程提醒总开关：关掉课程提醒等于关掉所有课表相关通知。 */
    fun autoSchedule(context: Context) {
        val store = cn.limpu.hita.data.source.preference.CourseReminderStore(context.applicationContext)
        if (store.isEnabled()) {
            ensureScheduled(context)
        } else {
            cancel(context)
        }
    }
}
