package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.*
import java.sql.Timestamp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeachingCourseOwnershipInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TimetableRepository
    private lateinit var versions: TimetableVersionStore
    private val start = 1_800_000_000_000L
    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repo = TimetableRepository(app, db, DisplayScopeSelector { error("no selection") })
        versions = TimetableVersionStore.forApplication(app, db)
        db.timetableDao().saveTimetableSync(Timetable().apply {
            id = "table"; code = "BENBU:term"; startTime = Timestamp(start); endTime = Timestamp(start + WEEK_MILLS)
        })
        db.subjectDao().saveSubjectSync(TermSubject().apply { id = "subject"; timetableId = "table"; name = "数学"; code = "MATH"; color = 9 })
        db.eventItemDao().addEvents(listOf(lesson()))
        versions.list("table")
    }
    @After fun close() { db.close() }
    private fun lesson() = EventItem().apply {
        id = "lesson"; subjectId = "subject"; timetableId = "table"; name = "数学"
        from = Timestamp(start + 3600000); to = Timestamp(start + 7200000); fromNumber = 1; lastNumber = 2
    }
    private fun source(event: EventItem) = versions.commit("SOURCE") { db.eventItemDao().deleteCourseFromTimetable("table"); db.eventItemDao().saveEvents(listOf(event)) }
    private fun current() = db.eventItemDao().versionClasses("table").single { it.isTeachingCourse() }

    @Test fun forgedOriginAndOrdinaryEditsCannotModifySourceMetadata() {
        assertThrows(IllegalArgumentException::class.java) { repo.writeSync(TimetableWriteOperation.Update(listOf(lesson().apply { place = "changed" }))) }
        assertThrows(IllegalArgumentException::class.java) { repo.writeSync(TimetableWriteOperation.Update(listOf(lesson().apply { source = EventItem.SOURCE_MANUAL; name = "forged" }))) }
        assertThrows(IllegalArgumentException::class.java) { repo.writeSync(TimetableWriteOperation.Add(
            ScheduleEventCreator.Result(listOf(lesson().apply { id = "fake" }), requireNotNull(db.timetableDao().getTimetableByIdSync("table"))), null)) }
        assertEquals("数学", current().name)
        assertEquals(1, versions.list("table").size)
    }

    @Test fun unrelatedPersonalEventCannotOverwriteTeachingSubjectThroughAddPayload() {
        repo.writeSync(TimetableWriteOperation.SetTeachingVisibility(listOf("lesson"), true))
        val personal = lesson().apply { id = "personal"; source = EventItem.SOURCE_MANUAL; subjectId = "" }
        val subject = db.subjectDao().getSubjectsSync("table").single().apply { name = "forged" }
        assertThrows(IllegalArgumentException::class.java) {
            repo.writeSync(TimetableWriteOperation.Add(ScheduleEventCreator.Result(listOf(personal),
                requireNotNull(db.timetableDao().getTimetableByIdSync("table"))), subject))
        }
        assertEquals("数学", db.subjectDao().getSubjectsSync("table").single().name)
        assertTrue(db.eventItemDao().getEventInIdsSync(listOf("personal")).isEmpty())
    }

    @Test fun tableCalibrationCannotRewriteTeachingTimes() {
        val table = requireNotNull(db.timetableDao().getTimetableByIdSync("table"))
        assertThrows(IllegalArgumentException::class.java) { repo.writeSync(TimetableWriteOperation.ChangeStartDate(table, start + WEEK_MILLS)) }
        assertThrows(IllegalArgumentException::class.java) { repo.writeSync(TimetableWriteOperation.ChangeStructure(table, TimePeriodInDay(TimeInDay(10, 0), TimeInDay(11, 0)), 0)) }
        assertEquals(start + 3600000, current().from.time)
    }

    @Test fun hidingIsPersonalAndRestorationPreservesPersonalEventsAndColors() {
        val first = versions.list("table").single()
        source(lesson().apply { place = "B202" })
        val revisions = versions.list("table")
        repo.writeSync(TimetableWriteOperation.SetTeachingVisibility(listOf("lesson"), true))
        db.eventItemDao().addEvents(listOf(lesson().apply { id = "personal"; source = EventItem.SOURCE_MANUAL; name = "聚餐" }))
        db.subjectDao().changeSubjectColorSync("subject", 42)
        assertEquals(revisions, versions.list("table"))
        val preview = versions.compare("table", first.id)
        repo.writeSync(TimetableWriteOperation.RestoreVersion("table", first.id, preview.currentRevision))
        assertEquals(1, current().hidden)
        assertEquals("聚餐", db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf("personal")).single().name)
        assertEquals(42, db.subjectDao().getSubjectsInIdsSync(listOf("subject")).single().color)
        assertEquals("", current().place)
    }

    @Test fun restoringTeachingMetadataKeepsLegacyPersonalCourseMetadataIndependent() {
        val first = versions.list("table").single()
        db.subjectDao().saveSubjectSync(db.subjectDao().getSubjectsInIdsSync(listOf("subject")).single().apply { name = "Current source"; color = 77 })
        db.eventItemDao().addEvents(listOf(lesson().apply { id = "legacy-personal"; source = EventItem.SOURCE_MANUAL; name = "My course" }))
        source(lesson().apply { place = "changed" })
        val personal = db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf("legacy-personal")).single()
        assertNotEquals("subject", personal.subjectId)
        repo.writeSync(TimetableWriteOperation.RestoreVersion("table", first.id, versions.list("table").first().revision))
        val metadata = db.subjectDao().getSubjectsInIdsSync(listOf(personal.subjectId)).single()
        assertEquals("Current source", metadata.name)
        assertEquals(77, metadata.color)
        assertEquals("数学", db.subjectDao().getSubjectsInIdsSync(listOf("subject")).single().name)
    }

    @Test fun personalCopyCanBeEditedWithoutCreatingATeachingVersion() {
        repo.writeSync(TimetableWriteOperation.CopyAsPersonal("lesson"))
        val copy = db.eventItemDao().getEventsOfTimetableSync("table").single { it.source == EventItem.SOURCE_MANUAL }
        assertEquals(EventItem.TYPE.OTHER, copy.type)
        assertEquals("", copy.subjectId)
        copy.place = "个人地点"
        repo.writeSync(TimetableWriteOperation.Update(listOf(copy)))
        assertEquals(1, versions.list("table").size)
        assertEquals("", current().place)
    }

    @Test fun visibilitySurvivesSourceMovesRemovalAndRestoreAndCanBeShownAgain() {
        val first = versions.list("table").single()
        repo.writeSync(TimetableWriteOperation.Delete(listOf("lesson")))
        source(lesson().apply { id = "moved"; from = Timestamp(from.time + 86400000); to = Timestamp(to.time + 86400000) })
        assertEquals(1, current().hidden)
        versions.commit("SOURCE") { db.eventItemDao().deleteCourseFromTimetable("table") }
        repo.writeSync(TimetableWriteOperation.RestoreVersion("table", first.id, versions.list("table").first().revision))
        assertEquals(1, current().hidden)
        repo.writeSync(TimetableWriteOperation.SetTeachingVisibility(listOf("lesson"), false))
        source(lesson().apply { id = "moved-again"; from = Timestamp(from.time + 86400000); to = Timestamp(to.time + 86400000) })
        assertEquals(0, current().hidden)
    }

    @Test fun subjectMetadataIsReadonlyButColorRemainsEditable() {
        val subjects = SubjectRepository(db, java.util.concurrent.Executor { it.run() })
        val proposed = db.subjectDao().getSubjectsInIdsSync(listOf("subject")).single().apply { name = "forged"; credit = 99f; code = "bad"; color = 123 }
        subjects.saveSubjectSync(proposed)
        subjects.actionSaveSubjectInfo("subject", SubjectInfoUpdate.Credit(99f))
        val actual = db.subjectDao().getSubjectsInIdsSync(listOf("subject")).single()
        assertEquals("数学", actual.name)
        assertEquals("MATH", actual.code)
        assertEquals(0f, actual.credit, 0f)
        assertEquals(123, actual.color)
    }

    @Test fun oldMixedVersionsRestoreOnlySourceCourses() {
        val old = requireNotNull(versions.content("table"))
        db.timetableVersionDao().clearVersions()
        val personal = lesson().apply { id = "old-personal"; source = EventItem.SOURCE_MANUAL }
        val content = old.copy(format = 1, events = old.events + personal)
        db.timetableVersionDao().insert(TimetableVersionEntity("legacy", "${FollowedTeachingSectionStore.ownerKey(cn.limpu.hita.data.source.preference.EasPreferenceSource(ApplicationProvider.getApplicationContext()).getEasToken())}", "table", 1, null, start, "MANUAL", null, "old-hash", EventEditHistoryStore.eventCodec().toJson(content)))
        source(lesson().apply { place = "new" })
        repo.writeSync(TimetableWriteOperation.RestoreVersion("table", "legacy", versions.list("table").first().revision))
        assertTrue(db.eventItemDao().getEventsInIdsIncludingHiddenSync(listOf("old-personal")).isEmpty())
        assertEquals("", current().place)
    }

    @Test fun aPersonalOnlyTimetableHasNoTeachingHistory() {
        db.timetableDao().saveTimetableSync(Timetable().apply { id = "personal-table"; code = null })
        db.eventItemDao().addEvents(listOf(lesson().apply { id = "manual"; timetableId = "personal-table"; source = EventItem.SOURCE_MANUAL }))
        assertNull(versions.content("personal-table"))
        assertTrue(db.timetableVersionDao().summaries("local", "personal-table").isEmpty())
    }
}
