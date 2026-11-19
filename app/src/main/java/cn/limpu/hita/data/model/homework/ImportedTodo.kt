package cn.limpu.hita.data.model.homework

/** A platform's published, unsubmitted item. Completion is local; the deadline is an epoch millisecond. */
data class ImportedTodo(
    val id: String,
    val title: String,
    val courseTitle: String,
    val dueAt: Long,
    val completed: Boolean,
)
