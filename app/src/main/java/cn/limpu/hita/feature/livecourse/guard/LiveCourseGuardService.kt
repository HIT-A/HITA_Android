package cn.limpu.hita.feature.livecourse.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.limpu.hita.R
import cn.limpu.hita.feature.livecourse.scheduler.LiveCourseScheduler
import cn.limpu.hita.feature.livecourse.settings.LiveCourseSettings
import cn.limpu.hita.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * User-enabled foreground guard for OEMs that defer exact alarms.  The service has its own
 * low-priority ongoing notification; the promoted Live Course notification remains separate.
 */
class LiveCourseGuardService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private var guardJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, guardNotification())
        if (!isGuardEnabled()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (guardJob?.isActive != true) {
            guardJob = serviceScope.launch {
                while (isActive && isGuardEnabled()) {
                    try {
                        LiveCourseScheduler(applicationContext).reconcile()
                    } catch (error: Exception) {
                        // A transient Room/database error must not silently end the guard.
                        // The next minute retries the timetable read.
                        LogUtils.e("Live Course strong reminder reconcile failed", error)
                    }
                    delay(CHECK_INTERVAL_MILLIS)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isGuardEnabled(): Boolean {
        val settings = LiveCourseSettings(this)
        return settings.isEnabled() && settings.isStrongReminderEnabled()
    }

    private fun guardNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_access_time_24)
            .setContentTitle(getString(R.string.live_course_guard_notification_title))
            .setContentText(getString(R.string.live_course_guard_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.live_course_guard_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.live_course_guard_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hita_live_course_guard"
        private const val NOTIFICATION_ID = 20_260_922
        private const val CHECK_INTERVAL_MILLIS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, LiveCourseGuardService::class.java),
            )
        }

        /** Restore only an explicit opt-in; rejected background starts keep alarm/worker fallback. */
        fun restoreIfEnabled(context: Context) {
            val settings = LiveCourseSettings(context)
            if (!settings.isEnabled() || !settings.isStrongReminderEnabled()) return
            try {
                start(context)
            } catch (error: IllegalStateException) {
                LogUtils.e("Live Course guard restoration deferred by system", error)
            } catch (error: SecurityException) {
                LogUtils.e("Live Course guard restoration denied by system", error)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, LiveCourseGuardService::class.java),
            )
        }
    }
}
