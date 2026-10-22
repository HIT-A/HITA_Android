package cn.limpu.hita.feature.livecourse.scheduler

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.livecourse.domain.LiveCourse
import cn.limpu.hita.feature.livecourse.domain.LiveCourseState
import cn.limpu.hita.feature.livecourse.domain.LiveCourseStateResolver
import cn.limpu.hita.feature.livecourse.notification.LiveCourseNotificationManager
import cn.limpu.hita.feature.livecourse.receiver.LiveCourseReceiver
import cn.limpu.hita.feature.livecourse.settings.LiveCourseSettings
import cn.limpu.hita.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps at most one course lifecycle active. It reads the current timetable every time it
 * reconciles, so edited, deleted, or re-imported courses never rely on a stale copied schedule.
 */
class LiveCourseScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val settings = LiveCourseSettings(appContext)
    private val notificationManager = LiveCourseNotificationManager(appContext)

    fun reconcile(
        nowMillis: Long = System.currentTimeMillis(),
        generation: Int = reconcileGeneration.get(),
    ) {
        if (generation != reconcileGeneration.get()) return
        if (!settings.isEnabled() || !notificationManager.canPostNotifications()) {
            cancel()
            return
        }
        LiveCourseWork.ensurePeriodic(appContext)

        val application = appContext as? Application ?: return
        val repository = TimetableRepository(application)
        val timetableIds = DisplayScopeResolver(application).currentSync()?.timetableIds.orEmpty()
        val now = Instant.ofEpochMilli(nowMillis)
        val candidate = repository.getUpcomingEventsSync(
            nowMillis - LiveCourseStateResolver.DEFAULT_PRE_CLASS_WINDOW.toMillis(),
            nowMillis + LOOK_AHEAD_MILLIS,
            timetableIds,
        )
            .asSequence()
            .filter { it.type == EventItem.TYPE.CLASS }
            .filter { it.to.time > it.from.time }
            .filter { it.to.time > nowMillis }
            .map(::toLiveCourse)
            .sortedWith(compareBy<LiveCourse> { it.startTime }.thenBy { it.courseId })
            .firstOrNull()

        if (!canPublish(generation)) return
        if (candidate == null) {
            cancelTransition()
            notificationManager.cancel()
            return
        }

        when (val state = LiveCourseStateResolver.resolve(candidate, now)) {
            is LiveCourseState.Inactive -> {
                if (!canPublish(generation)) return
                notificationManager.cancel()
                scheduleAt(checkNotNull(nextLiveCourseTransition(state)))
            }
            is LiveCourseState.PreClass -> {
                if (!canPublish(generation)) return
                notificationManager.show(state)
                if (!canPublish(generation)) return
                scheduleAt(checkNotNull(nextLiveCourseTransition(state)))
            }
            is LiveCourseState.InClass -> {
                if (!canPublish(generation)) return
                notificationManager.show(state)
                if (!canPublish(generation)) return
                scheduleAt(checkNotNull(nextLiveCourseTransition(state)))
            }
            is LiveCourseState.Ended -> reconcile(nowMillis + 1L, generation)
        }
    }

    fun cancel() {
        alarmPendingIntent(PendingIntent.FLAG_NO_CREATE)?.let { pendingIntent ->
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        LiveCourseWork.cancel(appContext)
        notificationManager.cancel()
    }

    private fun canPublish(generation: Int): Boolean {
        if (generation != reconcileGeneration.get()) return false
        if (!settings.isEnabled() || !notificationManager.canPostNotifications()) {
            cancel()
            return false
        }
        return true
    }

    private fun cancelTransition() {
        alarmPendingIntent(PendingIntent.FLAG_NO_CREATE)?.let { pendingIntent ->
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        LiveCourseWork.cancelTransition(appContext)
    }


    private fun scheduleAt(triggerAtMillis: Long) {
        LiveCourseWork.scheduleTransition(appContext, triggerAtMillis)
        val alarm = alarmManager ?: return
        val now = System.currentTimeMillis()
        val triggerAt = triggerAtMillis.coerceAtLeast(now + MIN_ALARM_DELAY_MILLIS)
        val pendingIntent = checkNotNull(alarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT))
        try {
            val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarm.canScheduleExactAlarms()
            if (canScheduleExact) {
                if (triggerAt - now > ALARM_CLOCK_PREPARE_WINDOW_MILLIS) {
                    // OnePlus gives ordinary exact alarms a one-hour window. Wake early,
                    // then arm the precise visible alarm shortly before the course.
                    alarm.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt - ALARM_CLOCK_PREPARE_WINDOW_MILLIS,
                        pendingIntent,
                    )
                } else {
                    alarm.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAt, openAppPendingIntent()),
                        pendingIntent,
                    )
                }
            } else {
                // Permission-denied fallback remains reliable enough to re-evaluate the course
                // and never starts a foreground loop.
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        ALARM_REQUEST_CODE,
        Intent(appContext, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(appContext, LiveCourseReceiver::class.java).apply {
            action = LiveCourseReceiver.ACTION_TRIGGER
            data = Uri.parse("hita://live-course/next-transition")
        }
        return PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun toLiveCourse(event: EventItem) = LiveCourse(
        courseId = event.id,
        courseName = event.name,
        classroom = event.place?.takeIf(String::isNotBlank),
        startTime = event.from.toInstant(),
        endTime = event.to.toInstant(),
    )

    companion object {
        // Keep one future lifecycle scheduled across holidays without requiring the app to open.
        private const val LOOK_AHEAD_MILLIS = 180L * 24 * 60 * 60 * 1000
        private const val MIN_ALARM_DELAY_MILLIS = 1_000L
        private const val ALARM_CLOCK_PREPARE_WINDOW_MILLIS = 2L * 60 * 60_000L
        private const val ALARM_REQUEST_CODE = 20_260_921

        fun autoSchedule(context: Context) {
            val scheduler = LiveCourseScheduler(context)
            if (!LiveCourseSettings(context).isEnabled()) {
                reconcileGeneration.incrementAndGet()
                scheduler.cancel()
                return
            }
            val generation = reconcileGeneration.incrementAndGet()
            // This entry point is also called directly by the Compose settings switch. The
            // timetable repository performs synchronous Room reads, so it must never run on UI.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    scheduler.reconcile(generation = generation)
                } catch (error: Exception) {
                    cn.limpu.hita.utils.LogUtils.e("Live course reconcile failed", error)
                }
            }
        }

        private val reconcileGeneration = AtomicInteger(0)
    }
}
