package cn.limpu.hita.data.model.campus

import java.util.Locale

/**
 * 服务端给的可用窗口，`startMinutes` / `endMinutes` 都是「当天第几分钟」。
 *
 * 刻意不用 `IntRange`：`start until end` 的 `last` 是 `end - 1`，
 * 很容易把结束时间少算一分钟；这里两端显式命名，避免踩坑。
 */
data class SilentPodWindow(
    val startMinutes: Int,
    val endMinutes: Int,
) {
    val durationMinutes: Int get() = endMinutes - startMinutes

    val startText: String get() = SilentPodSlotPlanner.formatMinutes(startMinutes)

    val endText: String get() = SilentPodSlotPlanner.formatMinutes(endMinutes)

    val label: String get() = "$startText-$endText"
}

/**
 * 把「可约窗口」换算成可提交的「开始 / 结束」选项。
 *
 * 事实依据（接口文档 §6.2 / §6.4）：
 * - `allowUseDurationList` 是当日可用**区间**，不是预约时段本身；
 * - `gtDurationMinutes` / `ltDurationMinutes` 是最小 / 最大可预约时长；
 * - 选时步长 **10 分钟**，`startTime` 必须仍在未来，否则服务端返回
 *   `INVALID_FIELD`（预约时间异常）。
 *
 * 这里只做纯计算，不碰网络与 UI，便于单测覆盖边界。
 */
object SilentPodSlotPlanner {

    /** 选时步长：与图书馆网页一致。 */
    const val STEP_MINUTES = 10

    /** 默认时长：与网页一致，优先给 60 分钟，可选不到就退到最短可选。 */
    const val PREFERRED_DURATION_MINUTES = 60

    private const val MINUTES_PER_DAY = 24 * 60

    private val WINDOW = Regex("""^(\d{2}):(\d{2})-(\d{2}):(\d{2})$""")

    /**
     * 解析 `HH:mm-HH:mm` 窗口。
     *
     * 格式不符、零长度或跨天一律返回 `null`：这类数据宁可不展示，
     * 也不要拼出一个必然被服务端拒绝的时段。
     */
    fun parseWindow(raw: String): SilentPodWindow? {
        val match = WINDOW.matchEntire(raw.trim()) ?: return null
        val start = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        val end = match.groupValues[3].toInt() * 60 + match.groupValues[4].toInt()
        if (start >= end || end > MINUTES_PER_DAY) return null
        return SilentPodWindow(start, end)
    }

    /** 把分钟数向上取整到步长；已在步长点上则原样返回。 */
    fun ceilToStep(minutes: Int, step: Int = STEP_MINUTES): Int {
        if (step <= 0 || minutes % step == 0) return minutes
        return minutes + step - minutes % step
    }

    /** 最小时长；缺字段时单人仓 20 分钟、多人仓 30 分钟。 */
    fun minDuration(space: SilentPodSpace): Int =
        space.minDurationMinutes
            ?: if (space.category == SilentPodCategory.SINGLE) 20 else 30

    /** 最大时长；缺字段时单人仓 60 分钟、多人仓 120 分钟。 */
    fun maxDuration(space: SilentPodSpace): Int =
        space.maxDurationMinutes
            ?: if (space.category == SilentPodCategory.SINGLE) 60 else 120

    /**
     * 窗口与「现在」取交集后、剩余时长仍够最短预约的那些区间。
     *
     * 列表用这个而不是原始窗口：原始窗口的起点常常就是当前分钟，
     * 直接展示会让人以为「现在还能从窗口起点约」。
     */
    fun effectiveWindows(
        space: SilentPodSpace,
        nowMinutes: Int,
        step: Int = STEP_MINUTES,
    ): List<SilentPodWindow> {
        val minDuration = minDuration(space)
        val earliestStart = ceilToStep(nowMinutes, step)
        return space.allowDurations.mapNotNull { raw ->
            val window = parseWindow(raw) ?: return@mapNotNull null
            val start = maxOf(window.startMinutes, earliestStart)
            if (window.endMinutes - start < minDuration) return@mapNotNull null
            SilentPodWindow(start, window.endMinutes)
        }.sortedBy { it.startMinutes }
    }

    /**
     * 可选的开始时间，按窗口顺序、步进 10 分钟。
     *
     * 最后一个开始时间要保证「还能约到最短时长」，因此上限是 `窗口终点 - 最小时长`。
     */
    fun startOptions(
        space: SilentPodSpace,
        nowMinutes: Int,
        step: Int = STEP_MINUTES,
    ): List<Int> {
        val minDuration = minDuration(space)
        val options = LinkedHashSet<Int>()
        effectiveWindows(space, nowMinutes, step).forEach { window ->
            val lastStart = window.endMinutes - minDuration
            var minute = window.startMinutes
            while (minute <= lastStart) {
                options += minute
                minute += step
            }
        }
        return options.sorted()
    }

    /**
     * 给定开始时间可选的结束时间。
     *
     * 下限是 `开始 + 最小时长`，上限是 `min(窗口终点, 开始 + 最大时长)`；
     * 开始时间不在任何窗口里时返回空列表。
     */
    fun endOptions(
        space: SilentPodSpace,
        startMinutes: Int,
        step: Int = STEP_MINUTES,
    ): List<Int> {
        val minDuration = minDuration(space)
        val maxDuration = maxDuration(space)
        val window = space.allowDurations
            .mapNotNull(::parseWindow)
            .firstOrNull { startMinutes >= it.startMinutes && startMinutes < it.endMinutes }
            ?: return emptyList()
        val minEnd = startMinutes + minDuration
        val maxEnd = minOf(window.endMinutes, startMinutes + maxDuration)
        if (minEnd > maxEnd) return emptyList()
        val options = ArrayList<Int>()
        var minute = minEnd
        while (minute <= maxEnd) {
            options += minute
            minute += step
        }
        return options
    }

    /** 默认结束时间：优先 60 分钟，选不到就退到最短可选。 */
    fun defaultEnd(startMinutes: Int?, endOptions: List<Int>): Int? {
        if (startMinutes == null) return endOptions.firstOrNull()
        return endOptions.firstOrNull { it - startMinutes >= PREFERRED_DURATION_MINUTES }
            ?: endOptions.firstOrNull()
    }

    /**
     * 提交前的最终校验：时长在上下限内、整段落在同一个窗口、开始时间还没过去。
     *
     * 与 [startOptions] / [endOptions] 的区别是这里以「提交那一刻」为准，
     * 对话框开着很久之后必须重算一次。
     */
    fun isValidSelection(
        space: SilentPodSpace,
        startMinutes: Int,
        endMinutes: Int,
        nowMinutes: Int,
        step: Int = STEP_MINUTES,
    ): Boolean {
        if (endMinutes <= startMinutes) return false
        if (startMinutes < ceilToStep(nowMinutes, step)) return false
        val duration = endMinutes - startMinutes
        if (duration < minDuration(space) || duration > maxDuration(space)) return false
        return space.allowDurations.mapNotNull(::parseWindow).any { window ->
            startMinutes >= window.startMinutes && endMinutes <= window.endMinutes
        }
    }

    /** `95` → `01:35`；越界值截断到一天之内，避免展示 `25:00` 这种时间。 */
    fun formatMinutes(minutes: Int): String {
        val clamped = minutes.coerceIn(0, MINUTES_PER_DAY)
        return String.format(Locale.ROOT, "%02d:%02d", clamped / 60, clamped % 60)
    }
}
