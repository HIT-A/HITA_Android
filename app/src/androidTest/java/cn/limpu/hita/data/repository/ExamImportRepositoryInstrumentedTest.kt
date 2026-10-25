package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.util.Calendar

/** 三个考试入口共用持久化规则，同时保留手动/自动的去重范围及失败边界。 */
@RunWith(AndroidJUnit4::class)
class ExamImportRepositoryInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repository: ExamImportRepository

    private val timetableStart = at(2026, Calendar.JANUARY, 1)
    private val timetableEnd = at(2026, Calendar.JANUARY, 7, 23, 59)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = ExamImportRepository(db)
        db.timetableDao().saveTimetablesSync(listOf(timetable("target"), timetable("other")))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun manualImportDeduplicatesExistingTargetExamsAndRepeatedItemsInTheBatch() {
        db.eventItemDao().insertEventSync(storedExam("existing"))

        val result = repository.importIntoTimetable(
            listOf(exam(), exam(), exam("线性代数"), exam("线性代数")), "target"
        )

        assertCounts(result, total = 4, imported = 1, duplicates = 3, invalid = 0)
        assertEquals(setOf("[考试] 高等数学", "[考试] 线性代数"), events("target").map { it.name }.toSet())
        assertTrue(events("target").any { it.id == "existing" })
    }

    @Test
    fun manualImportAllowsAnExamInAnotherTimetableWhileAutomaticImportSkipsItGlobally() {
        db.eventItemDao().insertEventSync(storedExam("elsewhere", "other"))

        val automatic = repository.importAutomatically(listOf(exam()), storedTimetable("target"))
        assertCounts(automatic, total = 1, imported = 0, duplicates = 1, invalid = 0)
        assertTrue(events("target").isEmpty())

        val manual = repository.importIntoTimetable(listOf(exam()), "target")
        assertCounts(manual, total = 1, imported = 1, duplicates = 0, invalid = 0)
        assertEquals(1, events("target").size)
        assertEquals(listOf("elsewhere"), events("other").map { it.id })
    }

    @Test
    fun automaticImportDeduplicatesRepeatedItemsWithinTheNewBatch() {
        val result = repository.importAutomatically(
            listOf(exam(), exam(), exam("线性代数"), exam("线性代数"), exam("空日期").apply { examDate = "" }),
            storedTimetable("target"),
        )

        assertCounts(result, total = 5, imported = 2, duplicates = 2, invalid = 1)
        assertEquals(setOf("[考试] 高等数学", "[考试] 线性代数"), events("target").map { it.name }.toSet())
    }

    @Test
    fun manualImportCountsInvalidAndDuplicateItemsSeparately() {
        db.eventItemDao().insertEventSync(storedExam("existing"))

        val result = repository.importIntoTimetable(listOf(
            exam(),
            exam("空日期").apply { examDate = "" },
            exam("坏时间").apply { examTime = "08:30" },
            exam("线性代数"),
        ), "target")

        assertCounts(result, total = 4, imported = 1, duplicates = 1, invalid = 2)
        assertEquals(2, events("target").size)
    }

    @Test
    fun singleExamResultsDistinguishSuccessDuplicateAndInvalidTime() {
        val imported = repository.importIntoTimetable(listOf(exam()), "target")
        val duplicate = repository.importIntoTimetable(listOf(exam()), "target")
        val invalid = repository.importIntoTimetable(
            listOf(exam().apply { examTime = null }), "target"
        )

        assertCounts(imported, total = 1, imported = 1, duplicates = 0, invalid = 0)
        assertCounts(duplicate, total = 1, imported = 0, duplicates = 1, invalid = 0)
        assertCounts(invalid, total = 1, imported = 0, duplicates = 0, invalid = 1)
        assertEquals(1, events("target").size)
    }

    @Test
    fun legacyNamesAndWhitespaceMatchButDifferentRoomDateOrEndTimeRemainDistinct() {
        db.eventItemDao().insertEventSync(storedExam("legacy").apply {
            name = "  高等数学  "
            place = "  正心楼   101  "
            source = EventItem.SOURCE_ICS_IMPORT
        })

        val result = repository.importIntoTimetable(listOf(
            exam("  [考试]  高等数学  "),
            exam().apply { examLocation = "正心楼 102" },
            exam().apply { examDate = "2026-01-11" },
            exam().apply { examTime = "08:30-10:31" },
        ), "target")

        assertCounts(result, total = 4, imported = 3, duplicates = 1, invalid = 0)
        val saved = events("target")
        assertEquals(4, saved.size)
        assertEquals("  高等数学  ", saved.single { it.id == "legacy" }.name)
        assertTrue(saved.any { it.place == "正心楼 102" })
        assertTrue(saved.any { it.from.time == at(2026, Calendar.JANUARY, 11, 8, 30) })
        assertTrue(saved.any { it.to.time == at(2026, Calendar.JANUARY, 10, 10, 31) })
    }

    @Test
    fun manualBatchInsertFailureRollsBackEarlierInsertAndRetainsExistingRows() {
        db.eventItemDao().insertEventSync(storedExam("existing"))
        failSecondInsert()

        assertInjectedFailure {
            repository.importIntoTimetable(listOf(exam("第一场"), exam("第二场")), "target")
        }

        assertEquals(listOf("existing"), events("target").map { it.id })
        assertEquals(timetableEnd, storedTimetable("target").endTime.time)
    }

    @Test
    fun automaticBatchInsertFailureKeepsEarlierSuccessfulInsert() {
        db.eventItemDao().insertEventSync(storedExam("existing"))
        failSecondInsert()

        assertInjectedFailure {
            repository.importAutomatically(
                listOf(exam("第一场"), exam("第二场")), storedTimetable("target")
            )
        }

        assertEquals(setOf("[考试] 高等数学", "[考试] 第一场"), events("target").map { it.name }.toSet())
        assertTrue(events("target").any { it.id == "existing" })
        assertEquals(timetableEnd, storedTimetable("target").endTime.time)
    }

    @Test
    fun missingManualTargetThrowsForBothNonemptyAndEmptyBatches() {
        db.timetableDao().deleteTimetablesInIdsSync(listOf("target"))

        for (batch in listOf(listOf(exam()), emptyList())) {
            val failure = runCatching { repository.importIntoTimetable(batch, "target") }.exceptionOrNull()
            assertTrue("目标消失不能当作跳过或空批成功", failure is ExamImportTargetMissingException)
            assertTrue(failure is IllegalArgumentException)
            assertEquals("target", (failure as? ExamImportTargetMissingException)?.timetableId)
        }

        assertTrue(events("target").isEmpty())
        assertEquals(listOf("other"), db.timetableDao().getTimetablesSync().map { it.id })
    }

    @Test
    fun emptyBatchesReturnZeroCountsAndKeepExistingRows() {
        db.eventItemDao().insertEventSync(storedExam("existing"))

        val manual = repository.importIntoTimetable(emptyList(), "target")
        val automatic = repository.importAutomatically(emptyList(), storedTimetable("target"))

        assertCounts(manual, total = 0, imported = 0, duplicates = 0, invalid = 0)
        assertCounts(automatic, total = 0, imported = 0, duplicates = 0, invalid = 0)
        assertEquals("target 课表", manual.timetableName)
        assertEquals("target 课表", automatic.timetableName)
        assertEquals(listOf("existing"), events("target").map { it.id })
    }

    @Test
    fun importedFieldsUseCurrentTargetNameAndDoNotExtendTheTimetable() {
        db.timetableDao().renameTimetableSync("target", "已重命名课表")
        val input = exam("  [考试]  高等数学  ").apply {
            examTime = " 08:30 - 10:30 "
            examLocation = " 正心楼   101 "
        }

        val result = repository.importIntoTimetable(listOf(input), "target")

        assertCounts(result, total = 1, imported = 1, duplicates = 0, invalid = 0)
        assertEquals("已重命名课表", result.timetableName)
        val saved = events("target").single()
        assertEquals("target", saved.timetableId)
        assertEquals("[考试] 高等数学", saved.name)
        assertEquals(" 正心楼   101 ", saved.place)
        assertEquals(at(2026, Calendar.JANUARY, 10, 8, 30), saved.from.time)
        assertEquals(at(2026, Calendar.JANUARY, 10, 10, 30), saved.to.time)
        assertEquals(EventItem.TYPE.EXAM, saved.type)
        assertEquals(EventItem.SOURCE_EAS_IMPORT, saved.source)
        assertEquals("", saved.teacher)
        assertEquals("", saved.subjectId)
        assertEquals(0, saved.fromNumber)
        assertEquals(0, saved.lastNumber)
        assertEquals(timetableStart, storedTimetable("target").startTime.time)
        assertEquals(timetableEnd, storedTimetable("target").endTime.time)
        assertTrue(db.subjectDao().getAllSubjectsSync().isEmpty())
    }

    @Test
    fun localMemoExamsKeepTheSameExamTypeAndEasSourceAsRemoteExams() {
        val single = exam("单条备忘").apply { memoId = "memo-one" }
        val batchMemo = exam("批量备忘").apply { memoId = "memo-two" }

        val singleResult = repository.importIntoTimetable(listOf(single), "target")
        val batchResult = repository.importIntoTimetable(listOf(batchMemo, exam()), "target")

        assertCounts(singleResult, total = 1, imported = 1, duplicates = 0, invalid = 0)
        assertCounts(batchResult, total = 2, imported = 2, duplicates = 0, invalid = 0)
        val saved = events("target")
        assertEquals(setOf("[考试] 单条备忘", "[考试] 批量备忘", "[考试] 高等数学"), saved.map { it.name }.toSet())
        assertTrue(saved.all { it.type == EventItem.TYPE.EXAM && it.source == EventItem.SOURCE_EAS_IMPORT })
        assertTrue(saved.all { it.subjectId.isEmpty() })
    }

    @Test
    fun sameIdentityNonExamEventsArePreservedAndDoNotBlockEitherImportMode() {
        db.eventItemDao().insertEventSync(storedExam("class").apply { type = EventItem.TYPE.CLASS })
        db.eventItemDao().insertEventSync(storedExam("activity", "other").apply {
            name = "[考试] 自动导入"
            type = EventItem.TYPE.OTHER
        })

        val manual = repository.importIntoTimetable(listOf(exam()), "target")
        val automatic = repository.importAutomatically(listOf(exam("自动导入")), storedTimetable("target"))

        assertCounts(manual, total = 1, imported = 1, duplicates = 0, invalid = 0)
        assertCounts(automatic, total = 1, imported = 1, duplicates = 0, invalid = 0)
        assertEquals(3, events("target").size)
        assertEquals(EventItem.TYPE.CLASS, events("target").single { it.id == "class" }.type)
        assertEquals(EventItem.TYPE.OTHER, events("other").single { it.id == "activity" }.type)
    }

    private fun assertCounts(result: ExamImportResult, total: Int, imported: Int, duplicates: Int, invalid: Int) {
        assertEquals(total, result.totalCount)
        assertEquals(imported, result.importedCount)
        assertEquals(duplicates, result.duplicateCount)
        assertEquals(invalid, result.invalidCount)
        assertEquals(duplicates + invalid, result.skippedCount)
    }

    private fun failSecondInsert() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_second_exam BEFORE INSERT ON events " +
                "WHEN NEW.name = '[考试] 第二场' BEGIN SELECT RAISE(ABORT, 'injected exam failure'); END"
        )
    }

    private fun assertInjectedFailure(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("数据库写失败不得被当作无效考试吞掉", failure?.message.orEmpty().contains("injected exam failure"))
    }

    private fun exam(courseName: String = "高等数学") = ExamItem().apply {
        this.courseName = courseName
        examDate = "2026-01-10"
        examTime = "08:30-10:30"
        examLocation = "正心楼 101"
    }

    private fun timetable(id: String) = Timetable().apply {
        this.id = id
        name = "$id 课表"
        startTime = Timestamp(timetableStart)
        endTime = Timestamp(timetableEnd)
    }

    private fun storedExam(id: String, timetableId: String = "target") = EventItem().apply {
        this.id = id
        this.timetableId = timetableId
        name = "[考试] 高等数学"
        type = EventItem.TYPE.EXAM
        source = EventItem.SOURCE_MANUAL
        place = "正心楼 101"
        from = Timestamp(at(2026, Calendar.JANUARY, 10, 8, 30))
        to = Timestamp(at(2026, Calendar.JANUARY, 10, 10, 30))
    }

    private fun events(timetableId: String): List<EventItem> = db.eventItemDao().getEventsOfTimetableSync(timetableId)

    private fun storedTimetable(id: String): Timetable = requireNotNull(db.timetableDao().getTimetableByIdSync(id))

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
