package cn.limpu.hita.data.model.homework

data class HomeworkItem(
    val id: String,
    val title: String,
    val createdAt: Long,
    val completed: Boolean = false,
    /** Local calendar date (ISO yyyy-MM-dd); null preserves undated legacy tasks. */
    val plannedDate: String? = null,
    /** Local HH:mm; dated legacy tasks without this field are due at 23:59. */
    val plannedTime: String? = null,
) {
    val deadlineText: String?
        get() = plannedDate?.let { "$it ${plannedTime ?: "23:59"}" }
}
