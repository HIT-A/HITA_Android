package cn.limpu.hita.ui.main.timetable

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import android.widget.ListView
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.R
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.source.dao.EventItemDao
import cn.limpu.hita.ui.subject.SubjectActivity
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The same assertions run against the frozen original Fragment and the replacement.
 * Reflection only adapts private entry/observable names and supplies IO gates; no legacy logic is copied.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class TimetableLegacyBoundaryInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun delayedBatchReadCannotReopenEditorAfterActualDestroyView() = fixture { scenario, fragment, model, event ->
        val repository = repository(model)
        val daoField = TimetableRepository::class.java.getDeclaredField("eventItemDao").apply { isAccessible = true }
        val dao = daoField.get(repository) as EventItemDao
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val proxy = Proxy.newProxyInstance(EventItemDao::class.java.classLoader, arrayOf(EventItemDao::class.java)) { _, method, args ->
            if (method.name == "getClassesOfSubjectSync" && args?.firstOrNull() == event.subjectId) {
                worker.set(Thread.currentThread())
                entered.countDown()
                assertTrue("Read gate timed out", release.await(10, TimeUnit.SECONDS))
                try { method.invoke(dao, *(args ?: emptyArray())) } finally { exited.countDown() }
            } else method.invoke(dao, *(args ?: emptyArray()))
        }
        daoField.set(repository, proxy)
        try {
            showScope(scenario, fragment, event)
            assertTrue("Real DAO read did not start", entered.await(10, TimeUnit.SECONDS))
            scenario.onActivity { it.supportFragmentManager.beginTransaction().detach(fragment).commitNow() }
            release.countDown()
            assertTrue(exited.await(10, TimeUnit.SECONDS))
            if (legacy(model)) {
                worker.get().join(10_000)
                assertFalse("Legacy read thread did not finish", worker.get().isAlive)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNull("Destroyed view must not receive the late batch", batch(model)) }
        } finally {
            release.countDown()
            daoField.set(repository, dao)
        }
    }

    private fun showScope(scenario: ActivityScenario<SubjectActivity>, fragment: TimetableFragment, event: EventItem) {
        scenario.onActivity {
            val method = TimetableFragment::class.java.declaredMethods.single { it.name == "showBatchScopePicker" }.apply { isAccessible = true }
            if (method.parameterTypes.size == 2) method.invoke(fragment, event, true)
            else {
                val owner = TimetableFragment::class.java.getDeclaredField("viewOwner").apply { isAccessible = true }.get(fragment)
                method.invoke(fragment, owner, event, true)
            }
        }
        // Invoke the actual AlertDialog ListView action. Window focus is unrelated to the
        // DAO/callback ownership under test and may lag behind ActivityScenario startup.
        val text = app.getString(R.string.subject_batch_scope_this)
        var selected = false
        waitUntil {
            scenario.onActivity {
                val list = windowViews().filterIsInstance<ListView>().firstOrNull { candidate ->
                    candidate.isShown && (0 until candidate.adapter.count).any { candidate.adapter.getItem(it)?.toString() == text }
                }
                if (list != null) {
                    val position = (0 until list.adapter.count).first { list.adapter.getItem(it)?.toString() == text }
                    val row = list.getChildAt(position - list.firstVisiblePosition)
                    if (row != null) selected = list.performItemClick(row, position, list.adapter.getItemId(position))
                }
            }
            selected
        }
    }

    private fun repository(model: TimetableViewModel) = TimetableViewModel::class.java.getDeclaredField("timetableRepository")
        .apply { isAccessible = true }.get(model) as TimetableRepository

    private fun legacy(model: TimetableViewModel) = model.javaClass.declaredFields.any { it.name == "batchSessionLiveData" }

    private fun batch(model: TimetableViewModel): Any? {
        val old = model.javaClass.declaredFields.firstOrNull { it.name == "batchSessionLiveData" }
        if (old != null) return (old.apply { isAccessible = true }.get(model) as LiveData<*>).value
        val coordinator = model.javaClass.getDeclaredField("batches").apply { isAccessible = true }.get(model)
        val state = coordinator.javaClass.getMethod("getState").invoke(coordinator) as StateFlow<*>
        return state.value!!.javaClass.getMethod("getSession").invoke(state.value)
    }

    private fun fixture(block: (ActivityScenario<SubjectActivity>, TimetableFragment, TimetableViewModel, EventItem) -> Unit) {
        val db = AppDatabase.getDatabase(app)
        val table = Timetable().apply { name = "Timetable boundary fixture" }
        // Real subject identity for the Hilt host; blank resource query avoids live HTTP.
        val subject = TermSubject().apply { timetableId = table.id; name = ""; code = null }
        val event = EventItem().apply { timetableId = table.id; subjectId = subject.id; name = "Boundary fixture"; place = "original" }
        try {
            db.timetableDao().saveTimetableSync(table)
            db.subjectDao().saveSubjectSync(subject)
            db.eventItemDao().insertEventSync(event)
            ActivityScenario.launch<SubjectActivity>(Intent(app, SubjectActivity::class.java).putExtra("subjectId", subject.id)).use { scenario ->
                lateinit var fragment: TimetableFragment
                lateinit var model: TimetableViewModel
                scenario.onActivity { activity ->
                    val container = FrameLayout(activity).apply { id = View.generateViewId() }
                    activity.setContentView(container)
                    fragment = TimetableFragment()
                    activity.supportFragmentManager.beginTransaction().add(container.id, fragment, "boundary-fixture").commitNow()
                    model = ViewModelProvider(fragment)[TimetableViewModel::class.java]
                }
                instrumentation.waitForIdleSync()
                block(scenario, fragment, model, event)
            }
        } finally {
            db.eventItemDao().deleteEventsInIdsSync(listOf(event.id))
            db.subjectDao().deleteSubjectsInIdsSync(listOf(subject.id))
            db.timetableDao().deleteTimetablesInIdsSync(listOf(table.id))
        }
    }

    private fun windowViews(): Sequence<View> {
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        return WindowInspector.getGlobalWindowViews().asReversed().asSequence().flatMap(::descendants)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Real batch UI did not reach the expected boundary")
    }
}
