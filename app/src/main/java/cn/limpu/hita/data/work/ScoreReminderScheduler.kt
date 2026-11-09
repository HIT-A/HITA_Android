package cn.limpu.hita.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ScoreReminderScheduler {
    private const val UNIQUE_PERIODIC_NAME = "score_reminder_periodic"
    private const val UNIQUE_IMMEDIATE_NAME = "score_reminder_once"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodicRequest = PeriodicWorkRequestBuilder<ScoreReminderWorker>(
            30, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )

        val immediateRequest = OneTimeWorkRequestBuilder<ScoreReminderWorker>()
            .setConstraints(constraints)
            .build()
        // 每次进入成绩页都会调用 schedule()；KEEP 避免取消正在执行的查询并重复请求 EAS。
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_NAME,
            ExistingWorkPolicy.KEEP,
            immediateRequest
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_IMMEDIATE_NAME)
    }
}
