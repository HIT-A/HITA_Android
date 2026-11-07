package cn.limpu.hita.feature.livecourse.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.limpu.hita.R
import cn.limpu.hita.feature.livecourse.domain.LiveCourse
import cn.limpu.hita.feature.livecourse.domain.LiveCourseState
import cn.limpu.hita.ui.main.MainActivity
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Renders one ongoing notification; Android 16+ may promote it to a Live Update. */
class LiveCourseNotificationManager(context: Context) {
    private val appContext = context.applicationContext
    private val platformManager = appContext.getSystemService(NotificationManager::class.java)

    fun canPostNotifications(): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted &&
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    fun show(state: LiveCourseState) {
        if (state is LiveCourseState.Inactive || state is LiveCourseState.Ended || !canPostNotifications()) {
            cancel()
            return
        }
        ensureChannel()
        val course = when (state) {
            is LiveCourseState.PreClass -> state.course
            is LiveCourseState.InClass -> state.course
            else -> return
        }
        val status = appContext.getString(
            if (state is LiveCourseState.PreClass) R.string.live_course_pre_class_imminent
            else R.string.live_course_in_class,
        )
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
        val timeRange = appContext.getString(
            R.string.live_course_time_range,
            timeFormatter.format(course.startTime),
            timeFormatter.format(course.endTime),
        )
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live_course_hourglass)
            .setContentTitle(course.courseName)
            .setContentText(status)
            .setSubText(course.classroom?.takeIf(String::isNotBlank)?.let {
                appContext.getString(R.string.live_course_room_and_time, it, timeRange)
            } ?: timeRange)
            .setColor(ContextCompat.getColor(appContext, R.color.primary))
            .setContentIntent(courseIntent(course))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setRequestPromotedOngoing(true)

        when (state) {
            is LiveCourseState.PreClass -> {
                builder.setWhen(course.startTime.toEpochMilli())
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    // Remove the pre-class cloud at the class boundary even when the OEM
                    // defers the alarm that would normally switch it to InClass.
                    .setTimeoutAfter(state.remaining.toMillis().coerceAtLeast(1L))
            }
            is LiveCourseState.InClass -> {
                val max = 1_000
                val progress = ((state.progress * max).toInt()).coerceIn(0, max)
                // Standard progress remains meaningful on all supported devices. Android 16
                // System UI can render this promoted ongoing notification as a Live Update.
                // The countdown is owned by System UI, so the Dynamic Island keeps its
                // remaining-time display current even when the app is not brought forward.
                builder.setProgress(max, progress, false)
                    .setWhen(course.endTime.toEpochMilli())
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setTimeoutAfter(state.remaining.toMillis().coerceAtLeast(1L))
            }
            else -> Unit
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        platformManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.live_course_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.live_course_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun courseIntent(course: LiveCourse): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_COURSE_ID, course.courseId)
        }
        return PendingIntent.getActivity(
            appContext,
            course.courseId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "hita_live_course"
        const val EXTRA_COURSE_ID = "live_course_id"
        private const val NOTIFICATION_ID = 20_260_921
    }
}
