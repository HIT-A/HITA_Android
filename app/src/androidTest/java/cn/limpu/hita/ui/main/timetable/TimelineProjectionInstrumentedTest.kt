package cn.limpu.hita.ui.main.timetable

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.main.timeline.TimelineListProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp

/**
 * 时间线投影在真实设备/模拟机上的行为回归。
 *
 * 单测已经覆盖纯函数；这里补的是"用真实 Room 写入的事件也能得到同样的首末/进行中判定"，
 * 避免只有内存构造的对象才成立。
 */
@RunWith(AndroidJUnit4::class)
class TimelineProjectionInstrumentedTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun realRoomEventsProjectIntoStableTimelineBounds() {
        val db = AppDatabase.getDatabase(app)
        val table = Timetable().apply { name = "Timeline projection fixture" }
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val past = EventItem().apply {
            id = "projection-past"
            timetableId = table.id
            name = "past"
            from = Timestamp(now - 2 * hour)
            to = Timestamp(now - hour)
        }
        val running = EventItem().apply {
            id = "projection-running"
            timetableId = table.id
            name = "running"
            from = Timestamp(now - hour)
            to = Timestamp(now + hour)
        }
        val future = EventItem().apply {
            id = "projection-future"
            timetableId = table.id
            name = "future"
            from = Timestamp(now + hour)
            to = Timestamp(now + 2 * hour)
        }

        // Room 的阻塞 DAO 不允许在主线程执行，夹具写入与读取都放到 IO 线程。
        val read = runBlocking(Dispatchers.IO) {
            db.timetableDao().saveTimetableSync(table)
            listOf(past, running, future).forEach(db.eventItemDao()::insertEventSync)
            db.eventItemDao().getEventsOfTimetableSync(table.id).sortedBy { it.from.time }
        }

        val bounds = TimelineListProjection.timelineBounds(read)
        assertEquals(0 to read.lastIndex, bounds)

        val rows = read.mapIndexed { index, event ->
            TimelineListProjection.rowState(event, index, bounds, now)
        }
        assertTrue(rows.first().isFirstTimeline)
        assertTrue(rows.last().isLastTimeline)
        assertTrue(rows.first().isPassed)
        assertTrue(rows[1].isNow)
        assertFalse(rows.last().isPassed)
        // 进度由传入的 now 决定，不来自绘制期时钟。
        assertTrue(rows[1].progress in 0f..1f)
    }
}
