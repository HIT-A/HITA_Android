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
        val entry = delete(EventItem.SOURCE_MANUAL)
        assertTrue(repository.writeSync(restore(entry.id)))
        assertEquals(0, current().hidden)
        assertEquals(DeletedEventStatus.RESTORED, repository.deletedEventHistorySync(TABLE).single().status)
        assertTrue(runCatching { repository.writeSync(restore(entry.id)) }.isFailure)
        assertEquals(2, db.eventEditHistoryDao().countSync())
    }

    @Test
    fun teachingSourceDeletionUnhidesOriginalRow() {
        val entry = delete(EventItem.SOURCE_EAS_IMPORT)
        assertEquals(1, current().hidden)
        assertEquals(DeletedEventStatus.AVAILABLE, entry.status)
        repository.writeSync(restore(entry.id))
        assertEquals(0, current().hidden)
        assertEquals("测试课程", current().name)
    }

    @Test
    fun historyInsertFailureRollsBackEventAndRestoredMarker() {
        val entry = delete(EventItem.SOURCE_MANUAL)
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
        val entry = delete(EventItem.SOURCE_MANUAL)
        db.timetableDao().deleteTimetablesInIdsSync(listOf(TABLE))
        assertTrue(runCatching { repository.writeSync(restore(entry.id)) }.isFailure)
        assertNull(db.timetableDao().getTimetableByIdSync(TABLE))
        assertTrue(db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf(EVENT)).isEmpty())
    }

    @Test
    fun anotherTimetableCannotRestoreThisDeletion() {
        val entry = delete(EventItem.SOURCE_MANUAL)
        db.timetableDao().saveTimetablesSync(listOf(Timetable().apply { id = "other" }))
        assertTrue(runCatching {
            repository.writeSync(TimetableWriteOperation.RestoreDeleted("other", entry.id))
        }.isFailure)
        assertTrue(db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf(EVENT)).isEmpty())
        assertEquals(1, db.eventEditHistoryDao().countSync())
    }

    @Test
    fun modifiedHiddenCourseCannotBeOverwritten() {
        val entry = delete(EventItem.SOURCE_EAS_IMPORT)
        db.eventItemDao().updateEventSync(current().apply { place = "新教室" })
        assertTrue(runCatching { repository.writeSync(restore(entry.id)) }.isFailure)
        assertEquals("新教室", current().place)
        assertEquals(1, current().hidden)
        assertEquals(DeletedEventStatus.CHANGED, repository.deletedEventHistorySync(TABLE).single().status)
    }

    private fun current() = db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf(EVENT)).single()
    private fun restore(id: String) = TimetableWriteOperation.RestoreDeleted(TABLE, id)
    private fun delete(source: String): DeletedEventHistory {
        db.eventItemDao().addEvents(listOf(EventItem().apply {
            id = EVENT
            timetableId = TABLE
            name = "测试课程"
            this.source = source
            from = Timestamp(1_800_000_000_000L)
            to = Timestamp(1_800_003_600_000L)
        }))
        repository.writeSync(TimetableWriteOperation.Delete(listOf(EVENT)))
        return repository.deletedEventHistorySync(TABLE).single()
    }

    companion object {
        private const val TABLE = "history-test-table"
        private const val EVENT = "history-test-event"
    }
}
