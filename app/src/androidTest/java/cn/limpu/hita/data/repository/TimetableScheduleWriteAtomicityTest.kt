package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.utils.TimeTools
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.util.Calendar

/** 改学期起点/作息：课表与课次必须同事务落库，且不得用调用方的旧对象覆盖库中字段。 */
@RunWith(AndroidJUnit4::class)
class TimetableScheduleWriteAtomicityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository

    private val start = TimeTools.getMonday(Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 31, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis).timeInMillis
    private val day = 86_400_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = TimetableRepository(app, db, DisplayScopeSelector {
            error("Schedule writes must not change the display scope")
        })
        db.timetableDao().saveTimetableSync(Timetable().apply {
            id = "table"; name = "秋季课表"
            startTime = Timestamp(start)
            endTime = Timestamp(start + 120 * day)
        })
        db.eventItemDao().insertEventSync(event("class", EventItem.TYPE.CLASS, fromNumber = 1, lastNumber = 2))
        db.eventItemDao().insertEventSync(event("single", EventItem.TYPE.CLASS, fromNumber = 1, lastNumber = 1))
        db.eventItemDao().insertEventSync(event("exam", EventItem.TYPE.EXAM, fromNumber = 1, lastNumber = 1))
    }

    @After fun tearDown() {
        db.close()
    }

    private fun event(id: String, type: EventItem.TYPE, fromNumber: Int, lastNumber: Int) = EventItem().apply {
        this.id = id; timetableId = "table"; subjectId = "subject"; name = id; this.type = type
        from = Timestamp(start + 8 * 3_600_000L)
        to = Timestamp(start + 10 * 3_600_000L)
        this.fromNumber = fromNumber; this.lastNumber = lastNumber
    }

    private fun stored(): Timetable = requireNotNull(db.timetableDao().getTimetableByIdSync("table"))

    private fun eventsById(): Map<String, EventItem> =
        db.eventItemDao().getEventsOfTimetableSync("table").associateBy { it.id }

    private fun failOn(operation: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_events_$operation BEFORE $operation ON events " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
        )
    }

    private fun assertFails(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("注入的写入失败应抛出", error)
    }

    private fun TimePeriodInDay.minutes() = listOf(from.hour, from.minute, to.hour, to.minute)

    @Test fun startDateShiftsTimetableAndClassesInOneWrite() {
        val requested = start + 9 * day
        val expectedStart = TimeTools.getMonday(requested).timeInMillis
        val offset = expectedStart - start

        repository.changeTimetableStartDateSync(stored(), requested)

        assertEquals(expectedStart, stored().startTime.time)
        assertEquals(start + 120 * day + offset, stored().endTime.time)
        val events = eventsById()
        assertEquals(start + 8 * 3_600_000L + offset, events.getValue("class").from.time)
        assertEquals(start + 8 * 3_600_000L + offset, events.getValue("single").from.time)
        assertEquals("考试不随起点平移", start + 8 * 3_600_000L, events.getValue("exam").from.time)
    }

    @Test fun startDateFailureLeavesTimetableClassesAndCallerUntouched() {
        val caller = stored()
        failOn("UPDATE")

        assertFails { repository.changeTimetableStartDateSync(caller, start + 9 * day) }

        assertEquals(start, stored().startTime.time)
        assertEquals(start + 120 * day, stored().endTime.time)
        assertEquals(start + 8 * 3_600_000L, eventsById().getValue("class").from.time)
        assertEquals("失败时不得修改调用方对象", start, caller.startTime.time)
    }

    @Test fun startDateKeepsFieldsChangedElsewhere() {
        val stale = stored()
        db.timetableDao().saveTimetableSync(stored().apply { name = "已重命名"; groupId = "group" })

        repository.changeTimetableStartDateSync(stale, start + 9 * day)

        assertEquals("已重命名", stored().name)
        assertEquals("group", stored().groupId)
    }

    @Test fun structureUpdatesClassesStartingOrEndingAtThePeriod() {
        val period = TimePeriodInDay(TimeInDay(7, 50), TimeInDay(8, 35))

        repository.changeTimetableStructureSync(stored(), period, 0)

        assertEquals(period.minutes(), stored().scheduleStructure[0].minutes())
        val events = eventsById()
        val single = Calendar.getInstance()
        single.timeInMillis = events.getValue("single").from.time
        assertEquals(listOf(7, 50), listOf(single[Calendar.HOUR_OF_DAY], single[Calendar.MINUTE]))
        single.timeInMillis = events.getValue("single").to.time
        assertEquals(listOf(8, 35), listOf(single[Calendar.HOUR_OF_DAY], single[Calendar.MINUTE]))
        single.timeInMillis = events.getValue("class").from.time
        assertEquals(listOf(7, 50), listOf(single[Calendar.HOUR_OF_DAY], single[Calendar.MINUTE]))
        assertEquals("结束于第 2 节的课次不改结束时间", start + 10 * 3_600_000L, events.getValue("class").to.time)
        assertEquals("考试不随作息调整", start + 8 * 3_600_000L, events.getValue("exam").from.time)
    }

    @Test fun structureFailureLeavesStoredStructureAndCallerUntouched() {
        val caller = stored()
        val before = caller.scheduleStructure[0].minutes()
        failOn("INSERT")

        assertFails {
            repository.changeTimetableStructureSync(caller, TimePeriodInDay(TimeInDay(7, 50), TimeInDay(8, 35)), 0)
        }

        assertEquals(before, stored().scheduleStructure[0].minutes())
        assertEquals(start + 8 * 3_600_000L, eventsById().getValue("single").from.time)
        assertEquals("失败时不得修改调用方对象", before, caller.scheduleStructure[0].minutes())
    }

    @Test fun structureKeepsFieldsChangedElsewhere() {
        val stale = stored()
        db.timetableDao().saveTimetableSync(stored().apply { name = "已重命名"; groupId = "group" })

        repository.changeTimetableStructureSync(stale, TimePeriodInDay(TimeInDay(7, 50), TimeInDay(8, 35)), 0)

        assertEquals("已重命名", stored().name)
        assertEquals("group", stored().groupId)
    }
}
