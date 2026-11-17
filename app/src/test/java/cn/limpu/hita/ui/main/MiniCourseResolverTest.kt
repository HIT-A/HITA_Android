package cn.limpu.hita.ui.main

import cn.limpu.hita.data.model.timetable.EventItem
import java.sql.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniCourseResolverTest {

    private val minute = 60_000L

    private fun course(
        name: String,
        startMinute: Long,
        endMinute: Long,
        place: String = "A101",
    ) = EventItem().apply {
        this.name = name
        this.place = place
        type = EventItem.TYPE.CLASS
        from = Timestamp(startMinute * minute)
        to = Timestamp(endMinute * minute)
    }

    private fun now(minuteOfDay: Long) = minuteOfDay * minute

    @Test
    fun `class in progress reports ongoing with progress`() {
        val state = MiniCourseResolver.resolve(
            listOf(course("高等数学", 100, 160)),
            now(130),
        )

        assertEquals(MiniCoursePhase.ONGOING, state.phase)
        assertEquals("高等数学", state.title)
        assertEquals("A101", state.place)
        assertEquals(0.5f, state.progress, 0.001f)
    }

    @Test
    fun `class starting within fifteen minutes reports upcoming`() {
        val state = MiniCourseResolver.resolve(
            listOf(course("线性代数", 100, 160)),
            now(90),
        )

        assertEquals(MiniCoursePhase.UPCOMING, state.phase)
        assertEquals("线性代数", state.title)
        assertEquals("09:40-10:40", state.timeText)
    }

    @Test
    fun `class starting beyond the window does not surface as upcoming`() {
        val state = MiniCourseResolver.resolve(
            listOf(course("线性代数", 300, 360)),
            now(100),
        )

        assertEquals(MiniCoursePhase.DONE, state.phase)
        assertEquals("", state.title)
    }

    @Test
    fun `no classes at all reports idle`() {
        assertEquals(MiniCoursePhase.IDLE, MiniCourseResolver.resolve(emptyList(), now(100)).phase)
    }

    @Test
    fun `all classes finished reports done`() {
        val state = MiniCourseResolver.resolve(
            listOf(course("早课", 10, 50), course("午课", 60, 100)),
            now(200),
        )

        assertEquals(MiniCoursePhase.DONE, state.phase)
    }

    @Test
    fun `adjacent classes pick the running one at the boundary`() {
        val events = listOf(course("第一节", 100, 150), course("第二节", 150, 200))

        val duringFirst = MiniCourseResolver.resolve(events, now(150).let { it - 1 })
        val duringSecond = MiniCourseResolver.resolve(events, now(150))

        assertEquals("第一节", duringFirst.title)
        assertEquals("第二节", duringSecond.title)
        assertEquals(MiniCoursePhase.ONGOING, duringSecond.phase)
        assertTrue(duringSecond.progress >= 0f)
    }

    @Test
    fun `exam events never drive the mini bar`() {
        val exam = EventItem().apply {
            name = "期末考试"
            type = EventItem.TYPE.EXAM
            from = Timestamp(100 * minute)
            to = Timestamp(160 * minute)
        }

        val state = MiniCourseResolver.resolve(listOf(exam), now(120))

        assertEquals(MiniCoursePhase.IDLE, state.phase)
    }
    @Test fun `simultaneous courses are selected by stable id rather than incoming order`() {
        val a = course("a", 100, 150).apply { id = "a" }
        val b = course("b", 100, 150).apply { id = "b" }
        assertEquals("a", MiniCourseResolver.resolve(listOf(b, a), now(100)).title)
        assertEquals("a", MiniCourseResolver.resolve(listOf(a, b), now(100)).title)
        assertEquals(MiniCoursePhase.DONE, MiniCourseResolver.resolve(listOf(a), now(150)).phase)
    }

    @Test fun `invalid course cannot drive upcoming mini bar`() {
        assertEquals(MiniCoursePhase.IDLE,
            MiniCourseResolver.resolve(listOf(course("invalid", 100, 100)), now(90)).phase)
    }

}
