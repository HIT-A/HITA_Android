package cn.limpu.hita.ui.subject

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubjectActivityInstrumentedTest {
    @Test fun subjectIntentRealScreenRecreationAndBackSelectionUseSameViewModel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)
        val table = Timetable().apply { name = "Subject activity fixture" }
        // Blank resource query deliberately exercises the production adapter without live HTTP.
        val subject = TermSubject().apply { name = ""; timetableId = table.id }
        val event = EventItem().apply { subjectId = subject.id; timetableId = table.id; name = "Subject fixture class" }
        try {
            db.timetableDao().saveTimetableSync(table)
            db.subjectDao().saveSubjectSync(subject)
            db.eventItemDao().insertEventSync(event)
            ActivityScenario.launch<SubjectActivity>(Intent(app, SubjectActivity::class.java).putExtra("subjectId", subject.id)).use { scenario ->
                lateinit var initial: SubjectViewModel
                scenario.onActivity { initial = ViewModelProvider(it)[SubjectViewModel::class.java] }
                runBlocking {
                    withTimeout(10_000) { initial.screen.first { it.subject?.id == subject.id && it.classes.size == 1 } }
                }
                scenario.onActivity {
                    assertEquals(subject.id, it.intent.getStringExtra("subjectId"))
                    initial.selectCourse(event.id)
                }
                scenario.recreate()
                scenario.onActivity {
                    val current = ViewModelProvider(it)[SubjectViewModel::class.java]
                    assertSame(initial, current)
                    assertEquals(setOf(event.id), current.screen.value.selectedEventIds)
                    assertEquals(subject.id, current.resources.state.value.key?.subjectId)
                    it.onBackPressedDispatcher.onBackPressed()
                    assertTrue(current.screen.value.selectedEventIds.isEmpty())
                    assertFalse(it.isFinishing)
                }
            }
        } finally {
            db.eventItemDao().deleteEventsInIdsSync(listOf(event.id))
            db.subjectDao().deleteSubjectsInIdsSync(listOf(subject.id))
            db.timetableDao().deleteTimetablesInIdsSync(listOf(table.id))
        }
    }
}
