package cn.limpu.hita.ui.main.homework

import cn.limpu.hita.data.model.homework.HomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkBoardTest {

    private fun item(
        id: String,
        date: String?,
        completed: Boolean = false,
    ) = HomeworkItem(id = id, title = id, createdAt = 0L, completed = completed, plannedDate = date)

    @Test
    fun `every date is present and groups ascend with undated last`() {
        val board = buildHomeworkBoard(
            listOf(
                item("d", null),
                item("c", "2026-11-20"),
                item("a", "2026-11-08"),
                item("b", "2026-11-09"),
            )
        )

        assertEquals(listOf("2026-11-08", "2026-11-09", "2026-11-20", null), board.groups.map { it.date })
        assertEquals(listOf("a", "b", "c", "d"), board.groups.flatMap { group -> group.pending.map { it.id } })
        assertEquals(4, board.pendingCount)
        assertEquals(1, board.undatedCount)
    }

    @Test
    fun `completed items leave the pending groups and keep their own date order`() {
        val board = buildHomeworkBoard(
            listOf(
                item("a", "2026-11-08"),
                item("a-done", "2026-11-08", completed = true),
                item("b", "2026-11-09"),
                item("b-done", "2026-11-09", completed = true),
                item("legacy-done", null, completed = true),
            )
        )

        assertEquals(listOf("2026-11-08", "2026-11-09"), board.groups.map { it.date })
        assertTrue(board.groups.all { it.completed.isEmpty() })
        assertEquals(listOf("2026-11-08", "2026-11-09", null), board.completedGroups.map { it.date })
        assertEquals(listOf("a-done", "b-done", "legacy-done"), board.completedGroups.flatMap { group -> group.completed.map { it.id } })
        // 三条已完成：两条有日期 + 一条历史遗留未安排。
        assertEquals(3, board.completedCount)
        assertEquals(2, board.pendingCount)
    }

    @Test
    fun `a day with only completed items still surfaces in the completed block`() {
        val board = buildHomeworkBoard(
            listOf(
                item("open", "2026-11-08"),
                item("closed", "2026-11-10", completed = true),
            )
        )

        assertEquals(listOf("2026-11-08"), board.groups.map { it.date })
        assertEquals(listOf("2026-11-10"), board.completedGroups.map { it.date })
    }

    @Test
    fun `order inside a group follows storage so completing does not reshuffle`() {
        val items = listOf(
            item("later-added", "2026-11-08"),
            item("earlier-added", "2026-11-08"),
        )
        assertEquals(
            listOf("later-added", "earlier-added"),
            buildHomeworkBoard(items).groups.single().pending.map { it.id },
        )
        val toggled = items.map { it.copy(completed = true) }
        assertEquals(
            listOf("later-added", "earlier-added"),
            buildHomeworkBoard(toggled).completedGroups.single().completed.map { it.id },
        )
    }

    @Test
    fun `split separates undated group and keeps dates non-null`() {
        val board = buildHomeworkBoard(
            listOf(
                item("dated", "2026-11-08"),
                item("undated", null),
                item("done", "2026-11-09", completed = true),
            )
        )
        val split = board.groups.splitUndated()
        assertEquals(listOf("2026-11-08"), split.dated.map { it.date })
        assertEquals(listOf("undated"), split.undated?.pending?.map { it.id })
        assertEquals(listOf("2026-11-09"), board.completedGroups.splitUndated().dated.map { it.date })
    }

    @Test
    fun `empty input yields an empty board`() {
        val board = buildHomeworkBoard(emptyList())
        assertTrue(board.groups.isEmpty())
        assertTrue(board.completedGroups.isEmpty())
        assertEquals(0, board.totalCount)
        assertEquals(0, board.undatedCount)
    }
}
