package cn.limpu.hita.data.model.campus

/**
 * 实验中心个人课表里的一节课。
 *
 * [id] 用于列表 key：同一条排课在整学期里会按周拆成多节，因此必须把周次算进去，
 * 否则 LazyColumn 会复用错误的行。
 */
data class LabSessionItem(
    val id: String,
    val courseName: String,
    val week: Int,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val room: String?,
)

/**
 * 本学期实验课进度。
 *
 * [currentWeek] 为 null 表示课表页没有给出选中的教学周，此时无法判断「已上」，
 * 页面只能展示总场次，不能拿 0 冒充。
 */
data class LabCourseProgress(
    val termName: String?,
    val currentWeek: Int?,
    val sessions: List<LabSessionItem>,
    val fetchedAtMillis: Long,
) {
    val totalCount: Int get() = sessions.size

    /** 已经上过的场次：周次更早，或同一周里星期更早。 */
    fun completedCount(currentWeek: Int, weekday: Int): Int =
        sessions.count { session ->
            session.week < currentWeek ||
                (session.week == currentWeek && session.weekday < weekday)
        }
}
