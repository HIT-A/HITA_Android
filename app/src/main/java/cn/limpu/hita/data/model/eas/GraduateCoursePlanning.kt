package cn.limpu.hita.data.model.eas

/**
 * 威海研究生教务的课程/选课任务行。
 *
 * 这些数据仅用于只读浏览、本地选课预览与跟课；参考端尚未提供研究生远端提交契约，
 * 因此不包含任何提交授权字段。
 */
data class GraduateCourseRow(
    val taskNumber: String,
    val requestId: String,
    val courseCode: String,
    val courseName: String,
    val teachers: List<String> = emptyList(),
    val credits: String = "",
    val totalHours: String = "",
    val nature: String = "",
    val category: String = "",
    val college: String = "",
    val campus: String = "",
    val classNumber: String = "",
    val trainingLevel: String = "",
    val capacity: Int? = null,
    val selectedCount: Int? = null,
    val selectionMode: String = "",
    val selectionModeName: String = "",
    val note: String = "",
    val meetings: List<ShenzhenCourseMeeting> = emptyList(),
)

/** 选课辅助的服务端动态类别（`xkgzszList`），不能由深圳静态课程池替代。 */
data class GraduateSelectionCategory(val id: String, val name: String)

data class GraduateSelectionPage(
    val rows: List<GraduateCourseRow>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
) {
    val hasMore: Boolean get() = rows.isNotEmpty() && page * pageSize < total
}

/** 指定学期已选课程及其可选类别。 */
data class GraduateSelectedCourses(
    val rows: List<GraduateCourseRow>,
    val categories: List<GraduateSelectionCategory>,
)

data class GraduateFilterOption(val id: String, val name: String)

/** 全校研究生课表的筛选数据：节次名称、周次、校区、开课院系。 */
data class GraduateFilterOptions(
    val periods: List<String> = emptyList(),
    val weeks: List<Int> = emptyList(),
    val campuses: List<GraduateFilterOption> = emptyList(),
    val colleges: List<GraduateFilterOption> = emptyList(),
)
