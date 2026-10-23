package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.sql.Timestamp
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TimetableManualSessionProtectionTest {
    private class IsolatedApplication(context: Context, private val directory: File) : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")

    private fun fixture(action: (Application, AppDatabase, Timetable, TimetableChangeStore, TimetableSourceApplier) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "manual-protection-${UUID.randomUUID()}").apply { mkdirs() }
        val app = IsolatedApplication(context, directory)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val table = Timetable().apply {
                id = "table"; name = "Test"; code = "BENBU:2026-2027-1"
                startTime = Timestamp(1788105600000L)
                endTime = Timestamp(startTime.time + 120 * 86400000L)
            }
            db.timetableDao().saveTimetableSync(table)
            db.subjectDao().saveSubjectSync(TermSubject().apply {
                id = "subject"; timetableId = table.id; name = "操作系统"; code = "CS101"
            })
            db.eventItemDao().insertEventSync(event(table, "source", EventItem.SOURCE_EAS_IMPORT))
            val changes = TimetableChangeStore(app)
            val applier = TimetableSourceApplier(app, EasPreferenceSource(app), changes, TimetableMutationLock(), db)
            action(app, db, table, changes, applier)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    private fun event(table: Timetable, id: String, source: String, type: EventItem.TYPE = EventItem.TYPE.CLASS): EventItem =
        EventItem().apply {
            this.id = id; timetableId = table.id; subjectId = "subject"
            name = "操作系统"; this.source = source; this.type = type; place = "A101"; teacher = "教师"
            from = Timestamp(table.startTime.time + 8 * 3600000L)
            to = Timestamp(from.time + 3600000L); fromNumber = 1; lastNumber = 1
        }

    private fun course(db: AppDatabase, table: Timetable): MergeCourse = MergeCourse(
        "subject", "操作系统", "CS101",
        db.eventItemDao().getImportedClassEventsOfTimetableSync(table.id).map { it.toMergeLesson(table.startTime.time) }
    )

    private fun decision(changes: TimetableChangeStore, table: Timetable, incoming: MergeCourse?) {
        changes.recordApplied(null, listOf(TimetableDecisionItem(
            term.id, table.id, "code:CS101", "subject", "操作系统",
            if (incoming == null) CourseVetoReason.MISSING_FROM_SOURCE else CourseVetoReason.LESSON_COUNT_REDUCED,
            1, 1L, 3, incoming
        )))
    }

    @Test fun pendingSourceUpdatePreservesManualExamAndOtherTimetableEvents() = fixture { _, db, table, changes, applier ->
        val manual = event(table, "manual", EventItem.SOURCE_MANUAL)
        val exam = event(table, "exam", EventItem.SOURCE_EAS_IMPORT, EventItem.TYPE.EXAM)
        val otherTable = Timetable().apply { id = "other-table" }
        db.timetableDao().saveTimetableSync(otherTable)
        val other = event(table, "cross-table", EventItem.SOURCE_EAS_IMPORT).apply { timetableId = otherTable.id }
        db.eventItemDao().saveEvents(listOf(manual, exam, other))
        val local = course(db, table)
        val incoming = local.copy(lessons = local.lessons.map { it.copy(place = "B202") })
        val updates = TimetableRefreshMergePolicy.plan(listOf(local), listOf(incoming), emptyMap(), 1L)
            .pendingUpdates.map { it.withTerm(term.id, table.id) }
        assertEquals(1, updates.size)
        changes.recordPendingUpdates(updates)
        applier.applyPendingConfirmations(updates.flatMap { it.rows }.map { it.fingerprint }.toSet(), emptyList())
        val imported = db.eventItemDao().getImportedClassEventsOfTimetableSync(table.id)
        assertEquals("B202", imported.single().place)
        assertEquals("A101", db.eventItemDao().getEventInIdsSync(listOf(manual.id)).single().place)
        assertEquals("A101", db.eventItemDao().getEventInIdsSync(listOf(exam.id)).single().place)
        assertEquals(other.id, db.eventItemDao().getEventsOfTimetableSync(otherTable.id).single().id)
    }

    @Test fun reducedSourceAdoptionPreservesManualSession() = fixture { _, db, table, changes, applier ->
        db.eventItemDao().insertEventSync(event(table, "manual", EventItem.SOURCE_MANUAL))
        val incoming = course(db, table).let { it.copy(lessons = it.lessons.map { lesson -> lesson.copy(place = "C303") }) }
        decision(changes, table, incoming)
        applier.adoptCourse(term.id, "code:CS101")
        assertEquals("C303", db.eventItemDao().getImportedClassEventsOfTimetableSync(table.id).single().place)
        assertEquals("manual", db.eventItemDao().getEventInIdsSync(listOf("manual")).single().id)
        assertEquals(1, db.subjectDao().getSubjectsSync(table.id).size)
    }

    @Test fun removingSourceCourseRetainsManualSessionAndSubject() = fixture { _, db, table, changes, applier ->
        db.eventItemDao().insertEventSync(event(table, "manual", EventItem.SOURCE_MANUAL))
        decision(changes, table, null)
        applier.adoptCourse(term.id, "code:CS101")
        assertEquals(listOf("manual"), db.eventItemDao().getEventsOfTimetableSync(table.id).map { it.id })
        assertEquals("subject", db.subjectDao().getSubjectsSync(table.id).single().id)
    }

    @Test fun removingSourceCourseDoesNotDeleteCrossTableReference() = fixture { _, db, table, changes, applier ->
        val otherTable = Timetable().apply { id = "other-table" }
        db.timetableDao().saveTimetableSync(otherTable)
        db.eventItemDao().insertEventSync(event(table, "cross-table", EventItem.SOURCE_EAS_IMPORT).apply {
            timetableId = otherTable.id
        })
        decision(changes, table, null)
        applier.adoptCourse(term.id, "code:CS101")
        assertTrue(db.eventItemDao().getEventsOfTimetableSync(table.id).isEmpty())
        assertEquals("cross-table", db.eventItemDao().getEventsOfTimetableSync(otherTable.id).single().id)
        assertEquals("subject", db.subjectDao().getSubjectsSync(table.id).single().id)
    }

    @Test fun removingSourceCourseCleansUpSubjectWhenNoEventsRemain() = fixture { _, db, table, changes, applier ->
        decision(changes, table, null)
        applier.adoptCourse(term.id, "code:CS101")
        assertTrue(db.eventItemDao().getEventsOfTimetableSync(table.id).isEmpty())
        assertTrue(db.subjectDao().getSubjectsSync(table.id).isEmpty())
    }

    @Test fun restoringSourceSnapshotRetainsSubjectUsedByManualSession() = fixture { app, db, table, _, _ ->
        val snapshots = TimetableSnapshotStore(app, db)
        val snapshot = requireNotNull(snapshots.capture("owner", term, table, TimetableSnapshotKind.IMPORTED))
        db.subjectDao().saveSubjectSync(TermSubject().apply {
            id = "added-subject"; timetableId = table.id; name = "补充课程"
        })
        db.eventItemDao().saveEvents(listOf(
            event(table, "later-source", EventItem.SOURCE_EAS_IMPORT).apply { subjectId = "added-subject" },
            event(table, "manual", EventItem.SOURCE_MANUAL).apply { subjectId = "added-subject" },
        ))
        snapshots.restore("owner", snapshot.id)
        assertEquals(setOf("source", "manual"), db.eventItemDao().getEventsOfTimetableSync(table.id).map { it.id }.toSet())
        assertEquals(setOf("subject", "added-subject"), db.subjectDao().getSubjectsSync(table.id).map { it.id }.toSet())
    }
}
