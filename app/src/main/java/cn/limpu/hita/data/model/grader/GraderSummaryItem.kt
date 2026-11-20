package cn.limpu.hita.data.model.grader

/** Published current-account records, without credentials or historical-account caches. */
data class GraderSummaryItem(
    val accountId: String,
    val id: String,
    val title: String,
    val course: String,
    val dueAt: Long?,
    val completed: Boolean,
)
