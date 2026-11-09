package cn.limpu.hita.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 课程提醒调度器
 *
 * 单一调度源：一次性任务自调度链（每次检查后由 Worker 排下一次），不再叠加周期任务。
 * 周期任务与一次性任务使用不同唯一名，WorkManager 只对同名任务互斥，两者同时到期会各自
 * 读到同一份“已发送”集合并可能重复发通知。一次性任务没有最小间隔限制，粒度由
 * [CourseReminderWorker.scheduleNextReminderCheck] 决定，因此周期任务无用且有冲突风险。
 */
object CourseReminderScheduler {
    private const val UNIQUE_NEXT_WORK_NAME = "course_reminder_next"

    /**
     * 启动课程提醒。已存在排队/运行中的检查时保持它（KEEP），避免取消正在执行的检查。
     */
    fun schedule(context: Context) {
        ensureScheduled(context)
    }

    /**
     * 外部入口（应用启动、设置开关、页面切换）只保证链存在，不替换在途检查。
     */
    fun ensureScheduled(context: Context) {
        enqueue(context, 0L, ExistingWorkPolicy.KEEP)
    }

    /**
     * Worker 结束时为自己排下一次检查。必须用 REPLACE：同名任务正是刚刚运行结束的这一次，
     * KEEP 会因“未完成”而丢弃新请求，令自调度链断掉。
     */
    fun scheduleNextCheck(context: Context, delayMs: Long) {
        enqueue(context, delayMs, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<CourseReminderWorker>()
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

    /**
     * 取消课程提醒。
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NEXT_WORK_NAME)
    }

    /**
     * 根据开关状态自动调度或取消。
     */
    fun autoSchedule(context: Context) {
        val store = cn.limpu.hita.data.source.preference.CourseReminderStore(context.applicationContext)
        if (store.isEnabled()) {
            schedule(context)
        } else {
            cancel(context)
        }
    }
}
