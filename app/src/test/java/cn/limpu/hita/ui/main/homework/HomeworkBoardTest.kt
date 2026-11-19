package cn.limpu.hita.ui.main.homework

import cn.limpu.hita.data.model.homework.HomeworkItem
import cn.limpu.hita.data.model.homework.ImportedTodo
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkBoardTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = "2026-11-08"
    private val now = Instant.parse("2026-11-07T16:00:00Z").toEpochMilli()

    private fun personal(id: String, date: String?, time: String? = null, completed: Boolean = false) =
        HomeworkItem(id, id, 0L, completed, date, time)

    private fun remote(id: String, dueAt: String, completed: Boolean = false) =
        ImportedTodo(id, id, "合成课程", Instant.parse(dueAt).toEpochMilli(), completed)

    @Test
    fun `same day deadlines interleave by instant regardless of source with undated last`() {
        val grader = ImportedTodoGroup(TodoPlatform.GRADER, "grader-user", listOf(remote("same-id", "2026-11-07T16:15:00Z")))
        val genius = ImportedTodoGroup(TodoPlatform.GENIUSTA, "genius-user", listOf(remote("same-id", "2026-11-08T10:00:00Z")))
        val board = buildHomeworkBoard(
            listOf(personal("legacy", null), personal("end of day", date), personal("morning", date, "09:00")),
            listOf(grader, genius),
            zone,
            now,
        )

        assertEquals(
            listOf(
                TodoKey.Imported(TodoPlatform.GRADER, "grader-user", "same-id"),
                TodoKey.Personal("morning"),
                TodoKey.Imported(TodoPlatform.GENIUSTA, "genius-user", "same-id"),
                TodoKey.Personal("end of day"),
                TodoKey.Personal("legacy"),
            ),
            board.pending.map { it.key },
        )
        assertEquals("2026-11-08 00:15", board.pending.first().deadlineText)
        assertEquals(mapOf(date to 4), board.pendingByDate)
        assertEquals(5, board.pending.map { it.key.listKey }.distinct().size)
    }

    @Test
    fun `an imported deadline remains in Beijing time on devices in another time zone`() {
        val group = ImportedTodoGroup(
            TodoPlatform.GRADER, "account", listOf(remote("early-beijing", "2026-11-07T16:15:00Z")),
        )
        val board = buildHomeworkBoard(
            listOf(personal("later-local", "2026-11-07", "17:00")),
            listOf(group),
            ZoneId.of("UTC"),
            now,
        )

        assertEquals(listOf("early-beijing", "later-local"), board.pending.map { it.key.id })
        assertEquals("2026-11-07", board.pending.first().date)
        assertEquals("2026-11-08 00:15", board.pending.first().deadlineText)
    }

    @Test
    fun `manual completion and restoration apply the same date order across all sources`() {
        val earlier = remote("earlier", "2026-11-07T18:00:00Z", completed = true)
        val newer = remote("newer", "2026-11-07T20:00:00Z", completed = true)
        val grader = ImportedTodoGroup(TodoPlatform.GRADER, "grader-user", listOf(earlier))
        val genius = ImportedTodoGroup(TodoPlatform.GENIUSTA, "genius-user", listOf(newer))
        val local = listOf(personal("legacy", null, completed = true), personal("pending", date))

        val board = buildHomeworkBoard(local, listOf(genius, grader), zone, now)
        assertEquals(listOf("pending"), board.pending.map { it.key.id })
        assertEquals(listOf("earlier", "newer", "legacy"), board.completed.map { it.key.id })

        val restored = buildHomeworkBoard(
            local, listOf(genius, grader.copy(tasks = listOf(earlier.copy(completed = false)))), zone, now,
        )
        assertEquals(listOf("earlier", "pending"), restored.pending.map { it.key.id })
        assertEquals(listOf("newer", "legacy"), restored.completed.map { it.key.id })
    }

    @Test
    fun `expired imports never enter either section but old personal todos remain`() {
        val pendingExpiry = remote("grader-at-deadline", "2026-11-07T16:00:00Z")
        val completedExpiry = remote("genius-next", "2026-11-07T16:00:00.001Z", completed = true)
        val oldPending = remote("old-pending", "2025-11-07T16:00:00Z")
        val oldCompleted = oldPending.copy(id = "old-completed", completed = true)
        val imports = listOf(
            ImportedTodoGroup(TodoPlatform.GRADER, "grader", listOf(pendingExpiry, oldPending, oldCompleted)),
            ImportedTodoGroup(TodoPlatform.GENIUSTA, "genius", listOf(completedExpiry, oldPending, oldCompleted)),
        )
        val local = listOf(personal("old-personal", "2025-11-08"), personal("old-personal-done", "2025-11-07", completed = true))

        val atDeadline = buildHomeworkBoard(local, imports, zone, now)
        assertEquals(listOf("old-personal", "grader-at-deadline"), atDeadline.pending.map { it.key.id })
        assertEquals(listOf("old-personal-done", "genius-next"), atDeadline.completed.map { it.key.id })
        assertEquals(now, atDeadline.nextImportedDeadline)

        val afterPendingExpiry = buildHomeworkBoard(local, imports, zone, now + 1)
        assertEquals(listOf("old-personal"), afterPendingExpiry.pending.map { it.key.id })
        assertEquals(mapOf("2025-11-08" to 1), afterPendingExpiry.pendingByDate)
        assertEquals(now + 1, afterPendingExpiry.nextImportedDeadline)

        val afterBothExpire = buildHomeworkBoard(local, imports, zone, now + 2)
        assertEquals(listOf("old-personal-done"), afterBothExpire.completed.map { it.key.id })
        assertEquals(null, afterBothExpire.nextImportedDeadline)
    }

    @Test
    fun `same instant retains personal storage order after a completion toggle`() {
        val items = listOf(personal("z", date), personal("a", date))
        val original = buildHomeworkBoard(items, emptyList(), zone, now)
        val toggled = buildHomeworkBoard(items.map { it.copy(completed = true) }, emptyList(), zone, now)
        assertEquals(listOf("z", "a"), original.pending.map { it.key.id })
        assertEquals(original.pending.map { it.key }, toggled.completed.map { it.key })
        assertTrue(toggled.pending.isEmpty())
    }
}
