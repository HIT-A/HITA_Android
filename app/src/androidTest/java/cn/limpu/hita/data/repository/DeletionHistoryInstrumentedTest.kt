package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import java.sql.Timestamp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated in-memory database; never reads or edits the user's timetables. */
@RunWith(AndroidJUnit4::class)
class DeletionHistoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = TimetableRepository(app, db, DisplayScopeSelector { error("Unexpected selection") })
        db.timetableDao().saveTimetablesSync(listOf(Timetable().apply {
            id = "history-test-table"
            name = "恢复测试"
            startTime = Timestamp(1_800_000_000_000L)
            endTime = Timestamp(1_801_000_000_000L)
        }))
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun manualDeletionRestoresOnceAndKeepsConsumedHistory() {
        val entry = deleteManualCourse()
        assertTrue(repository.writeSync(restore(entry.id)))
        assertEquals(0, current().hidden)
        assertEquals(DeletedEventStatus.RESTORED, repository.deletedEventHistorySync(TABLE).single().status)
        assertTrue(runCatching { repository.writeSync(restore(entry.id)) }.isFailure)
        assertEquals(2, db.eventEditHistoryDao().countSync())
    }

    @Test
    fun teachingCourseHideAndShowDoNotRecordDeletionHistory() {
        val course = insertCourse(EventItem.SOURCE_EAS_IMPORT)
        assertTrue(repository.writeSync(TimetableWriteOperation.Delete(listOf(EVENT))))
        val hidden = db.teachingVisibilityDao().all(TABLE).single()
        assertEquals(1, hidden.hidden)
        assertEquals(1, current().hidden)
        assertEquals(course, current())
        assertEquals(0, db.eventEditHistoryDao().countSync())

        assertTrue(repository.writeSync(TimetableWriteOperation.SetTeachingVisibility(listOf(EVENT), false)))
        assertEquals(hidden.copy(hidden = 0), db.teachingVisibilityDao().all(TABLE).single())
        assertEquals(0, current().hidden)
        assertEquals(course, current())
        assertEquals(0, db.eventEditHistoryDao().countSync())
    }

    @Test
    fun historyInsertFailureRollsBackEventAndRestoredMarker() {
        val entry = deleteManualCourse()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_restore_history BEFORE INSERT ON event_edit_history WHEN NEW.action = 'RESTORE' " +
                "BEGIN SELECT RAISE(ABORT, 'injected marker failure'); END"
        )
        assertTrue(runCatching { repository.writeSync(restore(entry.id)) }.isFailure)
        assertTrue(db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf(EVENT)).isEmpty())
        assertEquals(1, db.eventEditHistoryDao().countSync())
        assertEquals(DeletedEventStatus.AVAILABLE, repository.deletedEventHistorySync(TABLE).single().status)
    }

    @Test
    fun deletedTimetableCannotBeResurrectedByHistory() {
        val entry = deleteManualCourse()
        db.timetableDao().deleteTimetablesInIdsSync(listOf(TABLE))
        assertTrue(runCatching { repository.writeSync(restore(entry.id)) }.isFailure)
        assertNull(db.timetableDao().getTimetableByIdSync(TABLE))
        assertTrue(db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf(EVENT)).isEmpty())
    }

    @Test
    fun anotherTimetableCannotRestoreThisDeletion() {
        val entry = deleteManualCourse()
        db.timetableDao().saveTimetablesSync(listOf(Timetable().apply { id = "other" }))
        assertTrue(runCatching {
            repository.writeSync(TimetableWriteOperation.RestoreDeleted("other", entry.id))
        }.isFailure)
        assertTrue(db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf(EVENT)).isEmpty())
        assertEquals(1, db.eventEditHistoryDao().countSync())
    }

    @Test
    fun changedTeachingCourseCannotBeOverwrittenByLegacyDeletion() {
        insertCourse(EventItem.SOURCE_EAS_IMPORT)
        val history = db.eventEditHistoryDao()
        // Seed the old soft-delete record directly; the current repository only sets visibility.
        assertTrue(EventEditHistoryStore(db.eventItemDao(), history).delete(listOf(EVENT)))
        val legacyEntries = history.historyOfTimetableSync(TABLE)
        val entry = repository.deletedEventHistorySync(TABLE).single()
        assertEquals(DeletedEventStatus.AVAILABLE, entry.status)

        db.eventItemDao().updateEventSync(current().apply { place = "新教室" })
        assertEquals(DeletedEventStatus.CHANGED, repository.deletedEventHistorySync(TABLE).single().status)
        assertThrows(IllegalStateException::class.java) { repository.writeSync(restore(entry.id)) }
        assertEquals("新教室", current().place)
        assertEquals(1, current().hidden)
        assertEquals(legacyEntries, history.historyOfTimetableSync(TABLE))

        assertTrue(repository.writeSync(TimetableWriteOperation.SetTeachingVisibility(listOf(EVENT), false)))
        assertEquals("新教室", current().place)
        assertEquals(0, current().hidden)
        assertEquals(legacyEntries, history.historyOfTimetableSync(TABLE))
    }

    private fun current() = db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf(EVENT)).single()
    private fun restore(id: String) = TimetableWriteOperation.RestoreDeleted(TABLE, id)
    private fun deleteManualCourse(): DeletedEventHistory {
        insertCourse(EventItem.SOURCE_MANUAL)
        assertTrue(repository.writeSync(TimetableWriteOperation.Delete(listOf(EVENT))))
        return repository.deletedEventHistorySync(TABLE).single()
    }

    private fun insertCourse(source: String): EventItem = EventItem().apply {
        id = EVENT
        type = EventItem.TYPE.CLASS
        timetableId = TABLE
        name = "测试课程"
        this.source = source
        from = Timestamp(1_800_000_000_000L)
        to = Timestamp(1_800_003_600_000L)
    }.also { db.eventItemDao().addEvents(listOf(it)) }

    companion object {
        private const val TABLE = "history-test-table"
        private const val EVENT = "history-test-event"
    }
}
