package cn.limpu.hita.ui.main.homework

import cn.limpu.hita.data.model.homework.HomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkDayPartitionsTest {

    private fun item(
        id: String,
        date: String?,
        completed: Boolean = false,
    ) = HomeworkItem(id = id, title = id, createdAt = 0L, completed = completed, plannedDate = date)

    @Test
    fun `dated filter splits pending and completed and keeps order`() {
        val items = listOf(
            item("a", "2026-11-08"),
            item("b", "2026-11-08", completed = true),
            item("c", "2026-11-09"),
            item("d", null),
            item("e", "2026-11-08"),
            item("f", null, completed = true),
        )
        val partitions = partitionHomeworkDay(items) { it.plannedDate == "2026-11-08" }

        assertEquals(listOf("a", "e"), partitions.pending.map { it.id })
        assertEquals(listOf("b"), partitions.completed.map { it.id })
        assertEquals(2, partitions.unplannedCount)
    }

    @Test
    fun `undated filter counts undated items as the day bucket`() {
        val items = listOf(
            item("a", null),
            item("b", null, completed = true),
            item("c", "2026-11-08"),
        )
        val partitions = partitionHomeworkDay(items) { it.plannedDate == null }

        assertEquals(listOf("a"), partitions.pending.map { it.id })
        assertEquals(listOf("b"), partitions.completed.map { it.id })
        assertEquals(2, partitions.unplannedCount)
    }

    @Test
    fun `empty day yields empty buckets but keeps the global undated count`() {
        val partitions = partitionHomeworkDay(listOf(item("a", null), item("b", "2026-11-08"))) {
            it.plannedDate == "2030-01-01"
        }

        assertTrue(partitions.pending.isEmpty())
        assertTrue(partitions.completed.isEmpty())
        assertEquals(1, partitions.unplannedCount)
    }

    @Test
    fun `partition is deterministic for repeated calls`() {
        val items = listOf(item("a", "2026-11-08"), item("b", "2026-11-08", completed = true))
        val first = partitionHomeworkDay(items) { it.plannedDate == "2026-11-08" }
        val second = partitionHomeworkDay(items) { it.plannedDate == "2026-11-08" }
        assertEquals(first, second)
    }
}
