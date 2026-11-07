package cn.limpu.hita.data.model.grader

/** dueAt is an absolute UTC epoch millisecond, never a local date. */
data class GraderTask(
    val id: String,
    val title: String,
    val courseTitle: String,
    val dueAt: Long,
    val completed: Boolean = false,
)
