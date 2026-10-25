package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp

/** 统一写入口保留原有更新/删除语义，且不得复活已经删除的课表。 */
@RunWith(AndroidJUnit4::class)
class TimetableWriteStoreInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository

    private val start = 1_788_134_400_000L
    private val day = 86_400_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = TimetableRepository(app, db, DisplayScopeSelector {
            error("Ordinary writes must not select a display scope")
        })
        db.timetableDao().saveTimetablesSync(listOf(timetable("table"), timetable("other-table")))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun updateChangesExistingRowsWithoutInsertingMissingEvents() {
        db.eventItemDao().addEvents(listOf(event("edited"), event("untouched")))
        val edited = event("edited").apply { name = "新名称"; place = "新教室" }
        val missing = event("missing")

        assertTrue(repository.writeSync(TimetableWriteOperation.Update(listOf(edited, missing))))

        val saved = db.eventItemDao().getEventsOfTimetableSync("table").associateBy { it.id }
        assertEquals(setOf("edited", "untouched"), saved.keys)
        assertEquals("新名称", saved.getValue("edited").name)
        assertEquals("新教室", saved.getValue("edited").place)
        assertEquals("untouched", saved.getValue("untouched").name)
    }

    @Test
    fun deleteRemovesOnlyRequestedIdsAcrossTimetables() {
        db.eventItemDao().addEvents(listOf(
            event("remove-here"),
            event("keep-here"),
            event("remove-there", timetableId = "other-table"),
            event("keep-there", timetableId = "other-table"),
        ))

        assertTrue(repository.writeSync(TimetableWriteOperation.Delete(
            listOf("remove-here", "remove-there", "already-missing")
        )))

        assertEquals(listOf("keep-here"), eventIds("table"))
        assertEquals(listOf("keep-there"), eventIds("other-table"))
        assertEquals(setOf("table", "other-table"), db.timetableDao().getTimetablesSync().map { it.id }.toSet())
    }

    @Test
    fun deleteSubjectsAlsoRemovesLegacyCrossTimetableSessionsButKeepsOtherSubjects() {
        val removed = subject("removed-subject")
        val kept = subject("kept-subject")
        val keptElsewhere = subject("other-subject", "other-table")
        db.subjectDao().saveSubjectsSync(listOf(removed, kept, keptElsewhere))
        db.eventItemDao().addEvents(listOf(
            event("removed-session", subjectId = removed.id),
            event("legacy-cross-table", "other-table", removed.id),
            event("kept-session", subjectId = kept.id),
            event("kept-elsewhere", "other-table", keptElsewhere.id),
            event("unrelated-activity").apply { type = EventItem.TYPE.OTHER },
        ))

        assertTrue(repository.writeSync(TimetableWriteOperation.DeleteSubjects(listOf(removed))))

        assertEquals(setOf(kept.id, keptElsewhere.id), db.subjectDao().getAllSubjectsSync().map { it.id }.toSet())
        assertEquals(listOf("kept-session", "unrelated-activity"), eventIds("table"))
        assertEquals(listOf("kept-elsewhere"), eventIds("other-table"))
        assertTrue(db.eventItemDao().getClassesOfSubjectSync(removed.id).isEmpty())
    }

    @Test
    fun addAfterTargetDeletionFailsWithoutLeavingRowsOrRecreatingTimetable() {
        val stale = requireNotNull(db.timetableDao().getTimetableByIdSync("table"))
        val subject = subject("new-subject")
        val result = ScheduleEventCreator.Result(
            listOf(event("new-session", subjectId = subject.id).apply { to = Timestamp(start + 30 * day) }),
            stale,
        )
        db.timetableDao().deleteTimetablesInIdsSync(listOf(stale.id))

        val failure = runCatching {
            repository.writeSync(TimetableWriteOperation.Add(result, subject))
        }.exceptionOrNull()

        assertTrue("目标课表消失应作为写失败抛出", failure is IllegalStateException)
        assertNull(db.timetableDao().getTimetableByIdSync(stale.id))
        assertEquals(listOf("other-table"), db.timetableDao().getTimetablesSync().map { it.id })
        assertTrue(db.subjectDao().getAllSubjectsSync().isEmpty())
        assertTrue(eventIds("table").isEmpty())
        assertEquals(start + 14 * day, stale.endTime.time)
    }

    @Test
    fun missingScheduleTargetReturnsFalseWithoutRecreatingItOrChangingOtherEvents() {
        val stale = requireNotNull(db.timetableDao().getTimetableByIdSync("table"))
        db.eventItemDao().addEvents(listOf(event("other-session", "other-table")))
        db.timetableDao().deleteTimetablesInIdsSync(listOf(stale.id))

        assertFalse(repository.writeSync(TimetableWriteOperation.ChangeStartDate(stale, start + 7 * day)))
        assertFalse(repository.writeSync(TimetableWriteOperation.ChangeStructure(
            stale, TimePeriodInDay(TimeInDay(7, 50), TimeInDay(8, 35)), 0,
        )))

        assertNull(db.timetableDao().getTimetableByIdSync(stale.id))
        val other = db.eventItemDao().getEventsOfTimetableSync("other-table").single()
        assertEquals(start + 8 * 3_600_000L, other.from.time)
        assertEquals(start + 10 * 3_600_000L, other.to.time)
        assertEquals(start, stale.startTime.time)
        assertEquals(start, requireNotNull(db.timetableDao().getTimetableByIdSync("other-table")).startTime.time)
    }

    @Test
    fun subjectEventDeleteFailurePreservesExistingNonTransactionalBehavior() {
        val removed = subject("removed-subject")
        db.subjectDao().saveSubjectSync(removed)
        db.eventItemDao().addEvents(listOf(event("session", subjectId = removed.id)))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_event_delete BEFORE DELETE ON events " +
                "BEGIN SELECT RAISE(ABORT, 'injected delete failure'); END"
        )

        val failure = runCatching {
            repository.writeSync(TimetableWriteOperation.DeleteSubjects(listOf(removed)))
        }.exceptionOrNull()

        assertTrue("删除课次失败应传递给写入口", failure?.message.orEmpty().contains("injected delete failure"))
        // 此次只统一入口；原来先删课程再删课次的两条语句不增加事务。
        assertTrue(db.subjectDao().getAllSubjectsSync().isEmpty())
        assertEquals(listOf("session"), eventIds("table"))
    }

    private fun timetable(id: String) = Timetable().apply {
        this.id = id
        name = id
        startTime = Timestamp(start)
        endTime = Timestamp(start + 14 * day)
    }

    private fun subject(id: String, timetableId: String = "table") = TermSubject().apply {
        this.id = id
        this.timetableId = timetableId
        name = "相同课程名"
    }

    private fun event(id: String, timetableId: String = "table", subjectId: String = "") = EventItem().apply {
        this.id = id
        this.timetableId = timetableId
        this.subjectId = subjectId
        name = id
        type = EventItem.TYPE.CLASS
        source = EventItem.SOURCE_MANUAL
        from = Timestamp(start + 8 * 3_600_000L)
        to = Timestamp(start + 10 * 3_600_000L)
        fromNumber = 1
        lastNumber = 2
    }

    private fun eventIds(timetableId: String): List<String> =
        db.eventItemDao().getEventIdsOfTimetableSync(timetableId).sorted()
}
