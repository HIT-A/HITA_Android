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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.sql.Timestamp
import java.util.UUID

/** 采纳待确认变更：写库失败时待确认项与“记住忽略”必须保留，多课表同事务提交。 */
@RunWith(AndroidJUnit4::class)
class TimetablePendingConfirmationRecoveryTest {
    private class IsolatedApplication(context: Context, private val directory: File) : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
    private val start = 1788105600000L
    private lateinit var directory: File
    private lateinit var db: AppDatabase
    private lateinit var changes: TimetableChangeStore
    private lateinit var applier: TimetableSourceApplier

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "pending-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val app = IsolatedApplication(context, directory)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        changes = TimetableChangeStore(app)
        applier = TimetableSourceApplier(app, EasPreferenceSource(app), changes, TimetableMutationLock(), db)
    }

    @After fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    private fun table(id: String): Timetable {
        val table = Timetable().apply {
            this.id = id; name = id; code = "BENBU:2026-2027-1"
            startTime = Timestamp(start)
            endTime = Timestamp(start + 120 * 86400000L)
        }
        db.timetableDao().saveTimetableSync(table)
        db.subjectDao().saveSubjectSync(TermSubject().apply {
            this.id = "subject-$id"; timetableId = id; name = "操作系统"; code = "CS101"
        })
        db.eventItemDao().insertEventSync(EventItem().apply {
            this.id = "source-$id"; timetableId = id; subjectId = "subject-$id"
            name = "操作系统"; source = EventItem.SOURCE_EAS_IMPORT; type = EventItem.TYPE.CLASS
            place = "A101"; teacher = "教师"
            from = Timestamp(start + 8 * 3600000L)
            to = Timestamp(from.time + 3600000L); fromNumber = 1; lastNumber = 1
        })
        return table
    }

    /** 每张课表的唯一课程改地点为 [place]，返回已带学期与课表归属的待确认项。 */
    private fun placeChange(table: Timetable, place: String): List<PendingCourseUpdate> {
        val local = MergeCourse("subject-${table.id}", "操作系统", "CS101",
            db.eventItemDao().getImportedClassEventsOfTimetableSync(table.id).map { it.toMergeLesson(start) })
        val incoming = local.copy(lessons = local.lessons.map { it.copy(place = place) })
        return TimetableRefreshMergePolicy.plan(listOf(local), listOf(incoming), emptyMap(), 1L)
            .pendingUpdates.map { it.withTerm(term.id, table.id) }
    }

    private fun failInsertsInto(timetableId: String? = null) {
        val condition = timetableId?.let { " WHEN NEW.timetableId = '$it'" }.orEmpty()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_event_insert BEFORE INSERT ON events$condition " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
        )
    }

    private fun places(timetableId: String): List<String> =
        db.eventItemDao().getImportedClassEventsOfTimetableSync(timetableId).map { it.place.orEmpty() }

    private fun fingerprints(updates: List<PendingCourseUpdate>) =
        updates.flatMap { it.rows }.map { it.fingerprint }.toSet()

    @Test fun failureKeepsPendingUpdatesAndIgnoredChoices() {
        val updates = placeChange(table("table"), "B202")
        changes.recordPendingUpdates(updates)
        val revision = changes.currentState().pendingRevision
        failInsertsInto()

        val error = runCatching {
            applier.applyPendingConfirmations(fingerprints(updates), listOf("ignored-fingerprint"))
        }.exceptionOrNull()

        assertNotNull("注入的写入失败应抛给调用方", error)
        assertEquals(updates, changes.currentState().pendingUpdates)
        assertEquals(revision, changes.currentState().pendingRevision)
        assertTrue("失败时不应记住忽略", changes.ignoredFingerprints().isEmpty())
        assertEquals(listOf("A101"), places("table"))
    }

    @Test fun failureInLaterTimetableRollsBackEarlierTimetable() {
        val updates = placeChange(table("table-a"), "B202") + placeChange(table("table-b"), "C303")
        changes.recordPendingUpdates(updates)
        failInsertsInto("table-b")

        assertNotNull(runCatching { applier.applyPendingConfirmations(fingerprints(updates), emptyList()) }.exceptionOrNull())

        assertEquals("同一次确认的所有课表应一起回滚", listOf("A101"), places("table-a"))
        assertEquals(listOf("A101"), places("table-b"))
        assertEquals(updates, changes.currentState().pendingUpdates)
    }

    @Test fun successAppliesRowsThenClearsPendingAndRemembersIgnored() {
        val updates = placeChange(table("table-a"), "B202") + placeChange(table("table-b"), "C303")
        changes.recordPendingUpdates(updates)

        applier.applyPendingConfirmations(fingerprints(updates), listOf("ignored-fingerprint"))

        assertEquals(listOf("B202"), places("table-a"))
        assertEquals(listOf("C303"), places("table-b"))
        assertTrue(changes.currentState().pendingUpdates.isEmpty())
        assertEquals(setOf("ignored-fingerprint"), changes.ignoredFingerprints())
    }

    @Test fun reapplyingSameUpdatesAfterInterruptedClearIsIdempotent() {
        val table = table("table")
        val updates = placeChange(table, "B202")
        changes.recordPendingUpdates(updates)
        applier.applyPendingConfirmations(fingerprints(updates), emptyList())
        val first = db.eventItemDao().getImportedClassEventsOfTimetableSync(table.id)
            .map { listOf(it.subjectId, it.place, it.from.time, it.to.time) }

        // 模拟提交后、清除待确认前进程被杀：同一批变更再次被确认
        changes.recordPendingUpdates(updates)
        applier.applyPendingConfirmations(fingerprints(updates), emptyList())

        val second = db.eventItemDao().getImportedClassEventsOfTimetableSync(table.id)
            .map { listOf(it.subjectId, it.place, it.from.time, it.to.time) }
        assertEquals(first, second)
        assertEquals(1, db.subjectDao().getSubjectsSync(table.id).size)
    }

    @Test fun clearingWithStaleRevisionKeepsNewerPendingUpdates() {
        val older = placeChange(table("table"), "B202")
        changes.recordPendingUpdates(older)
        val staleRevision = changes.currentState().pendingRevision
        val newer = placeChange(table("table-b"), "C303")
        changes.recordPendingUpdates(newer)

        changes.clearPendingUpdates(staleRevision)

        assertEquals(newer, changes.currentState().pendingUpdates)
    }

    @Test fun consecutiveRecordsGetDistinctRevisions() {
        val updates = placeChange(table("table"), "B202")
        val revisions = (1..5).map {
            changes.recordPendingUpdates(updates)
            changes.currentState().pendingRevision
        }
        assertEquals(revisions.size, revisions.toSet().size)
    }
}
