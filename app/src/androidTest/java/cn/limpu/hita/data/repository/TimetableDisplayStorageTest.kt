package cn.limpu.hita.data.repository

import android.app.Application
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.feature.timetable.display.DisplayScopeCoordinator
import cn.limpu.hita.feature.timetableshare.protocol.*
import cn.limpu.hita.ui.main.timeline.FragmentTimelineViewModel
import com.limpu.component.data.DataState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TimetableDisplayStorageTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository
    private lateinit var selectionResolver: DisplayScopeResolver
    private lateinit var coordinator: DisplayScopeCoordinator
    private val selections = CopyOnWriteArrayList<DisplayScope>()
    private val selectionTransactions = CopyOnWriteArrayList<Boolean>()
    private val refreshes = CopyOnWriteArrayList<Refresh>()
    private var onWidgetRefresh: () -> Unit = {}
    private val preferences get() = app.getSharedPreferences("timetable_display_scope", 0)
    private var previousPreferences: Map<String, *> = emptyMap<String, Any>()

    private data class Refresh(val source: String, val type: String?, val id: String?)

    @Before fun setUp() {
        previousPreferences = preferences.all
        preferences.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        selectionResolver = DisplayScopeResolver(app, db)
        coordinator = DisplayScopeCoordinator(
            persistSelection = { scope ->
                selections += scope
                selectionTransactions += db.inTransaction()
                selectionResolver.persistSelection(scope)
            },
            refreshWidgets = {
                recordRefresh("widgets")
                onWidgetRefresh()
            },
            scheduleLiveCourse = { recordRefresh("liveCourse") },
        )
        repository = TimetableRepository(app, db, coordinator)
    }

    private fun recordRefresh(source: String) {
        refreshes += Refresh(source, preferences.getString("display_scope_type", null),
            preferences.getString("display_scope_id", null))
    }

    private fun clearSelectionRecords() {
        selections.clear()
        selectionTransactions.clear()
        refreshes.clear()
    }

    @After fun tearDown() {
        flush()
        db.close()
        preferences.edit().clear().apply {
            previousPreferences.forEach { (key, value) -> if (value is String) putString(key, value) }
        }.commit()
    }

    private fun table(id: String, groupId: String? = null, start: Long = 0): Timetable = Timetable().apply {
        this.id = id
        name = id
        this.groupId = groupId
        startTime = Timestamp(start)
        endTime = Timestamp(Long.MAX_VALUE)
        db.timetableDao().saveTimetableSync(this)
    }

    private fun group(id: String) = TimetableGroupEntity().apply {
        this.id = id
        name = id
        db.timetableGroupDao().saveGroupSync(this)
    }

    private fun <T> callback(action: ((T) -> Unit) -> Unit): T {
        val done = CountDownLatch(1)
        var result: T? = null
        action { value ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            result = value
            done.countDown()
        }
        assertTrue("Repository callback timed out", done.await(10, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun flush() {
        callback<Boolean> { repository.actionRenameTimetable("missing-barrier", "barrier", it) }
    }

    private fun <T> awaitValue(data: LiveData<T>, accepts: (T) -> Boolean): T {
        val done = CountDownLatch(1)
        var result: T? = null
        val observer = Observer<T> { value ->
            if (accepts(value)) {
                result = value
                done.countDown()
            }
        }
        instrumentation.runOnMainSync { data.observeForever(observer) }
        try {
            assertTrue("LiveData timed out", done.await(10, TimeUnit.SECONDS))
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally { instrumentation.runOnMainSync { data.removeObserver(observer) } }
    }

    @Test fun movingLastMemberDeletesOldGroupAndRejectsMissingDestination() {
        group("old"); group("new")
        table("member", "old"); table("other", "new")
        assertTrue(callback<Boolean> { repository.actionAddToGroup("member", "new", it) })
        assertNull(db.timetableGroupDao().getGroupByIdSync("old"))
        assertEquals("new", db.timetableDao().getTimetableByIdSync("member")!!.groupId)
        assertFalse(callback<Boolean> { repository.actionAddToGroup("member", "gone", it) })
        assertEquals("new", db.timetableDao().getTimetableByIdSync("member")!!.groupId)
    }

    @Test fun creatingGroupValidatesAllMembersAndCleansPreviousGroups() {
        group("old")
        table("a", "old"); table("b")
        assertNull(callback<String?> { repository.actionCreateGroupWithMembers(emptyList(), it) })
        assertNull(callback<String?> { repository.actionCreateGroupWithMembers(listOf("a", "gone"), it) })
        assertEquals(listOf("old"), db.timetableGroupDao().getGroupsSync().map { it.id })
        assertEquals("old", db.timetableDao().getTimetableByIdSync("a")!!.groupId)
        val newId = callback<String?> { repository.actionCreateGroupWithMembers(listOf("a", "b", "a"), it) }!!
        assertNull(db.timetableGroupDao().getGroupByIdSync("old"))
        assertEquals(setOf("a", "b"), db.timetableGroupDao().getMembersSync(newId).map { it.id }.toSet())
    }

    @Test fun failedSecondMemberWriteRollsBackGroupAndEarlierMove() {
        group("old")
        table("a", "old"); table("b")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_member BEFORE UPDATE OF groupId ON timetable WHEN NEW.id = 'b' BEGIN SELECT RAISE(ABORT, 'injected member write failure'); END")
        assertNull(callback<String?> { repository.actionCreateGroupWithMembers(listOf("a", "b"), it) })
        assertEquals(listOf("old"), db.timetableGroupDao().getGroupsSync().map { it.id })
        assertEquals("old", db.timetableDao().getTimetableByIdSync("a")!!.groupId)
        assertNull(db.timetableDao().getTimetableByIdSync("b")!!.groupId)
    }

    @Test fun deletingWithStaleCallerMembershipStillDeletesEmptyGroup() {
        val stale = table("a")
        group("g")
        db.timetableGroupDao().setTimetableGroupSync("a", "g")
        db.eventItemDao().insertEventSync(EventItem().apply { timetableId = "a"; name = "course" })
        repository.actionDeleteTimetables(listOf(stale))
        flush()
        assertNull(db.timetableDao().getTimetableByIdSync("a"))
        assertNull(db.timetableGroupDao().getGroupByIdSync("g"))
        assertTrue(db.eventItemDao().getEventsOfTimetableSync("a").isEmpty())
    }

    @Test fun renameCallbacksAreMainThreadAndReflectMissingRows() {
        table("a"); group("g")
        assertTrue(callback<Boolean> { repository.actionRenameTimetable("a", "renamed", it) })
        assertEquals("renamed", db.timetableDao().getTimetableByIdSync("a")!!.name)
        assertFalse(callback<Boolean> { repository.actionRenameTimetable("gone", "valid", it) })
        assertFalse(callback<Boolean> { repository.actionRenameGroup("gone", "valid", it) })
        assertFalse(callback<Boolean> { repository.actionRenameGroup("g", "   ", it) })
        assertTrue(callback<Boolean> { repository.actionRenameGroup("g", "renamed group", it) })
        assertEquals("renamed group", db.timetableGroupDao().getGroupByIdSync("g")!!.name)
    }

    @Test fun legacyDuplicateCleanupAlsoDeletesEmptyGroups() {
        group("g")
        table("default", "g").apply {
            name = app.getString(cn.limpu.hita.R.string.default_timetable_name)
            db.timetableDao().saveTimetableSync(this)
        }
        table("eas").apply {
            code = "BENBU:2026-2027-1"
            db.timetableDao().saveTimetableSync(this)
        }
        repository.cleanupDefaultDuplicateTimetablesSync()
        assertNull(db.timetableDao().getTimetableByIdSync("default"))
        assertNull(db.timetableGroupDao().getGroupByIdSync("g"))
        assertNotNull(db.timetableDao().getTimetableByIdSync("eas"))
    }

    private fun snapshot(): SharedTimetable {
        val start = 1767225600000L
        return SharedTimetable("5fb93366-071b-4bd6-a658-ff402a25311b", "朋友", ShareMode.FULL,
            SharedTerm("课表", "春季", start, start + 86400000L * 100),
            listOf(SharedPeriod(480, 540)), listOf("数学", "操作系统"),
            listOf(SharedOccurrence(start + 28800000, start + 32400000, 0, null),
                SharedOccurrence(start + 115200000, start + 118800000, 1, null)))
    }

    @Test fun eventWriteFailureRollsBackEntirePersonalSnapshotImport() {
        table("existing")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON events WHEN NEW.name = '操作系统' BEGIN SELECT RAISE(ABORT, 'injected event write failure'); END")
        val result = awaitValue(repository.importShareSnapshotAsTimetable(snapshot())) {
            it.state == DataState.STATE.FETCH_FAILED
        }
        assertEquals(DataState.STATE.FETCH_FAILED, result.state)
        assertTrue(result.message.orEmpty().contains("injected event write failure"))
        assertEquals(listOf("existing"), db.timetableDao().getTimetablesSync().map { it.id })
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM subject").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM events").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun eventWriteFailureRollsBackNewIcsTimetable() {
        table("existing")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_ics BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'injected ICS write failure'); END")
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//HITA//Test//EN",
            "BEGIN:VEVENT", "UID:atomic-import", "DTSTAMP:20260101T000000Z",
            "DTSTART:20260101T080000Z", "DTEND:20260101T090000Z", "SUMMARY:Math",
            "END:VEVENT", "END:VCALENDAR", "",
        ).joinToString("\r\n")
        val result = awaitValue(repository.importFromICSAsNewTimetable(ics.byteInputStream(), "test.ics")) {
            it.state == DataState.STATE.FETCH_FAILED
        }
        assertTrue(result.message.orEmpty().contains("injected ICS write failure"))
        assertEquals(listOf("existing"), db.timetableDao().getTimetablesSync().map { it.id })
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM subject").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM events").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun newEasTableGroupsWithPreviousDefaultInsteadOfSelectingItself() {
        table("old", start = 1000)
        table("new", start = 0)
        var visibleAtRefresh: ResolvedDisplayScope? = null
        onWidgetRefresh = { visibleAtRefresh = selectionResolver.currentSync() }
        repository.autoGroupAfterEasImportSync("new")
        val resolver = DisplayScopeResolver(app, db)
        val scope = resolver.currentSync()!!
        assertTrue(scope.scope is DisplayScope.Group)
        assertEquals(setOf("old", "new"), scope.timetableIds.toSet())
        assertEquals(1, db.timetableGroupDao().getGroupsSync().size)
        assertEquals(listOf(scope.scope), selections)
        assertEquals("Selection must follow the grouping transaction", listOf(false), selectionTransactions)
        assertEquals(scope.scope, visibleAtRefresh!!.scope)
        assertEquals(setOf("old", "new"), visibleAtRefresh!!.timetableIds.toSet())
        assertEquals(listOf(Refresh("widgets", "group", scope.scope.id),
            Refresh("liveCourse", "group", scope.scope.id)), refreshes)
    }

    @Test fun addingEasTableToExistingGroupDoesNotSelectOrRefreshAgain() {
        group("g")
        table("old", "g"); table("other", "g"); table("new")
        coordinator.select(DisplayScope.Group("g"))
        clearSelectionRecords()

        repository.autoGroupAfterEasImportSync("new")

        assertEquals(setOf("old", "other", "new"), db.timetableGroupDao().getMembersSync("g").map { it.id }.toSet())
        assertEquals("g", preferences.getString("display_scope_id", null))
        assertTrue(selections.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test fun automaticGroupingNoOpsDoNotSelectOrRefresh() {
        table("only")
        // No other display target, and no imported row, respectively.
        repository.autoGroupAfterEasImportSync("only")
        repository.autoGroupAfterEasImportSync("missing")
        assertTrue(selections.isEmpty())
        assertTrue(refreshes.isEmpty())

        coordinator.select(DisplayScope.Single("only"))
        clearSelectionRecords()
        repository.autoGroupAfterEasImportSync("only")
        group("g")
        table("already-grouped", "g")
        repository.autoGroupAfterEasImportSync("already-grouped")

        assertEquals(listOf("already-grouped"), db.timetableGroupDao().getMembersSync("g").map { it.id })
        assertNull(db.timetableDao().getTimetableByIdSync("only")!!.groupId)
        assertTrue(selections.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test fun failedAutomaticGroupingRollsBackBeforeAnySelectionOrRefresh() {
        table("old"); table("new")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_auto_group BEFORE UPDATE OF groupId ON timetable WHEN NEW.id = 'new' BEGIN SELECT RAISE(ABORT, 'injected auto group failure'); END")

        try {
            repository.autoGroupAfterEasImportSync("new")
            fail("Automatic grouping must propagate its transaction failure")
        } catch (failure: android.database.sqlite.SQLiteException) {
            assertTrue(failure.message.orEmpty().contains("injected auto group failure"))
        }

        assertTrue(db.timetableGroupDao().getGroupsSync().isEmpty())
        assertTrue(db.timetableDao().getTimetablesSync().all { it.groupId == null })
        assertFalse(preferences.contains("display_scope_id"))
        assertTrue(selections.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test fun selectionIsVisibleDuringRefreshAndEventuallyToBothResolvers() {
        table("a"); table("b", start = 1000)
        val otherResolver = DisplayScopeResolver(app, db)
        val initial = List(2) { CountDownLatch(1) }
        val updated = List(2) { CountDownLatch(1) }
        val observers = listOf(selectionResolver, otherResolver).mapIndexed { index, resolver ->
            val observer = Observer<ResolvedDisplayScope?> { scope ->
                when (scope?.scope) {
                    DisplayScope.Single("a") -> initial[index].countDown()
                    DisplayScope.Single("b") -> updated[index].countDown()
                    else -> Unit
                }
            }
            resolver to observer
        }
        instrumentation.runOnMainSync {
            observers.forEach { (resolver, observer) -> resolver.resolved.observeForever(observer) }
        }
        try {
            initial.forEach { assertTrue("Both readers must first observe the default", it.await(10, TimeUnit.SECONDS)) }

            coordinator.select(DisplayScope.Single("b"))

            assertEquals(listOf(Refresh("widgets", "single", "b"),
                Refresh("liveCourse", "single", "b")), refreshes)
            assertEquals(DisplayScope.Single("b"), selectionResolver.currentSync()!!.scope)
            assertEquals(DisplayScope.Single("b"), otherResolver.currentSync()!!.scope)
            updated.forEach { assertTrue("Both readers must observe the new preference", it.await(10, TimeUnit.SECONDS)) }
        } finally {
            instrumentation.runOnMainSync {
                observers.forEach { (resolver, observer) -> resolver.resolved.removeObserver(observer) }
            }
        }
    }

    @Test fun liveDataFallbackClearsInvalidSelectionWithoutPlatformRefresh() {
        table("a")
        val selected = table("b", start = 1000)
        coordinator.select(DisplayScope.Single("b"))
        clearSelectionRecords()
        val initial = CountDownLatch(1)
        val fallback = CountDownLatch(1)
        val preferenceCleared = CountDownLatch(1)
        val observer = Observer<ResolvedDisplayScope?> { scope ->
            if (scope?.scope == DisplayScope.Single("b")) initial.countDown()
            if (scope?.scope == DisplayScope.Single("a")) fallback.countDown()
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "display_scope_id" && !preferences.contains(key)) preferenceCleared.countDown()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        instrumentation.runOnMainSync { selectionResolver.resolved.observeForever(observer) }
        try {
            assertTrue(initial.await(10, TimeUnit.SECONDS))
            repository.actionDeleteTimetables(listOf(selected))
            flush()

            assertTrue("LiveData must fall back after deletion", fallback.await(10, TimeUnit.SECONDS))
            assertTrue("The authoritative fallback must clear the preference", preferenceCleared.await(10, TimeUnit.SECONDS))
            assertEquals(DisplayScope.Single("a"), selectionResolver.currentSync()!!.scope)
            assertFalse(preferences.contains("display_scope_type"))
            assertFalse(preferences.contains("display_scope_id"))
            assertTrue(selections.isEmpty())
            assertTrue(refreshes.isEmpty())
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            instrumentation.runOnMainSync { selectionResolver.resolved.removeObserver(observer) }
        }
    }

    @Test fun danglingLegacyMemberStillAppearsAsAnIndependentDisplayOption() {
        table("a", "missing-group")
        val resolver = DisplayScopeResolver(app, db)
        val options = awaitValue(resolver.options) { it.size == 1 }
        assertEquals(DisplayScope.Single("a"), options.single().scope)
        assertEquals(DisplayScope.Single("a"), resolver.currentSync()!!.scope)
    }

    @Test fun selectedSingleBecomesWholeGroupAndDissolvedGroupFallsBack() {
        group("g")
        table("a", "g"); table("b", "g"); table("unrelated", start = 1000)
        val resolver = DisplayScopeResolver(app, db)
        coordinator.select(DisplayScope.Single("a"))
        assertEquals(setOf("a", "b"), resolver.currentSync()!!.timetableIds.toSet())
        coordinator.select(DisplayScope.Group("g"))
        clearSelectionRecords()
        repository.actionDissolveGroup("g")
        flush()
        val fallback = resolver.currentSync()!!
        assertTrue(fallback.scope is DisplayScope.Single)
        assertFalse(preferences.contains("display_scope_id"))
        assertTrue(db.timetableDao().getTimetablesSync().all { it.groupId == null })
        assertTrue(selections.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test fun groupSelectionBeforeRoomEmissionIsNotErasedByStaleSnapshot() {
        table("a"); table("b")
        val resolver = DisplayScopeResolver(app, db)
        val initialized = CountDownLatch(1)
        val grouped = CountDownLatch(1)
        val observer = Observer<ResolvedDisplayScope?> { value ->
            if (value != null) initialized.countDown()
            if (value?.scope == DisplayScope.Group("g") && value.timetableIds.toSet() == setOf("a", "b")) grouped.countDown()
        }
        instrumentation.runOnMainSync { resolver.resolved.observeForever(observer) }
        val writer = Executors.newSingleThreadExecutor()
        try {
            assertTrue(initialized.await(10, TimeUnit.SECONDS))
            // Keep the main thread busy so the transaction commits before its Room delivery.
            instrumentation.runOnMainSync {
                writer.submit {
                    db.runInTransaction {
                        group("g")
                        db.timetableGroupDao().setTimetableGroupSync("a", "g")
                        db.timetableGroupDao().setTimetableGroupSync("b", "g")
                    }
                }.get(10, TimeUnit.SECONDS)
                coordinator.select(DisplayScope.Group("g"))
            }
            assertTrue(grouped.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals("g", preferences.getString("display_scope_id", null))
        } finally {
            writer.shutdown()
            instrumentation.runOnMainSync { resolver.resolved.removeObserver(observer) }
        }
    }

    @Test fun timelineClearsWhenLastScopeIsDeleted() {
        val a = table("a")
        val now = System.currentTimeMillis()
        db.eventItemDao().insertEventSync(EventItem().apply {
            timetableId = "a"; name = "visible"
            from = Timestamp(now); to = Timestamp(now + 60000)
        })
        val resolver = DisplayScopeResolver(app, db)
        lateinit var viewModel: FragmentTimelineViewModel
        instrumentation.runOnMainSync {
            viewModel = FragmentTimelineViewModel(repository, resolver)
            viewModel.startRefresh()
        }
        assertEquals(1, awaitValue(viewModel.todayEventsLiveData) { it.size == 1 }.size)
        repository.actionDeleteTimetables(listOf(a))
        flush()
        assertTrue(awaitValue(viewModel.todayEventsLiveData) { it.isEmpty() }.isEmpty())
    }

    @Test fun widgetReminderAndAgentQueriesExcludeUnrelatedTablesAndHandleEmptyScope() {
        group("g")
        table("a", "g"); table("b", "g"); table("unrelated", start = 1000)
        val now = System.currentTimeMillis()
        val ids = DisplayScopeResolver(app, db).currentSync()!!.timetableIds
        for (tableId in listOf("a", "b", "unrelated")) {
            for (type in listOf(EventItem.TYPE.CLASS, EventItem.TYPE.EXAM)) {
                db.eventItemDao().insertEventSync(EventItem().apply {
                    timetableId = tableId; this.type = type; name = "match $tableId $type"
                    from = Timestamp(now + 60000); to = Timestamp(now + 120000)
                })
            }
        }
        fun assertScoped(events: List<EventItem>, count: Int) {
            assertEquals(count, events.size)
            assertEquals(setOf("a", "b"), events.map { it.timetableId }.toSet())
        }
        assertScoped(repository.getUpcomingEventsSync(now, now + 180000, ids), 4)
        assertScoped(repository.getEventsOfTimetablesSync(ids, now, now + 180000), 4)
        assertScoped(repository.searchEventsByKeywordSync("match", ids), 4)
        assertScoped(repository.getUpcomingExamsWithinReminderWindowSync(now, ids), 2)
        assertScoped(awaitValue(repository.getEventsDuring(now, now + 180000, ids)) { it.size == 4 }, 4)
        assertTrue(repository.getUpcomingEventsSync(now, now + 180000, emptyList()).isEmpty())
        assertTrue(repository.searchEventsByKeywordSync("match", emptyList()).isEmpty())
        assertTrue(repository.getEventsOfTimetablesSync(emptyList(), now, now + 180000).isEmpty())
        assertTrue(repository.getUpcomingExamsWithinReminderWindowSync(now, emptyList()).isEmpty())
        assertTrue(repository.getTodayEventsSync(emptyList()).isEmpty())
    }
}
