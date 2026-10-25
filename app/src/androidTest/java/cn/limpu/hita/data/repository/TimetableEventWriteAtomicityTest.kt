package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.util.Calendar

/** 普通课次/活动与必要的课表延长必须一并成功，且不得回写调用方持有的旧课表。 */
@RunWith(AndroidJUnit4::class)
class TimetableEventWriteAtomicityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository

    private val start = at(2026, Calendar.AUGUST, 31)
    private val originalEnd = at(2026, Calendar.SEPTEMBER, 6, 23, 59)
    private val expectedExtendedEnd = at(2026, Calendar.SEPTEMBER, 13, 23, 59)
    private val alreadyLongerEnd = at(2026, Calendar.SEPTEMBER, 27, 23, 59)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = TimetableRepository(app, db, DisplayScopeSelector {
            error("Writing events must not select a display scope")
        })
        db.timetableDao().saveTimetableSync(Timetable().apply {
            id = "table"
            name = "原课表"
            groupId = "original-group"
            startTime = Timestamp(start)
            endTime = Timestamp(originalEnd)
        })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun extensionFailureRollsBackNewSubjectAndAllEvents() {
        val subject = subject()
        val result = events(subject = subject, days = listOf(9, 10))
        failTimetableWrites()

        assertInjectedFailure { persist(result, subject) }

        assertTrue("延长失败后不得留下任何课次", storedEvents().isEmpty())
        assertTrue("延长失败后不得留下新课程", db.subjectDao().getAllSubjectsSync().isEmpty())
        assertEquals(originalEnd, stored().endTime.time)
    }

    @Test
    fun extensionFailureRestoresPreviouslySavedSubject() {
        db.subjectDao().saveSubjectSync(subject().apply { name = "数据库中的旧课程" })
        val replacement = subject().apply { name = "待保存的新课程名" }
        val result = events(subject = replacement)
        failTimetableWrites()

        assertInjectedFailure { persist(result, replacement) }

        assertEquals("数据库中的旧课程", db.subjectDao().getAllSubjectsSync().single().name)
        assertTrue(storedEvents().isEmpty())
        assertEquals(originalEnd, stored().endTime.time)
    }

    @Test
    fun extensionFailureLeavesCallerObjectsUntouched() {
        val caller = stored()
        val subject = subject()
        val result = events(timetable = caller, subject = subject)
        val event = result.events.single()
        val originalEvent = listOf(event.name, event.subjectId, event.timetableId, event.from.time, event.to.time)
        failTimetableWrites()

        assertInjectedFailure { persist(result, subject) }

        assertEquals("失败不得提前延长调用方课表", originalEnd, caller.endTime.time)
        assertEquals(start, caller.startTime.time)
        assertEquals("原课表", caller.name)
        assertEquals("original-group", caller.groupId)
        assertEquals("测试课程", subject.name)
        assertEquals("table", subject.timetableId)
        assertEquals(originalEvent, listOf(event.name, event.subjectId, event.timetableId, event.from.time, event.to.time))
    }

    @Test
    fun extensionKeepsNameAndGroupChangedAfterCallerLoaded() {
        val stale = stored()
        db.timetableDao().saveTimetableSync(stored().apply {
            name = "已重命名"
            groupId = "new-group"
        })

        persist(events(timetable = stale))

        assertEquals("已重命名", stored().name)
        assertEquals("new-group", stored().groupId)
        assertEquals(expectedExtendedEnd, stored().endTime.time)
        assertEquals(1, storedEvents().size)
    }

    @Test
    fun staleCallerCannotShortenAnAlreadyExtendedTimetable() {
        val stale = stored()
        db.timetableDao().saveTimetableSync(stored().apply {
            endTime = Timestamp(alreadyLongerEnd)
        })

        persist(events(timetable = stale))

        assertEquals("应按库中最新范围决定是否延长", alreadyLongerEnd, stored().endTime.time)
        assertEquals(1, storedEvents().size)
    }

    @Test
    fun courseCreationSavesSubjectAndEverySessionInTheTargetTimetable() {
        val subject = subject()
        val result = events(subject = subject, days = listOf(9, 10))

        persist(result, subject)

        val savedSubject = db.subjectDao().getAllSubjectsSync().single()
        assertEquals(subject.id, savedSubject.id)
        assertEquals("table", savedSubject.timetableId)
        assertEquals("测试课程", savedSubject.name)
        val savedEvents = storedEvents()
        assertEquals(result.events.map { it.id }.toSet(), savedEvents.map { it.id }.toSet())
        assertTrue(savedEvents.all { it.subjectId == subject.id && it.timetableId == "table" })
        assertTrue(savedEvents.all { it.type == EventItem.TYPE.CLASS && it.source == "MANUAL" })
        assertEquals(expectedExtendedEnd, stored().endTime.time)
    }

    @Test
    fun activityCreationNeedsNoSubjectAndExtendsToSundayAt2359() {
        val result = events(days = listOf(9, 10))

        persist(result)

        val savedEvents = storedEvents()
        assertEquals(2, savedEvents.size)
        assertTrue(savedEvents.all { it.type == EventItem.TYPE.OTHER && it.subjectId.isEmpty() })
        assertEquals(result.events.map { it.to.time }.toSet(), savedEvents.map { it.to.time }.toSet())
        assertTrue(db.subjectDao().getAllSubjectsSync().isEmpty())
        assertEquals(expectedExtendedEnd, stored().endTime.time)
    }

    @Test
    fun sessionsWithinCurrentRangeDoNotWriteTheTimetable() {
        val subject = subject()
        val result = events(subject = subject, days = listOf(2, 3))
        failTimetableWrites()

        persist(result, subject)

        assertEquals(2, storedEvents().size)
        assertEquals(subject.id, db.subjectDao().getAllSubjectsSync().single().id)
        assertEquals(originalEnd, stored().endTime.time)
    }

    @Test
    fun emptyEventListDoesNotSaveSubjectOrWriteTheTimetable() {
        failTimetableWrites()

        persist(ScheduleEventCreator.Result(emptyList(), stored()), subject())

        assertTrue(storedEvents().isEmpty())
        assertTrue(db.subjectDao().getAllSubjectsSync().isEmpty())
        assertEquals(originalEnd, stored().endTime.time)
    }

    @Test
    fun eventInsertFailureRollsBackSubjectWithoutExtendingTimetable() {
        val caller = stored()
        val subject = subject()
        val result = events(timetable = caller, subject = subject, days = listOf(9, 10))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_second_event BEFORE INSERT ON events " +
                "WHEN NEW.id = 'event-10' BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
        )

        assertInjectedFailure { persist(result, subject) }

        assertTrue("第二条插入失败时第一条也应回滚", storedEvents().isEmpty())
        assertTrue(db.subjectDao().getAllSubjectsSync().isEmpty())
        assertEquals(originalEnd, stored().endTime.time)
        assertEquals(originalEnd, caller.endTime.time)
    }

    @Test
    fun additionalSessionsReuseExistingSubjectWithoutReplacingItsMetadata() {
        val existing = subject().apply { name = "保留课程名称"; credit = 3.5f }
        db.subjectDao().saveSubjectSync(existing)
        val result = events(subject = existing)

        persist(result)

        val savedSubject = db.subjectDao().getAllSubjectsSync().single()
        assertEquals("保留课程名称", savedSubject.name)
        assertEquals(3.5f, savedSubject.credit, 0f)
        assertEquals(existing.id, storedEvents().single().subjectId)
        assertEquals(expectedExtendedEnd, stored().endTime.time)
    }

    // Keep all behavior assertions above while changing only this seam to the unified write use case.
    private fun persist(result: ScheduleEventCreator.Result, subjectToSave: TermSubject? = null) {
        TimetableWriteUseCase(
            store = repository,
            refreshWidgets = {},
            deliverResult = { it() },
            onWriteFailure = { throw AssertionError("Unexpected asynchronous write", it) },
            onRefreshFailure = { throw AssertionError("Unexpected refresh failure", it) },
        ).addSync(result, subjectToSave)
    }

    private fun events(
        timetable: Timetable = stored(),
        subject: TermSubject? = null,
        days: List<Int> = listOf(9),
    ): ScheduleEventCreator.Result = ScheduleEventCreator.buildEvents(
        timetable = timetable,
        content = ScheduleEventCreator.Content(name = subject?.name ?: "测试活动", subject = subject),
        ranges = days.map { day ->
            ScheduleEventCreator.FixedRange(
                fromMs = at(2026, Calendar.SEPTEMBER, day, 8, 30),
                toMs = at(2026, Calendar.SEPTEMBER, day, 10, 15),
                fromNumber = if (subject != null) 1 else 0,
                lastNumber = if (subject != null) 1 else 0,
            )
        },
        source = "MANUAL",
    ).also { result ->
        result.events.zip(days).forEach { (event, day) -> event.id = "event-$day" }
    }

    private fun subject() = TermSubject().apply {
        id = "subject"
        name = "测试课程"
        timetableId = "table"
        color = 0x123456
    }

    private fun stored(): Timetable = requireNotNull(db.timetableDao().getTimetableByIdSync("table"))

    private fun storedEvents(): List<EventItem> = db.eventItemDao().getEventsOfTimetableSync("table")

    private fun failTimetableWrites() {
        // Room's save is INSERT OR REPLACE. Cover INSERT and UPDATE so either write form fails.
        listOf("INSERT", "UPDATE").forEach { operation ->
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_timetable_$operation BEFORE $operation ON timetable " +
                    "WHEN NEW.id = 'table' BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
            )
        }
    }

    private fun assertInjectedFailure(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("写入失败应同步向调用方抛出", error)
        assertTrue(
            "应由测试注入的数据库异常触发失败，实际为 $error",
            generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("injected failure") },
        )
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
