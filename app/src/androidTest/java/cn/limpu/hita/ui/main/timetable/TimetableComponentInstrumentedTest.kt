package cn.limpu.hita.ui.main.timetable

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.ShenzhenCourseMeeting
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.feature.timetableshare.protocol.*
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.eas.catalog.CoursePlanPreviewContent
import cn.limpu.hita.ui.subject.SubjectActivity
import cn.limpu.hita.ui.timetable.friend.FriendDetailState
import cn.limpu.hita.ui.timetable.friend.FriendTimetableDetailScreen
import com.limpu.component.data.DataState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Uses the real Compose tree and injected touch events, without a parallel test renderer. */
@RunWith(AndroidJUnit4::class)
class TimetableComponentInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val schedule = listOf(period(8, 0, 8, 45), period(9, 0, 9, 45), period(10, 0, 10, 45))

    @Test fun readOnlyWeekHandlesRealCardTapAndSingleSwipeThenClearsOldConflictSheet() = host { scenario ->
        val week = mutableLongStateOf(monday)
        val clicks = AtomicInteger()
        val moves = AtomicInteger()
        val first = event("first", "可读课程", 8)
        val renderedEvent = mutableStateOf(first)
        val clickedName = AtomicReference<String>()
        lateinit var view: ComposeView
        scenario.onActivity { activity ->
            view = render(activity) {
                ReadOnlyTimetableWeek(week.longValue, if (week.longValue == monday) listOf(renderedEvent.value) else emptyList(), schedule,
                    onNextWeek = { moves.incrementAndGet(); week.longValue += 7 * 86_400_000L },
                    onEventClick = { clicks.incrementAndGet(); clickedName.set(it.name) }, displayTimeZone = zone)
            }
        }
        tapText("可读课程")
        waitUntil { clicks.get() == 1 }
        scenario.onActivity { renderedEvent.value = event("first", "刷新后课程", 8) }
        tapText("刷新后课程")
        waitUntil { clicks.get() == 2 }
        assertEquals("刷新后课程", clickedName.get())
        val bounds = Rect()
        scenario.onActivity { view.getGlobalVisibleRect(bounds) }
        swipe(bounds, left = true)
        waitUntil { moves.get() == 1 }
        SystemClock.sleep(350)
        assertEquals(1, moves.get())
        assertEquals(2, clicks.get())
        assertNull(findText("刷新后课程"))

        // An actual conflict sheet must be invalidated synchronously on a week change.
        scenario.onActivity { activity ->
            week.longValue = monday
            render(activity) {
                ReadOnlyTimetableWeek(week.longValue,
                    if (week.longValue == monday) listOf(first, event("second", "冲突课程", 8)) else emptyList(),
                    schedule, displayTimeZone = zone)
            }
        }
        tapText("课程冲突")
        waitUntil { findText("冲突课程") != null }
        scenario.onActivity { week.longValue += 7 * 86_400_000L }
        waitUntil { findText("冲突课程") == null }
    }

    @Test fun friendDetailActuallyUsesSharedWeekAndOpensItsOccurrenceDetails() = host { scenario ->
        val snapshot = SharedTimetable("component-fixture", "朋友 fixture", ShareMode.FULL,
            SharedTerm("测试课表", "测试学期", monday, monday + 7 * 86_400_000L),
            listOf(SharedPeriod(480, 540)), listOf("朋友课程"),
            listOf(SharedOccurrence(monday + 8 * 3_600_000L, monday + 9 * 3_600_000L, 0, "fixture room")))
        val row = FriendTimetableEntity(snapshot.shareId, snapshot.nickname, null, snapshot.term.termName,
            snapshot.term.startMillis, snapshot.term.endMillis, "FULL", "", "", 0, 0)
        scenario.onActivity { activity -> render(activity) { FriendTimetableDetailScreen(FriendDetailState.Ready(row, snapshot)) {} } }
        tapText("朋友课程")
        waitUntil { findText("北京时间") != null }
        assertNotNull(findText("fixture room"))
        assertNotNull(findText("关闭"))
    }

    @Test fun coursePlanningPreviewActuallyUsesSharedWeekAndOpensItsEventDialog() = host { scenario ->
        scenario.onActivity { activity ->
            render(activity) {
                CoursePlanPreviewContent(
                    term = null,
                    selectedState = DataState(
                        listOf(ShenzhenCourseCatalogItem("planning", courseCode = "TEST", courseName = "规划课程",
                            meetings = listOf(ShenzhenCourseMeeting(listOf(1), 1, 1, 1, location = "planning room")),
                            source = ShenzhenCourseCatalogSource.AVAILABLE)),
                        DataState.STATE.SUCCESS
                    ),
                    startDateState = DataState(
                        Calendar.getInstance().apply { timeInMillis = monday },
                        DataState.STATE.SUCCESS
                    ),
                    scheduleState = DataState(schedule.toMutableList(), DataState.STATE.SUCCESS),
                    draft = null,
                    onBack = {},
                    onShowInTimetable = {},
                    onHideFromTimetable = {},
                    onClearDraft = {},
                    onRetryDependencies = {},
                )
            }
        }
        instrumentation.waitForIdleSync()
        tapText("规划课程")
        waitUntil { findText("地点：planning room") != null }
        assertNotNull(findText("完成"))
    }

    private fun host(block: (ActivityScenario<SubjectActivity>) -> Unit) {
        val db = AppDatabase.getDatabase(app)
        val table = Timetable().apply { name = "Timetable component host fixture" }
        // Real subject identity for the Hilt host; blank resource query avoids live HTTP.
        val subject = TermSubject().apply { timetableId = table.id; name = ""; code = null }
        try {
            db.timetableDao().saveTimetableSync(table)
            db.subjectDao().saveSubjectSync(subject)
            ActivityScenario.launch<SubjectActivity>(Intent(app, SubjectActivity::class.java).putExtra("subjectId", subject.id)).use(block)
        } finally {
            db.subjectDao().deleteSubjectsInIdsSync(listOf(subject.id))
            db.timetableDao().deleteTimetablesInIdsSync(listOf(table.id))
        }
    }

    private fun render(activity: SubjectActivity, content: @Composable () -> Unit): ComposeView = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        activity.setContentView(this)
        setContent { HitaComposeTheme { content() } }
    }

    private fun event(id: String, name: String, hour: Int) = EventItem().apply {
        this.id = id; this.name = name; subjectId = id
        from = Timestamp(monday + hour * 3_600_000L); to = Timestamp(from.time + 45 * 60_000)
    }

    private fun period(a: Int, b: Int, c: Int, d: Int) = TimePeriodInDay(TimeInDay(a, b), TimeInDay(c, d))

    private fun findText(text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString()?.contains(text) == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun tapText(text: String) {
        waitUntil { findText(text) != null }
        val bounds = Rect().also { requireNotNull(findText(text)).getBoundsInScreen(it) }
        val now = SystemClock.uptimeMillis()
        touch(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
        touch(now, now + 30, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun swipe(bounds: Rect, left: Boolean) {
        val from = bounds.left + bounds.width() * if (left) .85f else .15f
        val to = bounds.left + bounds.width() * if (left) .15f else .85f
        val y = bounds.top + bounds.height() * .45f
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, from, y)
        for (step in 1..12) {
            SystemClock.sleep(16)
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, from + (to - from) * step / 12, y)
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, to, y)
    }

    private fun touch(down: Long, now: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, now, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Expected real timetable component state was not rendered")
    }
}
