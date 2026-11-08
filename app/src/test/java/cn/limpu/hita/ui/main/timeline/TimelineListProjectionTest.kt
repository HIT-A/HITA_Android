package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Timestamp

class TimelineListProjectionTest {

    private fun event(
        id: String,
        from: Long,
        to: Long,
        type: EventItem.TYPE = EventItem.TYPE.CLASS,
        name: String = id,
    ) = EventItem().apply {
        this.id = id
        this.type = type
        this.name = name
        this.from = Timestamp(from)
        this.to = Timestamp(to)
    }

    private val base = 1_700_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `bounds skip tag hints and pick the real first and last events`() {
        val events = listOf(
            event("hint", base, base + hour, type = EventItem.TYPE.TAG),
            event("a", base + hour, base + 2 * hour),
            event("b", base + 2 * hour, base + 3 * hour),
            event("hint2", base + 3 * hour, base + 4 * hour, type = EventItem.TYPE.TAG),
        )
        assertEquals(1 to 2, TimelineListProjection.timelineBounds(events))
    }

    @Test
    fun `bounds report -1 when the day holds only hints`() {
        val events = listOf(
            event("hint", base, base + hour, type = EventItem.TYPE.TAG),
            event("hint2", base + hour, base + 2 * hour, type = EventItem.TYPE.TAG),
        )
        assertEquals(-1 to -1, TimelineListProjection.timelineBounds(emptyList()))
        assertEquals(-1 to -1, TimelineListProjection.timelineBounds(events))
    }

    @Test
    fun `row state marks only the real first and last rows`() {
        val events = listOf(
            event("a", base - 2 * hour, base - hour),
            event("b", base - hour, base + hour),
            event("c", base + hour, base + 2 * hour),
        )
        val bounds = TimelineListProjection.timelineBounds(events)
        val now = base
        val first = TimelineListProjection.rowState(events[0], 0, bounds, now)
        val middle = TimelineListProjection.rowState(events[1], 1, bounds, now)
        val last = TimelineListProjection.rowState(events[2], 2, bounds, now)

        assertTrue(first.isFirstTimeline)
        assertFalse(first.isLastTimeline)
        assertFalse(middle.isFirstTimeline)
        assertFalse(middle.isLastTimeline)
        assertFalse(last.isFirstTimeline)
        assertTrue(last.isLastTimeline)

        assertTrue(first.isPassed)
        assertFalse(middle.isPassed)
        assertFalse(last.isPassed)
    }

    @Test
    fun `now row is the only one with a running progress`() {
        val events = listOf(
            event("a", base - hour, base + hour),
            event("b", base + hour, base + 2 * hour),
        )
        val bounds = TimelineListProjection.timelineBounds(events)
        val running = TimelineListProjection.rowState(events[0], 0, bounds, base)
        val future = TimelineListProjection.rowState(events[1], 1, bounds, base)

        assertTrue(running.isNow)
        assertEquals(0.5f, running.progress, 0.001f)
        assertFalse(future.isNow)
        assertEquals(0f, future.progress, 0f)
    }

    @Test
    fun `progress follows the supplied clock instead of freezing`() {
        val event = event("a", base, base + 100_000L)
        val bounds = 0 to 0
        val early = TimelineListProjection.rowState(event, 0, bounds, base + 25_000L)
        val late = TimelineListProjection.rowState(event, 0, bounds, base + 75_000L)

        assertEquals(0.25f, early.progress, 0.001f)
        assertEquals(0.75f, late.progress, 0.001f)
        assertTrue(early.progress < late.progress)
    }

    @Test
    fun `upcoming exam flag depends on the supplied clock`() {
        val exam = event("exam", base + hour, base + 2 * hour, type = EventItem.TYPE.EXAM)
        val bounds = 0 to 0

        assertTrue(TimelineListProjection.rowState(exam, 0, bounds, base).isUpcomingExam)
        assertFalse(TimelineListProjection.rowState(exam, 0, bounds, base + 2 * hour).isUpcomingExam)
    }

    @Test
    fun `empty list yields a neutral row state without crashing`() {
        val bounds = TimelineListProjection.timelineBounds(emptyList())
        val event = event("a", base, base + hour)
        val row = TimelineListProjection.rowState(event, 0, bounds, base)

        assertFalse(row.isFirstTimeline)
        assertFalse(row.isLastTimeline)
    }
}
