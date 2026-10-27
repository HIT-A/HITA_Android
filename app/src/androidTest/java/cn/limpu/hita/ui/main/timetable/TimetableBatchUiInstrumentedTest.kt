package cn.limpu.hita.ui.main.timetable

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Observer
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Fragment -> Route -> dialog semantics -> coordinator -> queued use case -> Room. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class TimetableBatchUiInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun oldRenderedEditAndDeleteActionsCannotMutateOrDismissReplacementSelection() = fixture { f ->
        f.holdWrites { queue ->
            f.open(f.first)
            val oldApply = action(app.getString(R.string.subject_batch_apply))
            val oldCancel = action(app.getString(R.string.button_cancel))
            val replacement = f.open(f.second)
            f.scenario.onActivity {
                assertTrue(oldApply())
                assertTrue("Old form must not enqueue an edit of the replacement", queue.pending.isEmpty())
                assertSame(replacement, f.model.batches.state.value.session)
                assertTrue(oldCancel())
                assertSame("Old form must not dismiss the replacement", replacement, f.model.batches.state.value.session)
            }

            f.open(f.first, edit = false)
            val oldDelete = action(app.getString(R.string.button_confirm))
            val deletionReplacement = f.open(f.second, edit = false)
            f.scenario.onActivity {
                assertTrue(oldDelete())
                assertTrue("Old form must not enqueue deletion of the replacement", queue.pending.isEmpty())
                assertSame(deletionReplacement, f.model.batches.state.value.session)
            }
        }
    }

    @Test fun failedRealWriteKeepsFormAndRetryCommitsBeforeClosingAndRoomRefresh() = fixture { f ->
        val repository = f.repository
        val daoField = TimetableRepository::class.java.getDeclaredField("eventItemDao").apply { isAccessible = true }
        val actualDao = daoField.get(repository) as EventItemDao
        val attempts = AtomicInteger()
        val proxy = Proxy.newProxyInstance(EventItemDao::class.java.classLoader, arrayOf(EventItemDao::class.java)) { _, method, args ->
            if (method.name == "updateEventsSync" && attempts.incrementAndGet() == 1) {
                throw IllegalStateException("fixture write failure")
            }
            try { method.invoke(actualDao, *(args ?: emptyArray())) }
            catch (wrapped: InvocationTargetException) { throw requireNotNull(wrapped.cause) }
        }
        val observed = CopyOnWriteArrayList<String?>()
        val rows = actualDao.getEventsOfTimetable(f.table.id)
        val observer = Observer<List<EventItem>> { events ->
            events.firstOrNull { it.id == f.first.id }?.let { observed.add(it.place) }
        }
        daoField.set(repository, proxy)
        f.scenario.onActivity { rows.observeForever(observer) }
        try {
            f.holdWrites { queue ->
                val session = f.open(f.first)
                setText("original", "retry room")
                f.scenario.onActivity { assertTrue(actionOnMain(app.getString(R.string.subject_batch_apply))!!()) }
                waitUntil { queue.pending.size == 1 }
                f.scenario.onActivity {
                    assertTrue(f.model.batches.state.value.writing)
                    assertSame(session, f.model.batches.state.value.session)
                }
                assertEquals("original", actualDao.getEventInIdsSync(listOf(f.first.id)).single().place)
                queue.runNext() // Runs the real persistence/use-case Runnable on the instrumentation worker.
                waitUntil { !f.model.batches.state.value.writing }
                f.scenario.onActivity { assertSame(session, f.model.batches.state.value.session) }
                assertEquals("original", actualDao.getEventInIdsSync(listOf(f.first.id)).single().place)
                assertFalse(observed.contains("retry room"))
                assertNotNull(action(app.getString(R.string.subject_batch_apply)))

                // The same actual form retains the edited text after failure, and can submit again.
                f.scenario.onActivity { assertTrue(actionOnMain(app.getString(R.string.subject_batch_apply))!!()) }
                waitUntil { queue.pending.size == 1 }
                queue.runNext()
                waitUntil { f.model.batches.state.value.session == null && observed.contains("retry room") }
                assertEquals(2, attempts.get())
                assertEquals("retry room", actualDao.getEventInIdsSync(listOf(f.first.id)).single().place)
                waitUntil {
                    var dismissed = false
                    f.scenario.onActivity { dismissed = actionOnMain(app.getString(R.string.subject_batch_apply)) == null }
                    dismissed
                }
            }
        } finally {
            f.scenario.onActivity { rows.removeObserver(observer) }
            daoField.set(repository, actualDao)
        }
    }

    private inner class Fixture(
        val scenario: ActivityScenario<SubjectActivity>,
        val fragment: TimetableFragment,
        val model: TimetableViewModel,
        val table: Timetable,
        val first: EventItem,
        val second: EventItem,
    ) {
        val repository = TimetableViewModel::class.java.getDeclaredField("timetableRepository")
            .apply { isAccessible = true }.get(model) as TimetableRepository

        fun open(event: EventItem, edit: Boolean = true): TimetableBatchSession {
            val label = app.getString(if (edit) R.string.subject_batch_apply else R.string.button_confirm)
            var previousAction: (() -> Boolean)? = null
            scenario.onActivity {
                previousAction = actionOnMain(label)
                val owner = TimetableFragment::class.java.getDeclaredField("viewOwner").apply { isAccessible = true }.get(fragment) as Long
                model.batches.request(owner, event, edit, TimetableBatchScope.THIS)
            }
            val session = runBlocking {
                withTimeout(10_000) {
                    requireNotNull(model.batches.state.first {
                        val active = it.session
                        active != null && active.events.singleOrNull()?.id == event.id && active.edit == edit
                    }.session)
                }
            }
            // Wait for the replacement dialog to have its own actual Compose tree.
            waitUntil {
                var replaced = false
                scenario.onActivity {
                    val currentAction = actionOnMain(label)
                    replaced = currentAction != null && currentAction !== previousAction
                }
                replaced
            }
            return session
        }

        fun holdWrites(block: (HoldingQueue) -> Unit) {
            val field = TimetableRepository::class.java.getDeclaredField("executor").apply { isAccessible = true }
            val original = field.get(repository)
            val queue = HoldingQueue()
            field.set(repository, queue)
            try { block(queue) } finally { field.set(repository, original); queue.shutdownNow() }
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val db = AppDatabase.getDatabase(app)
        val table = Timetable().apply { name = "Batch UI fixture" }
        // Real subject identity for the Hilt host; blank resource query avoids live HTTP.
        val subject = TermSubject().apply { timetableId = table.id; name = ""; code = null }
        val first = EventItem().apply { timetableId = table.id; subjectId = subject.id; name = "first"; place = "original" }
        val second = EventItem().apply { timetableId = table.id; subjectId = subject.id; name = "second"; place = "original second" }
        try {
            db.timetableDao().saveTimetableSync(table)
            db.subjectDao().saveSubjectSync(subject)
            db.eventItemDao().insertEventSync(first)
            db.eventItemDao().insertEventSync(second)
            ActivityScenario.launch<SubjectActivity>(Intent(app, SubjectActivity::class.java).putExtra("subjectId", subject.id)).use { scenario ->
                lateinit var fragment: TimetableFragment
                lateinit var model: TimetableViewModel
                scenario.onActivity { activity ->
                    val container = FrameLayout(activity).apply { id = View.generateViewId() }
                    activity.setContentView(container)
                    fragment = TimetableFragment()
                    activity.supportFragmentManager.beginTransaction().add(container.id, fragment, "batch-ui-fixture").commitNow()
                    model = ViewModelProvider(fragment)[TimetableViewModel::class.java]
                }
                instrumentation.waitForIdleSync()
                block(Fixture(scenario, fragment, model, table, first, second))
            }
        } finally {
            db.eventItemDao().deleteEventsInIdsSync(listOf(first.id, second.id))
            db.subjectDao().deleteSubjectsInIdsSync(listOf(subject.id))
            db.timetableDao().deleteTimetablesInIdsSync(listOf(table.id))
        }
    }

    private class HoldingQueue : AbstractExecutorService() {
        val pending = CopyOnWriteArrayList<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); pending.add(command) }
        fun runNext() = pending.removeAt(0).run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = pending.toMutableList().also { pending.clear(); stopped = true }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }

    /** Capture the real rendered button callback so it can be delivered after that form is replaced. */
    private fun action(text: String): () -> Boolean {
        var captured: (() -> Boolean)? = null
        waitUntil {
            instrumentation.runOnMainSync { captured = actionOnMain(text) }
            captured != null
        }
        return requireNotNull(captured)
    }

    private fun actionOnMain(text: String): (() -> Boolean)? {
        fun hasLabel(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                node.children.any(::hasLabel)
        return semantics().firstNotNullOfOrNull { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.takeIf { hasLabel(node) }
        }
    }

    private fun setText(previous: String, next: String) {
        var updated = false
        waitUntil {
            instrumentation.runOnMainSync {
                val node = semantics().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text == previous }
                updated = node?.config?.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(next)) == true
            }
            updated
        }
        instrumentation.waitForIdleSync()
    }

    private fun semantics(): Sequence<SemanticsNode> {
        fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
        fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(nodes(it)) }
        }
        return WindowInspector.getGlobalWindowViews().asReversed().asSequence().flatMap(::views).mapNotNull { view ->
            view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                ?.invoke(view) as? SemanticsOwner
        }.flatMap { nodes(it.rootSemanticsNode) }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Actual batch form did not reach the expected state")
    }
}
