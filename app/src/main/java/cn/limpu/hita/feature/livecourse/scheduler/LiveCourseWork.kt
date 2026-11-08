package cn.limpu.hita.feature.livecourse.scheduler

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import cn.limpu.hita.utils.LogUtils
import java.util.concurrent.TimeUnit

/** Persistent, inexact recovery path when an OEM delays alarms or kills the guard process. */
internal object LiveCourseWork {
    private const val PERIODIC_NAME = "live_course_reconcile_periodic"
    private const val TRANSITION_NAME = "live_course_next_transition"

    // The guard service reconciles every 60 s. `enqueueUniquePeriodicWork` is two IPC round trips
    // to WorkManager even when the stored request is kept, so remember what this process already
    // enqueued. Cancellation clears the marker, which is the only path that removes the request.
    @Volatile private var periodicEnqueued = false

    @Synchronized
    fun ensurePeriodic(context: Context) {
        if (periodicEnqueued) return
        val request = PeriodicWorkRequestBuilder<LiveCourseWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        periodicEnqueued = true
    }

    fun scheduleTransition(context: Context, triggerAtMillis: Long) {
        val delay = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<LiveCourseWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TRANSITION_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelTransition(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TRANSITION_NAME)
    }

    @Synchronized
    fun cancel(context: Context) {
        cancelTransition(context)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        periodicEnqueued = false
    }
}

class LiveCourseWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        LiveCourseScheduler(applicationContext).reconcile()
        Result.success()
    } catch (error: IllegalArgumentException) {
        LogUtils.e("Live Course work rejected a permanent timetable error", error)
        Result.failure()
    } catch (error: Exception) {
        LogUtils.e("Live Course work reconciliation failed", error)
        Result.retry()
    }
}
