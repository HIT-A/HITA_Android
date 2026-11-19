package cn.limpu.hita.ui.main.homework

import androidx.compose.runtime.Immutable
import cn.limpu.hita.data.model.homework.HomeworkItem
import cn.limpu.hita.data.model.homework.ImportedTodo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class TodoPlatform { GRADER, GENIUSTA }

/** An imported action can only change the row if its source account is still connected. */
internal sealed interface TodoKey {
    val id: String
    val listKey: String

    data class Personal(override val id: String) : TodoKey {
        override val listKey: String get() = "personal:$id"
    }

    data class Imported(val platform: TodoPlatform, val accountId: String, override val id: String) : TodoKey {
        override val listKey: String get() = "$platform:$accountId:$id"
    }
}

internal data class ImportedTodoGroup(val platform: TodoPlatform, val accountId: String, val tasks: List<ImportedTodo>)

@Immutable
internal data class TodoRow(
    val key: TodoKey,
    val title: String,
    val course: String,
    val dueAt: Long?,
    val date: String?,
    val deadlineText: String?,
    val completed: Boolean,
    val personal: HomeworkItem? = null,
)

@Immutable
internal data class HomeworkBoard(
    val pending: List<TodoRow>,
    val completed: List<TodoRow>,
    val pendingByDate: Map<String, Int>,
    val nextImportedDeadline: Long?,
)

private val importedDeadlineFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    .withZone(ZoneId.of("Asia/Shanghai"))
private val deadlineOrder = compareBy<TodoRow, Long?>(nullsLast(naturalOrder())) { it.dueAt }

/** Filter expired imports before either section; compare the remaining rows by absolute deadline. */
internal fun buildHomeworkBoard(
    personal: List<HomeworkItem>,
    imported: List<ImportedTodoGroup>,
    zone: ZoneId,
    now: Long,
): HomeworkBoard {
    val pending = ArrayList<TodoRow>()
    val completed = ArrayList<TodoRow>()
    val counts = HashMap<String, Int>()
    var nextImportedDeadline: Long? = null
    fun add(row: TodoRow) {
        if (row.completed) {
            completed.add(row)
        } else {
            pending.add(row)
            row.date?.let { counts[it] = (counts[it] ?: 0) + 1 }
        }
    }
    for (item in personal) {
        val dueAt = item.plannedDate?.let { date ->
            LocalDate.parse(date).atTime(LocalTime.parse(item.plannedTime ?: "23:59"))
                .atZone(zone).toInstant().toEpochMilli()
        }
        add(TodoRow(
            TodoKey.Personal(item.id), item.title, item.subject, dueAt, item.plannedDate,
            item.deadlineText, item.completed, item,
        ))
    }
    for ((platform, accountId, tasks) in imported) {
        for (task in tasks) {
            if (task.dueAt < now) continue
            val previous = nextImportedDeadline
            if (previous == null || task.dueAt < previous) nextImportedDeadline = task.dueAt
            val deadline = Instant.ofEpochMilli(task.dueAt)
            add(TodoRow(
                TodoKey.Imported(platform, accountId, task.id), task.title, task.courseTitle, task.dueAt,
                deadline.atZone(zone).toLocalDate().toString(), importedDeadlineFormat.format(deadline), task.completed,
            ))
        }
    }
    pending.sortWith(deadlineOrder)
    completed.sortWith(deadlineOrder)
    return HomeworkBoard(pending, completed, counts, nextImportedDeadline)
}
