package cn.limpu.hita.perf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.limpu.hita.data.source.preference.CourseReminderStore
import cn.limpu.hita.data.work.CourseReminderScheduler
import cn.limpu.hita.data.work.ScoreReminderScheduler
import cn.limpu.hita.data.work.WidgetRefreshScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * PERF-03 regression: repeated scheduling must not multiply background work.
 *
 * Before the fix the course reminder also owned a 15-minute periodic request while a separate
 * one-time chain ran the same checks. The two use different unique names, so WorkManager never
 * serialized them and one due course could be notified twice. The widget provider additionally
 * refreshed every 30 minutes (`android:updatePeriodMillis`) on top of the 15-minute worker.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundScheduleUniquenessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager = WorkManager.getInstance(context)

    @Before fun setUp() {
        cancelAll()
        CourseReminderStore(context).setEnabled(true)
    }

    @After fun tearDown() {
        cancelAll()
        CourseReminderStore(context).setEnabled(false)
    }

    private fun cancelAll() {
        CourseReminderScheduler.cancel(context)
        ScoreReminderScheduler.cancel(context)
        WidgetRefreshScheduler.cancel(context)
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        workManager.pruneWork().result.get(10, TimeUnit.SECONDS)
    }

    private fun liveInfos(uniqueName: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }

    /** The course reminder must expose exactly one live request no matter how often it is asked. */
    @Test fun repeatedCourseReminderSchedulingKeepsASingleChain() {
        repeat(3) { CourseReminderScheduler.autoSchedule(context) }
        val live = liveInfos(COURSE_REMINDER_WORK)
        assertEquals("duplicate course reminder work was enqueued: $live", 1, live.size)
    }

    /** No periodic course reminder may exist: it would race the one-time chain under another name. */
    @Test fun courseReminderHasNoPeriodicRequest() {
        repeat(2) { CourseReminderScheduler.autoSchedule(context) }
        val periodic = workManager.getWorkInfosForUniqueWork("course_reminder_periodic")
            .get(10, TimeUnit.SECONDS)
        assertTrue("a periodic course reminder still exists: $periodic", periodic.isEmpty())
    }

    /** Repeated score-page visits must not replace an in-flight immediate query. */
    @Test fun repeatedScoreReminderSchedulingKeepsOneImmediateRequest() {
        repeat(3) { ScoreReminderScheduler.schedule(context) }
        val live = liveInfos("score_reminder_once")
        assertEquals("duplicate score reminder work was enqueued: $live", 1, live.size)
    }

    @Test fun repeatedWidgetSchedulingKeepsOnePeriodicRequest() {
        repeat(3) { WidgetRefreshScheduler.schedule(context) }
        val live = liveInfos("today_widget_refresh")
        assertEquals("duplicate widget refresh work was enqueued: $live", 1, live.size)
    }

    private companion object {
        const val COURSE_REMINDER_WORK = "course_reminder_next"
    }
}
