package cn.limpu.hita.data.model.homework

/** 作业的提交方式；`UNKNOWN` 兼容旧快照与尚未填写的作业。 */
enum class HomeworkSubmissionMethod {
    UNKNOWN,
    ONLINE,
    PAPER,
    EMAIL,
    ;

    companion object {
        fun fromStorage(raw: String?): HomeworkSubmissionMethod =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/** 作业的提交格式；`UNKNOWN` 表示没写明。 */
enum class HomeworkSubmissionFormat {
    UNKNOWN,
    DOCUMENT,
    PDF,
    HANDWRITTEN,
    PRESENTATION,
    ;

    companion object {
        fun fromStorage(raw: String?): HomeworkSubmissionFormat =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

data class HomeworkItem(
    val id: String,
    val title: String,
    val createdAt: Long,
    val completed: Boolean = false,
    /** Local calendar date (ISO yyyy-MM-dd); null preserves undated legacy tasks. */
    val plannedDate: String? = null,
    /** Local HH:mm; dated legacy tasks without this field are due at 23:59. */
    val plannedTime: String? = null,
    /** 课程名；旧快照没有该字段，读出来是空串。 */
    val subject: String = "",
    val submissionMethod: HomeworkSubmissionMethod = HomeworkSubmissionMethod.UNKNOWN,
    /** 提交入口/邮箱/纸质说明等自由文本。 */
    val submissionDetail: String = "",
    val submissionFormat: HomeworkSubmissionFormat = HomeworkSubmissionFormat.UNKNOWN,
) {
    val deadlineText: String?
        get() = plannedDate?.let { "$it ${plannedTime ?: "23:59"}" }

    /** 至少有一项提交信息才值得在列表里展示，否则不占版面。 */
    val hasSubmissionInfo: Boolean
        get() = submissionMethod != HomeworkSubmissionMethod.UNKNOWN ||
            submissionFormat != HomeworkSubmissionFormat.UNKNOWN ||
            submissionDetail.isNotBlank()
}
