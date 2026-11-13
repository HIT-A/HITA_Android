package cn.limpu.hita.ui.main.homework

import androidx.compose.runtime.Immutable
import cn.limpu.hita.data.model.homework.HomeworkItem

/**
 * 待办看板的派生结构：所有日期的待办常驻显示，按日期分组（未安排组永远在最后）。
 *
 * [completedGroups] 与 [groups] 同序但只含已完成项，由 UI 收进一个可整体折叠的区块。
 * 这里只产出不可变的排序结果，日期文案由 UI 用 stringResource 渲染，
 * 因此分组语义可以在 JVM 单测里直接断言。
 */
@Immutable
internal data class HomeworkBoard(
    val groups: List<HomeworkGroup>,
    val completedGroups: List<HomeworkGroup>,
    val pendingCount: Int,
    val completedCount: Int,
    val undatedCount: Int,
) {
    val totalCount: Int get() = pendingCount + completedCount
}

/** [date] 为 ISO 日期；null 表示历史遗留的未安排待办。 */
@Immutable
internal data class HomeworkGroup(
    val date: String?,
    val pending: List<HomeworkItem>,
    val completed: List<HomeworkItem>,
) {
    val isEmpty: Boolean get() = pending.isEmpty() && completed.isEmpty()
}

/**
 * 单次扫描把 [items] 分成「按日期升序、未安排置底」的两组序列。
 *
 * 排序键用 ISO 日期字符串本身：`yyyy-MM-dd` 的字典序等于时间序，且定宽，
 * 因此不需要为每个分组再解析一次 [java.time.LocalDate]。
 * 组内保持存储顺序（即添加顺序），完成或取消完成不会让条目在组内跳动。
 */
internal fun buildHomeworkBoard(items: List<HomeworkItem>): HomeworkBoard {
    if (items.isEmpty()) {
        return HomeworkBoard(emptyList(), emptyList(), 0, 0, 0)
    }
    val pending = LinkedHashMap<String?, MutableList<HomeworkItem>>()
    val completed = LinkedHashMap<String?, MutableList<HomeworkItem>>()
    var undated = 0
    items.forEach { item ->
        if (item.plannedDate == null) undated++
        val target = if (item.completed) completed else pending
        target.getOrPut(item.plannedDate) { ArrayList(2) }.add(item)
    }
    val dates = (pending.keys + completed.keys).distinct().sortedWith(DATE_ORDER)
    return HomeworkBoard(
        groups = dates.mapNotNull { date ->
            pending[date]?.let { HomeworkGroup(date, it, emptyList()) }
        },
        completedGroups = dates.mapNotNull { date ->
            completed[date]?.let { HomeworkGroup(date, emptyList(), it) }
        },
        pendingCount = pending.values.sumOf { it.size },
        completedCount = completed.values.sumOf { it.size },
        undatedCount = undated,
    )
}

/**
 * 把分组拆成「有日期的」与「未安排的」两段，避免 UI 到处判空。
 *
 * [dated] 的元素保证 [DatedHomeworkGroup.date] 非空，因此渲染标题时不必再断言。
 */
internal data class HomeworkGroupSplit(
    val dated: List<DatedHomeworkGroup>,
    val undated: HomeworkGroup?,
)

/** 已确认带有日期的分组。 */
internal data class DatedHomeworkGroup(
    val date: String,
    val pending: List<HomeworkItem>,
    val completed: List<HomeworkItem>,
)

internal fun List<HomeworkGroup>.splitUndated(): HomeworkGroupSplit =
    HomeworkGroupSplit(
        dated = mapNotNull { group ->
            val date = group.date ?: return@mapNotNull null
            DatedHomeworkGroup(date, group.pending, group.completed)
        },
        undated = lastOrNull()?.takeIf { it.date == null },
    )

/** ISO 日期升序，未安排（null）永远排在最后。 */
private val DATE_ORDER = Comparator<String?> { left, right ->
    when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }
}
