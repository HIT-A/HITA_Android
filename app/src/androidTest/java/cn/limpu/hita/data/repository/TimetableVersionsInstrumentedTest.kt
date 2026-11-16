package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.*
import cn.limpu.hita.feature.timetable.version.VersionChangeKind
import java.sql.Timestamp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableVersionsInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository
    private lateinit var versions: TimetableVersionStore
    private val start = 1_800_000_000_000L

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = TimetableRepository(app, db, DisplayScopeSelector { error("No scope selection") })
        versions = TimetableVersionStore(db)
        db.timetableDao().saveTimetableSync(Timetable().apply {
            id = "table"; name = "版本测试"; startTime = Timestamp(start); endTime = Timestamp(start + 7 * WEEK_MILLS)
        })
        db.subjectDao().saveSubjectSync(TermSubject().apply { id = "subject"; timetableId = "table"; name = "数学"; code = "MATH" })
        db.eventItemDao().addEvents(listOf(event()))
    }
    @After fun tearDown() { db.close() }

    @Test fun successfulEditsRetainFiveVersionsIncludingCurrentAndNoOpDoesNotCommit() {
        assertEquals(1, versions.list("table").size)
        repeat(7) { i -> repository.writeSync(TimetableWriteOperation.Update(listOf(event().apply { place = "教室$i" }))) }
        val entries = versions.list("table")
        assertEquals(listOf(8L, 7L, 6L, 5L, 4L), entries.map { it.revision })
        repository.writeSync(TimetableWriteOperation.Update(listOf(current())))
        assertEquals(entries, versions.list("table"))
    }

    @Test fun deletingLastClassHasAnEmptyCurrentVersionAndCanRestoreAsOneNewCommit() {
        val first = versions.list("table").single()
        repository.writeSync(TimetableWriteOperation.Delete(listOf("lesson")))
        val comparison = versions.compare("table", first.id)
        assertEquals(1, comparison.diff.count(VersionChangeKind.REMOVED))
        assertEquals(1, comparison.restoreDiff.count(VersionChangeKind.ADDED))
        assertTrue(repository.writeSync(TimetableWriteOperation.RestoreVersion("table", first.id, comparison.currentRevision)))
        val entries = versions.list("table")
        assertEquals(listOf(3L, 2L, 1L), entries.map { it.revision })
        assertEquals("RESTORE", entries.first().reason)
        assertEquals(first.id, entries.first().restoredFrom)
        assertEquals(0, current().hidden)
    }

    @Test fun stalePreviewCannotOverwriteANewerEdit() {
        val first = versions.list("table").single()
        repository.writeSync(TimetableWriteOperation.Delete(listOf("lesson")))
        val preview = versions.compare("table", first.id)
        repository.writeSync(TimetableWriteOperation.Update(listOf(event().apply { hidden = 1; place = "新的内容" })))
        assertThrows(IllegalStateException::class.java) {
            repository.writeSync(TimetableWriteOperation.RestoreVersion("table", first.id, preview.currentRevision))
        }
        assertEquals("新的内容", current().place)
        assertEquals(1, current().hidden)
    }

    @Test fun failedVersionInsertRollsBackCourseWrite() {
        versions.list("table")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_version BEFORE INSERT ON timetable_version BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertTrue(runCatching { repository.writeSync(TimetableWriteOperation.Delete(listOf("lesson"))) }.isFailure)
        assertEquals(0, current().hidden)
        assertEquals(1, versions.list("table").size)
        assertEquals(0, db.eventEditHistoryDao().countSync())
    }

    @Test fun restorationPreservesExamAndCurrentLocalDisplayManagement() {
        val first = versions.list("table").single()
        val exam = event().apply { id = "exam"; type = EventItem.TYPE.EXAM; name = "独立考试" }
        db.eventItemDao().addEvents(listOf(exam))
        repository.writeSync(TimetableWriteOperation.Update(listOf(event().apply { place = "新教室" })))
        val table = requireNotNull(db.timetableDao().getTimetableByIdSync("table"))
        table.name = "用户改名"
        db.timetableDao().saveTimetableSync(table)
        val subject = db.subjectDao().getSubjectsSync("table").single().apply { color = 123 }
        db.subjectDao().saveSubjectSync(subject)
        val preview = versions.compare("table", first.id)
        repository.writeSync(TimetableWriteOperation.RestoreVersion("table", first.id, preview.currentRevision))
        assertEquals("用户改名", db.timetableDao().getTimetableByIdSync("table")?.name)
        assertEquals(123, db.subjectDao().getSubjectsSync("table").single().color)
        assertEquals("独立考试", db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf("exam")).single().name)
    }

    @Test fun accountScopedVersionsCannotReadAnotherOwnersPayload() {
        val table = requireNotNull(db.timetableDao().getTimetableByIdSync("table")).apply { code = "SHENZHEN:2026-2027-1" }
        db.timetableDao().saveTimetableSync(table)
        val a = TimetableVersionStore(db, "a").list("table").single()
        val b = TimetableVersionStore(db, "b")
        assertEquals(1, b.list("table").size)
        assertTrue(runCatching { b.compare("table", a.id) }.isFailure)
    }

    @Test fun sourceBaselineDoesNotGetTrimmedWithVersions() {
        db.timetableVersionDao().saveSource(TimetableSourceBaselineEntity("owner", "table", "baseline"))
        versions.list("table")
        repeat(8) { i -> repository.writeSync(TimetableWriteOperation.Update(listOf(event().apply { name = "课$i" }))) }
        assertEquals(5, versions.list("table").size)
        assertEquals("baseline", db.timetableVersionDao().source("owner", "table")?.payload)
    }

    @Test fun colorAndNameChangesDoNotCreateCourseVersions() {
        val first = versions.list("table")
        db.subjectDao().saveSubjectSync(db.subjectDao().getSubjectsSync("table").single().apply { color = 42; code = "ENRICHED" })
        db.timetableDao().saveTimetableSync(requireNotNull(db.timetableDao().getTimetableByIdSync("table")).apply { name = "新课表名" })
        assertEquals(first, versions.list("table"))
    }

    private fun current() = db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf("lesson")).single()
    private fun event() = EventItem().apply {
        id = "lesson"; timetableId = "table"; subjectId = "subject"; name = "数学"; source = EventItem.SOURCE_EAS_IMPORT
        from = Timestamp(start + 3600000); to = Timestamp(start + 7200000); createdAt = Timestamp(start)
    }
}
