package cn.limpu.hita.ui.subject

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.resource.*
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.SubjectInfoUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubjectViewModelInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = ViewModelStore()
    private val subjects = mutableMapOf<String, MutableLiveData<TermSubject>>()
    private val classes = mutableMapOf<String, MutableLiveData<List<EventItem>>>()
    private val tables = mutableMapOf<String, MutableLiveData<Timetable>>()
    private val teachers = mutableMapOf<String, MutableLiveData<List<String>>>()
    private val tokens = MutableLiveData<EASToken>()
    private var campus = EASToken.Campus.SHENZHEN
    private val gateway = Gateway()
    private val saved = mutableListOf<Pair<String, SubjectInfoUpdate>>()
    private val writes = mutableListOf<Pair<List<EventItem>, (Result<Unit>) -> Unit>>()
    private val notices = mutableListOf<SubjectWriteNotice>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var model: SubjectViewModel

    @Before fun setUp() = main {
        model = SubjectViewModel(
            SubjectPageInputs(
                subject = { subjects.getOrPut(it) { MutableLiveData() } },
                timetable = { tables.getOrPut(it) { MutableLiveData() } },
                classes = { classes.getOrPut(it) { MutableLiveData() } },
                teachers = { teachers.getOrPut(it.id) { MutableLiveData() } },
                tokenChanges = tokens,
                campus = { SubjectCampusSnapshot(campus, "shenzhen", campus == EASToken.Campus.SHENZHEN) },
                saveSubject = { id, update -> saved.add(id to update) },
                update = { values, callback -> writes.add(values.toList() to callback) },
                delete = { values, callback -> writes.add(values.toList() to callback) },
            ),
            gateway,
            SubjectResourceText("missing", "choose", "failed", "empty", "open"),
        )
        store.put("subject", model)
        scope.launch { model.notices.collect { notices.add(it) } }
    }

    @After fun tearDown() = main {
        try { store.clear() } finally { scope.cancel() }
    }

    @Test fun oldFallbackCannotBackfillNewSubjectAndExistingCodeIsNeverOverwritten() = main {
        show("A")
        gateway.agents[0].callback(Result.failure(IllegalStateException("offline")))
        val b = show("B")
        gateway.fallbacks[0].callback(Result.success(listOf(candidate())))
        assertTrue(saved.isEmpty())
        assertEquals("B", model.resources.state.value.key?.subjectId)
        gateway.agents[1].callback(Result.success(listOf(candidate())))
        assertEquals(listOf("B"), saved.map { it.first })
        assertEquals("COMP1001", (saved.single().second as SubjectInfoUpdate.FillCourseCode).value)
        assertNull(b.code)
        b.code = "existing"
        subjects.getValue("B").value = b
        gateway.agents.last().callback(Result.success(listOf(candidate())))
        assertEquals(1, saved.size)
    }

    @Test fun sameIdNameCodeAndCampusChangesOwnNewRequestsWhileReplayReusesCurrentWork() = main {
        val a = show("A")
        model.startRefresh("A")
        subjects.getValue("A").value = a
        assertEquals(1, gateway.agents.size)
        a.name = "数据库"
        subjects.getValue("A").value = a
        assertEquals("数据库", model.screen.value.subject?.name)
        a.code = "COMP2001"
        subjects.getValue("A").value = a
        campus = EASToken.Campus.WEIHAI
        tokens.value = EASToken().apply { this.campus = EASToken.Campus.WEIHAI }
        assertEquals(4, gateway.agents.size)
        assertEquals(EASToken.Campus.WEIHAI, model.resources.state.value.key?.campus)
        assertFalse(model.screen.value.subjectMetaSupported)
        gateway.agents[0].callback(Result.success(listOf(candidate())))
        assertTrue(saved.isEmpty())
    }

    @Test fun liveSelectionIntersectionAndWriteResultControlCompletion() = main {
        show("A")
        val first = event("one")
        val second = event("two")
        classes.getValue("A").value = listOf(first, second)
        model.selectCourse("one")
        model.selectCourse("two")
        model.selectCourse("missing")
        classes.getValue("A").value = listOf(second)
        assertEquals(setOf("two"), model.screen.value.selectedEventIds)
        val key = requireNotNull(model.resources.state.value.key)
        val edited = second.subjectEditCopy().apply { place = "new room" }
        model.updateCourses(key, listOf(edited), skippedTime = true)
        assertTrue(model.screen.value.writing)
        assertTrue(notices.isEmpty())
        assertEquals("original", second.place)
        assertEquals("new room", writes.single().first.single().place)
        writes.single().second(Result.failure(IllegalStateException("SQL failed")))
        assertEquals(setOf("two"), model.screen.value.selectedEventIds)
        assertFalse(notices.single().success)
        model.deleteCourses(key, listOf(second))
        writes.last().second(Result.success(Unit))
        assertTrue(model.screen.value.selectedEventIds.isEmpty())
        assertTrue(notices.last().success)
        assertFalse(notices.last().updated)
    }

    @Test fun navigationRejectsOldDialogAndOldWriteCompletion() = main {
        show("A")
        val event = event("one")
        classes.getValue("A").value = listOf(event)
        val oldKey = requireNotNull(model.resources.state.value.key)
        model.deleteCourses(oldKey, listOf(event))
        show("B")
        model.saveCredit(oldKey, 9f)
        model.updateCourses(oldKey, listOf(event), false)
        writes.single().second(Result.success(Unit))
        assertTrue(saved.isEmpty())
        assertEquals(1, writes.size)
        assertTrue(notices.isEmpty())
        assertEquals("B", model.screen.value.subject?.id)
    }

    @Test fun clearingViewModelStoreDetachesRoomAndCampusObserversAndRejectsLateDelivery() = main {
        show("A")
        assertTrue(subjects.getValue("A").hasObservers())
        assertTrue(tokens.hasObservers())
        store.clear()
        assertFalse(subjects.getValue("A").hasObservers())
        assertFalse(classes.getValue("A").hasObservers())
        assertFalse(tables.getValue("table").hasObservers())
        assertFalse(teachers.getValue("A").hasObservers())
        assertFalse(tokens.hasObservers())
        assertTrue(gateway.agents.single().cancelled)
        gateway.agents.single().callback(Result.success(listOf(candidate())))
        assertTrue(saved.isEmpty())
        assertNull(model.resources.state.value.key)
    }

    @Test fun replayWhileNextSubjectIsStillLoadingCannotRevivePreviousSubject() = main {
        show("A")
        model.startRefresh("B")
        model.startRefresh("B")
        assertNull(model.resources.state.value.key)
        assertEquals(1, gateway.agents.size)
        gateway.agents.single().callback(Result.success(listOf(candidate())))
        assertTrue(saved.isEmpty())
        assertNull(model.screen.value.subject)
    }

    @Test fun noticeQueuedWhileScreenStoppedDoesNotAppearOnDifferentSubject() = main {
        show("A")
        val a = event("one")
        classes.getValue("A").value = listOf(a)
        scope.cancel()
        model.deleteCourses(requireNotNull(model.resources.state.value.key), listOf(a))
        writes.single().second(Result.success(Unit))
        show("B")
        val resumed = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            resumed.launch { model.notices.collect { notices.add(it) } }
            assertTrue(notices.isEmpty())
            val b = event("two").apply { subjectId = "B" }
            classes.getValue("B").value = listOf(b)
            model.deleteCourses(requireNotNull(model.resources.state.value.key), listOf(b))
            writes.last().second(Result.success(Unit))
            assertEquals(1, notices.size)
            assertTrue(notices.single().success)
        } finally {
            resumed.cancel()
        }
    }

    private fun show(id: String): TermSubject {
        model.startRefresh(id)
        return TermSubject().apply {
            this.id = id; name = "程序设计"; timetableId = "table"
            subjects.getValue(id).value = this
        }
    }
    private fun event(id: String) = EventItem().apply { this.id = id; subjectId = "A"; timetableId = "table"; place = "original" }
    private fun candidate() = CourseResourceItem(repoName = "COMP1001", courseName = "程序设计", courseCode = "COMP1001")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private class Pending(val callback: (Result<List<CourseResourceItem>>) -> Unit) {
        var cancelled = false
        val handle = SubjectRequest { cancelled = true }
    }
    private class Gateway : SubjectResourceGateway {
        val agents = mutableListOf<Pending>()
        val fallbacks = mutableListOf<Pending>()
        override fun agent(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit) = Pending(result).also { agents.add(it) }.handle
        override fun direct(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit) = Pending(result).also { fallbacks.add(it) }.handle
        override fun readme(repo: String, campus: String, result: (Result<CourseReadmeData>) -> Unit) = SubjectRequest {}
        override fun search(query: String, result: (Result<List<ExternalCourseItem>>) -> Unit) = SubjectRequest {}
        override fun directory(path: String, source: ResourceSource, result: (Result<List<ExternalResourceEntry>>) -> Unit) = SubjectRequest {}
    }
}
