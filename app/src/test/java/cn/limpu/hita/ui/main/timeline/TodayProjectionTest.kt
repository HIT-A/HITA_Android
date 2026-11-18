package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayProjectionTest {
    private val window = TodayDateWindow.from(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val hour = 3_600_000L
    private fun at(hours: Int) = window.start + hours * hour
    private fun event(id: String, start: Long, end: Long, type: EventItem.TYPE = EventItem.TYPE.CLASS) = EventItem().apply {
        this.id = id
        this.from = Timestamp(start)
        this.to = Timestamp(end)
        this.type = type
    }
    private fun List<EventItem>.ids() = map { it.id }

    @Test fun `agenda keeps every remaining item and separates ended events in stable order`() {
        val events = listOf(
            event("next-b", at(14), at(15), EventItem.TYPE.OTHER),
            event("active-b", at(10), at(13), EventItem.TYPE.EXAM),
            event("next-a", at(14), at(15)),
            event("active-a", at(10), at(13)),
            event("past", at(8), at(9)),
            event("later", at(16), at(17)),
        )
        val result = TodayProjection.project(window, events + events.first(), at(12))
        assertEquals(listOf("active-a", "active-b"), result.active.ids())
        assertEquals(listOf("next-a", "next-b"), result.nextGroup.ids())
        assertEquals(listOf("active-a", "active-b", "next-a", "next-b", "later"), result.remaining.ids())
        assertEquals(listOf("past"), result.ended.ids())
        assertEquals(6, result.allToday.size)
        assertEquals(1, result.completedCount)
        assertEquals(listOf(listOf("active-a", "active-b"), listOf("next-a", "next-b")), result.conflictGroups.map { it.ids() })
    }

    @Test fun `adjacent events do not conflict and boundary switches active item`() {
        val events = listOf(event("first", at(8), at(9)), event("second", at(9), at(10)))
        val result = TodayProjection.project(window, events, at(9))
        assertEquals(listOf("second"), result.active.ids())
        assertEquals(1, result.completedCount)
        assertTrue(result.conflictGroups.isEmpty())
    }

    @Test fun `connected overlaps keep the whole group including different start times`() {
        val result = TodayProjection.project(window, listOf(
            event("a", at(8), at(10)), event("b", at(9), at(11)), event("c", at(10), at(12)),
        ), at(9))
        assertEquals(listOf(listOf("a", "b", "c")), result.conflictGroups.map { it.ids() })
    }

    @Test fun `tomorrow preparation requires 2030 and every real event to have ended`() {
        val evening = event("evening", at(20), at(22), EventItem.TYPE.OTHER)
        val tomorrow = event("tomorrow", at(32), at(33))
        assertNull(TodayProjection.project(window, listOf(tomorrow), window.preparationStart - 1).tomorrow)
        assertNotNull(TodayProjection.project(window, listOf(tomorrow), window.preparationStart).tomorrow)
        assertNull(TodayProjection.project(window, listOf(evening, tomorrow), window.preparationStart).tomorrow)
        assertNotNull(TodayProjection.project(window, listOf(evening, tomorrow), at(22)).tomorrow)
        assertNull(TodayProjection.project(window, listOf(tomorrow), window.tomorrowStart).tomorrow)
    }

    @Test fun `empty day and hints do not imply completed tasks or block preparation`() {
        val result = TodayProjection.project(window, listOf(event("hint", at(20), at(23), EventItem.TYPE.TAG)), window.preparationStart)
        assertTrue(result.allToday.isEmpty())
        assertTrue(result.allFinished)
        assertTrue(result.ended.isEmpty())
        assertEquals(0, result.completedCount)
        assertTrue(requireNotNull(result.tomorrow).firstGroup.isEmpty())
    }

    @Test fun `cross midnight events overlap both days and original timestamps are preserved`() {
        val crossing = event("overnight", at(23), at(26))
        assertEquals(listOf("overnight"), TodayProjection.project(window, listOf(crossing), at(23)).active.ids())
        val nextWindow = TodayDateWindow.from(Instant.ofEpochMilli(at(24)), window.zoneId)
        val result = TodayProjection.project(nextWindow, listOf(crossing), at(24))
        assertEquals(listOf("overnight"), result.active.ids())
        assertEquals(at(23), result.active.single().from.time)
        // The overnight event still in progress correctly prevents preparation today.
        assertNull(TodayProjection.project(window, listOf(crossing), window.preparationStart).tomorrow)
    }

    @Test fun `window edges hidden rows and invalid times remain distinguishable`() {
        val invalid = event("invalid", at(12), at(11))
        val hidden = event("hidden", at(12), at(13)).apply { this.hidden = 1 }
        val result = TodayProjection.project(window, listOf(
            invalid, hidden, event("previous", at(-1), at(0)), event("next", at(24), at(25)),
        ), window.preparationStart)
        assertEquals(listOf("invalid"), result.allToday.ids())
        assertEquals(listOf("invalid"), result.invalidEvents.ids())
        assertEquals(listOf("invalid"), result.remaining.ids())
        assertTrue(result.ended.isEmpty())
        assertEquals(0, result.completedCount)
        assertTrue(result.allFinished)
        assertEquals(listOf("next"), requireNotNull(result.tomorrow).firstGroup.ids())
    }

    @Test fun `tomorrow includes every earliest simultaneous item and crosses year boundary`() {
        val yearEnd = TodayDateWindow.from(Instant.parse("2026-12-31T12:30:00Z"), window.zoneId)
        val start = yearEnd.tomorrowStart + 8 * hour
        val result = TodayProjection.project(yearEnd, listOf(
            event("b", start, start + hour), event("a", start, start + hour),
            event("later", start + hour, start + 2 * hour),
        ), yearEnd.preparationStart)
        val tomorrow = requireNotNull(result.tomorrow)
        assertEquals("2027-01-01", tomorrow.date.toString())
        assertEquals(listOf("a", "b"), tomorrow.firstGroup.ids())
    }
}
