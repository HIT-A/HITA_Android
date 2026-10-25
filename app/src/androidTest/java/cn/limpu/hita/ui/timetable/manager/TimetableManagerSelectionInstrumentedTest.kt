package cn.limpu.hita.ui.timetable.manager

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.FriendTimetableRepository
import cn.limpu.hita.data.repository.TimetableExportRepository
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.repository.TimetableSharingRepository
import cn.limpu.hita.feature.timetable.display.DisplayScopeCoordinator
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableManagerSelectionInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences get() = app.getSharedPreferences("timetable_display_scope", 0)
    private val store = ViewModelStore()
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository
    private lateinit var model: TimetableManagerViewModel
    private var previousPreferences: Map<String, *> = emptyMap<String, Any>()
    private val selections = mutableListOf<DisplayScope>()
    private val selectionThreads = mutableListOf<Thread>()
    private val refreshOrder = mutableListOf<String>()
    private val refreshSelections = mutableListOf<DisplayScope?>()

    @Before fun setUp() {
        previousPreferences = preferences.all
        preferences.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        table("initial")
        val resolver = DisplayScopeResolver(app, db)
        resolver.persistSelection(DisplayScope.Single("initial"))
        val selector = DisplayScopeCoordinator(
            persistSelection = { scope ->
                selections += scope
                selectionThreads += Thread.currentThread()
                refreshOrder += "persist"
                resolver.persistSelection(scope)
            },
            refreshWidgets = {
                refreshOrder += "widgets"
                refreshSelections += persistedSelection()
            },
            scheduleLiveCourse = {
                refreshOrder += "liveCourse"
                refreshSelections += persistedSelection()
            },
        )
        repository = TimetableRepository(app, db, selector)
        instrumentation.runOnMainSync {
            model = TimetableManagerViewModel(
                repository, selector, TimetableSharingRepository(db),
                TimetableExportRepository(db, app), FriendTimetableRepository(db),
            )
            store.put("manager", model)
        }
        flush()
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { store.clear() }
        try {
            flush()
        } finally {
            db.close()
            preferences.edit().clear().apply {
                previousPreferences.forEach { (key, value) -> if (value is String) putString(key, value) }
            }.commit()
        }
    }

    @Test fun joiningGroupSelectsItOnceAfterSuccessfulMembershipWrite() {
        group("destination")
        table("member", "destination")
        table("imported")

        instrumentation.runOnMainSync { model.joinGroup("imported", "destination") }
        flush()

        assertEquals(setOf("member", "imported"),
            db.timetableGroupDao().getMembersSync("destination").map { it.id }.toSet())
        assertSelectedOnce(DisplayScope.Group("destination"))
    }

    @Test fun creatingGroupSelectsItOnceAfterSuccessfulGroupCreation() {
        table("target")
        table("imported")

        instrumentation.runOnMainSync { model.createGroupWith("target", "imported") }
        flush()

        val group = db.timetableGroupDao().getGroupsSync().single()
        assertEquals(setOf("target", "imported"),
            db.timetableGroupDao().getMembersSync(group.id).map { it.id }.toSet())
        assertSelectedOnce(DisplayScope.Group(group.id))
    }

    @Test fun joiningMissingTimetableOrGroupDoesNotSelect() {
        group("destination")
        table("member", "destination")
        table("imported")

        instrumentation.runOnMainSync {
            model.joinGroup("missing", "destination")
            model.joinGroup("imported", "missing")
        }
        flush()

        assertEquals(listOf("member"), db.timetableGroupDao().getMembersSync("destination").map { it.id })
        assertNull(db.timetableDao().getTimetableByIdSync("imported")!!.groupId)
        assertNoSelection()
    }

    @Test fun creatingGroupWithEitherMemberMissingDoesNotSelect() {
        table("existing")

        instrumentation.runOnMainSync {
            model.createGroupWith("missing", "existing")
            model.createGroupWith("existing", "missing")
        }
        flush()

        assertTrue(db.timetableGroupDao().getGroupsSync().isEmpty())
        assertNull(db.timetableDao().getTimetableByIdSync("existing")!!.groupId)
        assertNoSelection()
    }

    @Test fun failedJoinKeepsPreviousMembershipAndDoesNotSelect() {
        group("old")
        group("destination")
        table("imported", "old")
        table("member", "destination")
        rejectMemberMove("imported")

        instrumentation.runOnMainSync { model.joinGroup("imported", "destination") }
        flush()

        assertEquals("old", db.timetableDao().getTimetableByIdSync("imported")!!.groupId)
        assertEquals(setOf("old", "destination"), db.timetableGroupDao().getGroupsSync().map { it.id }.toSet())
        assertNoSelection()
    }

    @Test fun failedSecondMemberMoveRollsBackNewGroupAndDoesNotSelect() {
        group("old")
        table("target", "old")
        table("imported")
        rejectMemberMove("imported")

        instrumentation.runOnMainSync { model.createGroupWith("target", "imported") }
        flush()

        assertEquals(listOf("old"), db.timetableGroupDao().getGroupsSync().map { it.id })
        assertEquals("old", db.timetableDao().getTimetableByIdSync("target")!!.groupId)
        assertNull(db.timetableDao().getTimetableByIdSync("imported")!!.groupId)
        assertNoSelection()
    }

    private fun assertSelectedOnce(scope: DisplayScope) {
        assertEquals(listOf(scope), selections)
        assertEquals(listOf(Looper.getMainLooper().thread), selectionThreads)
        assertEquals(listOf("persist", "widgets", "liveCourse"), refreshOrder)
        assertEquals(listOf(scope, scope), refreshSelections)
        assertEquals(scope, persistedSelection())
    }

    private fun assertNoSelection() {
        assertTrue(selections.isEmpty())
        assertTrue(refreshOrder.isEmpty())
        assertEquals(DisplayScope.Single("initial"), persistedSelection())
    }

    private fun persistedSelection(): DisplayScope? = DisplayScope.of(
        preferences.getString("display_scope_type", null),
        preferences.getString("display_scope_id", null),
    )

    private fun table(id: String, groupId: String? = null) = Timetable().apply {
        this.id = id
        name = id
        this.groupId = groupId
        startTime = Timestamp(0)
        endTime = Timestamp(Long.MAX_VALUE)
        db.timetableDao().saveTimetableSync(this)
    }

    private fun group(id: String) = TimetableGroupEntity().apply {
        this.id = id
        name = id
        db.timetableGroupDao().saveGroupSync(this)
    }

    private fun rejectMemberMove(id: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_member BEFORE UPDATE OF groupId ON timetable " +
                "WHEN NEW.id = '$id' BEGIN SELECT RAISE(ABORT, 'injected member write failure'); END",
        )
    }

    private fun flush() {
        val done = CountDownLatch(1)
        repository.actionRenameTimetable("missing-barrier", "barrier") { done.countDown() }
        assertTrue("Repository work did not finish", done.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
}
