package cn.limpu.hita.feature.livecourse.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.limpu.hita.feature.livecourse.guard.LiveCourseGuardService
import cn.limpu.hita.feature.livecourse.scheduler.LiveCourseScheduler
import cn.limpu.hita.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Restores and advances the lifecycle after alarms, reboot, and wall-clock changes. */
class LiveCourseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIGGER,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }
        // These system broadcasts permit foreground-service restoration. An ordinary
        // trigger may be an inexact fallback alarm, which does not grant that exemption.
        if (intent.action != ACTION_TRIGGER) {
            LiveCourseGuardService.restoreIfEnabled(context)
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Keep goAsync alive until the Room read, notification update, and next alarm
                // finish. autoSchedule launches a separate coroutine and returns too early.
                LiveCourseScheduler(context.applicationContext).reconcile()
            } catch (error: Exception) {
                LogUtils.e("Live Course alarm reconciliation failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER = "cn.limpu.hita.action.LIVE_COURSE_TRIGGER"
    }
}
