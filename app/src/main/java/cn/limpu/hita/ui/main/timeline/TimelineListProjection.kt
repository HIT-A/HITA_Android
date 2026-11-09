package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.utils.SpecialEventReminderUtils

/**
 * 时间线渲染用的派生视图模型。
 *
 * 这里只放纯函数：输入事件列表和"现在"的时刻，输出每一行要显示的状态。
 * 组合函数不再自行读取系统时钟、不再做 O(n) 索引扫描，因此这些判断可以被单测覆盖，
 * 也能在任何状态刷新时得到一致结果。
 */
internal data class TimelineRowState(
    val isPassed: Boolean,
    val isUpcomingExam: Boolean,
    val isNow: Boolean,
    val isFirstTimeline: Boolean,
    val isLastTimeline: Boolean,
    val progress: Float,
)

internal object TimelineListProjection {
    /** 时间线里第一条/最后一条真实日程的下标；全为提示卡时返回 -1。 */
    fun timelineBounds(events: List<EventItem>): Pair<Int, Int> {
        var first = -1
        var last = -1
        events.forEachIndexed { index, event ->
            if (event.type != EventItem.TYPE.TAG) {
                if (first == -1) first = index
                last = index
            }
        }
        return first to last
    }

    /**
     * 单行状态。now 由调用方传入（来自 ticker），保证"进行中/即将开始"随刷新推进，
     * 而不是在一次组合里被冻结成常量。
     */
    fun rowState(
        event: EventItem,
        index: Int,
        bounds: Pair<Int, Int>,
        now: Long,
    ): TimelineRowState {
        val isUpcomingExam = SpecialEventReminderUtils.isExamEvent(event) && event.from.time > now
        val isNow = event.containsTimeStamp(now)
        return TimelineRowState(
            isPassed = event.to.time < now,
            isUpcomingExam = isUpcomingExam,
            isNow = isNow,
            isFirstTimeline = index == bounds.first,
            isLastTimeline = index == bounds.second,
            progress = if (isNow) event.getProgress(now) else 0f,
        )
    }
}
