package cn.limpu.hita.ui.main

import cn.limpu.hita.data.model.timetable.EventItem

/**
 * 首页「迷你课程条」的状态解析。
 *
 * 只回答一个问题：以 [now] 为准，当前应该显示哪一节课、处于什么阶段。
 * 纯函数，不取系统时间也不查数据库，便于在 JVM 上覆盖各种边界。
 */
enum class MiniCoursePhase {
    /** 正在上课。 */
    ONGOING,

    /** 15 分钟内开始下一节。 */
    UPCOMING,

    /** 今天已经没有后续课程。 */
    DONE,

    /** 当天完全没有课。 */
    IDLE,
}

data class MiniCourseState(
    val phase: MiniCoursePhase,
    val title: String = "",
    val place: String = "",
    val timeText: String = "",
    /** 0f..1f，仅 [MiniCoursePhase.ONGOING] 有意义。 */
    val progress: Float = 0f,
)

object MiniCourseResolver {

    /** 「即将开始」的窗口：iOS 用 15 分钟，与课程提醒的最小档位一致。 */
    const val UPCOMING_WINDOW_MILLIS = 15 * 60 * 1000L

    /**
     * @param events 当天该显示范围内的事件
     * @param now 当前时间戳（毫秒）
     */
    fun resolve(events: List<EventItem>, now: Long): MiniCourseState {
        // 只保留上课类事件：考试与手动备忘不是「正在上/即将上」的语义。
        val classes = events
            .filter { it.type == EventItem.TYPE.CLASS }
            .sortedBy { it.from.time }

        val ongoing = classes.firstOrNull { now >= it.from.time && now < it.to.time }
        if (ongoing != null) {
            val span = (ongoing.to.time - ongoing.from.time).coerceAtLeast(1L)
            val progress = ((now - ongoing.from.time).toFloat() / span).coerceIn(0f, 1f)
            return MiniCourseState(
                phase = MiniCoursePhase.ONGOING,
                title = ongoing.name,
                place = ongoing.place.orEmpty(),
                timeText = timeText(ongoing),
                progress = progress,
            )
        }

        val next = classes.firstOrNull { it.from.time > now }
        return when {
            next == null -> MiniCourseState(
                phase = if (classes.isEmpty()) MiniCoursePhase.IDLE else MiniCoursePhase.DONE,
            )
            next.from.time - now <= UPCOMING_WINDOW_MILLIS -> MiniCourseState(
                phase = MiniCoursePhase.UPCOMING,
                title = next.name,
                place = next.place.orEmpty(),
                timeText = timeText(next),
            )
            // 今天还有课，但还没进入窗口：不占用首页横条，避免长期显示无意义信息。
            else -> MiniCourseState(phase = MiniCoursePhase.DONE)
        }
    }

    private fun timeText(event: EventItem): String {
        val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        return "${formatter.format(event.from)}-${formatter.format(event.to)}"
    }
}
