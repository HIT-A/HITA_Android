package cn.limpu.hita.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSyncPlannerTest {

    private fun entry(
        eventId: String = "e1",
        startMillis: Long = 1_000L,
        endMillis: Long = 2_000L,
        timetableId: String = "t1",
    ) = CalendarSyncEntry(
        eventId = eventId,
        title = "高等数学",
        location = "A101",
        description = "王老师",
        startMillis = startMillis,
        endMillis = endMillis,
        idempotencyKey = CalendarSyncPlanner.idempotencyKey(timetableId, eventId, startMillis),
    )

    @Test
    fun `idempotency key separates timetable event and start time`() {
        val a = CalendarSyncPlanner.idempotencyKey("t1", "e1", 1_000L)
        val b = CalendarSyncPlanner.idempotencyKey("t2", "e1", 1_000L)
        val c = CalendarSyncPlanner.idempotencyKey("t1", "e2", 1_000L)
        val d = CalendarSyncPlanner.idempotencyKey("t1", "e1", 2_000L)

        assertEquals("hita:t1:e1:1000", a)
        assertEquals(4, setOf(a, b, c, d).size)
    }

    @Test
    fun `unknown key becomes an insert`() {
        val plan = CalendarSyncPlanner.plan(listOf(entry()), emptyMap())

        assertEquals(1, plan.inserts.size)
        assertTrue(plan.updates.isEmpty())
        assertFalse(plan.isNoOp)
    }

    @Test
    fun `same key becomes an update instead of a duplicate`() {
        val item = entry()
        val plan = CalendarSyncPlanner.plan(listOf(item), mapOf(item.idempotencyKey to 42L))

        assertTrue(plan.inserts.isEmpty())
        assertEquals(1, plan.updates.size)
        assertEquals(42L, plan.updates.first().first)
    }

    @Test
    fun `invalid time range is skipped rather than written`() {
        val plan = CalendarSyncPlanner.plan(listOf(entry(startMillis = 5_000L, endMillis = 5_000L)), emptyMap())

        assertEquals(1, plan.skipped.size)
        assertTrue(plan.isNoOp)
    }

    @Test
    fun `a real timetable produces one action per course slot`() {
        val slots = listOf(
            entry(eventId = "e1", startMillis = 1_000L),
            entry(eventId = "e2", startMillis = 3_000L, endMillis = 4_000L),
        )
        val plan = CalendarSyncPlanner.plan(slots, mapOf(slots[1].idempotencyKey to 7L))

        assertEquals(1, plan.inserts.size)
        assertEquals(slots[1].idempotencyKey, CalendarSyncPlanner.idempotencyKey("t1", "e2", 3_000L))
        assertEquals(2, plan.total)
    }
}
