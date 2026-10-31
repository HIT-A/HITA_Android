package cn.limpu.hita.ui.main.timetable

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.os.Build
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.R
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.EasSettingsRepository
import cn.limpu.hita.ui.main.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp

/** Actual Main tab navigation mounts the production Fragment and its Hilt ViewModel. */
@RunWith(AndroidJUnit4::class)
class TimetableHostInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun mainTabDestroyViewClearsBatchAndOldCompositionBeforeViewRecreation() {
        val db = AppDatabase.getDatabase(app)
        val table = Timetable().apply {
            name = "Timetable lifecycle fixture"
            startTime = Timestamp(mondayOf(System.currentTimeMillis()))
            endTime = Timestamp(startTime.time + 7 * 86_400_000L)
        }
        val subject = TermSubject().apply { timetableId = table.id; name = "Lifecycle fixture" }
        val event = EventItem().apply {
            timetableId = table.id; subjectId = subject.id; name = subject.name
            from = Timestamp(table.startTime.time + 8 * 3_600_000L); to = Timestamp(from.time + 3_600_000)
        }
        val settings = EasSettingsRepository(app)
        val automaticImport = settings.isAutoReimportEnabled()
        settings.setAutoReimport(false)
        try {
            db.timetableDao().saveTimetableSync(table)
            db.subjectDao().saveSubjectSync(subject)
            db.eventItemDao().insertEventSync(event)
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                isolateStartupPopups(scenario)
                tapTab(app.getString(R.string.title_timetable))
                lateinit var fragment: TimetableFragment
                lateinit var model: TimetableViewModel
                lateinit var oldView: ComposeView
                var owner = 0L
                waitUntil {
                    var mounted = false
                    scenario.onActivity { activity ->
                        val current = activity.supportFragmentManager.findFragmentByTag("main_tab_TIMETABLE") as? TimetableFragment
                        mounted = current?.view?.isAttachedToWindow == true
                    }
                    mounted
                }
                scenario.onActivity { activity ->
                    fragment = activity.supportFragmentManager.findFragmentByTag("main_tab_TIMETABLE") as TimetableFragment
                    model = ViewModelProvider(fragment)[TimetableViewModel::class.java]
                    oldView = fragment.requireView() as ComposeView
                    owner = TimetableFragment::class.java.getDeclaredField("viewOwner").apply { isAccessible = true }.get(fragment) as Long
                    assertTrue(oldView.hasComposition)
                    model.batches.request(owner, event, true, TimetableBatchScope.THIS)
                }
                runBlocking { withTimeout(10_000) { model.batches.state.first { it.session != null } } }
                scenario.onActivity { activity ->
                    val oldLifecycle = fragment.viewLifecycleOwner.lifecycle
                    activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
                    assertEquals(Lifecycle.State.DESTROYED, oldLifecycle.currentState)
                    assertFalse(oldView.hasComposition)
                    assertFalse(model.batches.owns(owner))
                    assertNull(model.batches.state.value.session)
                    activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
                    assertSame(model, ViewModelProvider(fragment)[TimetableViewModel::class.java])
                    assertNotSame(oldView, fragment.requireView())
                    model.batches.request(owner, event, true, TimetableBatchScope.THIS)
                    assertNull(model.batches.state.value.session)
                }
                // Full Activity reconstruction still resolves the external Main -> timetable entry point.
                scenario.recreate()
                isolateStartupPopups(scenario)
                waitUntil {
                    var mounted = false
                    scenario.onActivity { activity ->
                        mounted = activity.supportFragmentManager.fragments.count { it.tag == "main_tab_TIMETABLE" && it.view?.isAttachedToWindow == true } == 1
                    }
                    mounted
                }
            }
        } finally {
            settings.setAutoReimport(automaticImport)
            db.eventItemDao().deleteEventsInIdsSync(listOf(event.id))
            db.subjectDao().deleteSubjectsInIdsSync(listOf(subject.id))
            db.timetableDao().deleteTimetablesInIdsSync(listOf(table.id))
        }
    }

    private fun isolateStartupPopups(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            // This test checks real tab navigation and Fragment lifecycle. Live notices
            // and update responses must not replace its active accessibility window.
            listOf("noticePresenter", "updatePresenter").forEach { name ->
                val field = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(activity) as AutoCloseable).close()
            }
            // close() prevents late responses; also dismiss an already-restored update.
            (activity.supportFragmentManager.findFragmentByTag("update") as? androidx.fragment.app.DialogFragment)
                ?.dismissNow()
        }
    }

    private fun tapTab(text: String) {
        var bounds: Rect? = null
        waitUntil {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                val nodeBounds = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && node.text?.toString() == text && nodeBounds.width() > 0) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null) bounds = Rect().also(node::getBoundsInScreen)
            node != null
        }
        val target = requireNotNull(bounds)
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 30, action, target.exactCenterX(), target.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Main did not render its timetable tab")
    }
}
