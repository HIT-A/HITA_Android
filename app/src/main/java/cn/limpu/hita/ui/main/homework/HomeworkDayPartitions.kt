package cn.limpu.hita.ui.main.homework

import androidx.compose.runtime.Immutable
import cn.limpu.hita.data.model.homework.HomeworkItem

/**
 * 某一天待办的派生分区。
 *
 * 待办列表每次重组都会各自 `filter`/`count` 一遍，键盘输入或时间刷新时重复扫描；
 * 这里把一次分区结果固化，既是稳定值也便于在 JVM 上验证分组语义。
 */
@Immutable
internal data class HomeworkDayPartitions(
    val pending: List<HomeworkItem>,
    val completed: List<HomeworkItem>,
    val unplannedCount: Int,
)

/** 纯函数版本，供单测与 remember 共用同一套语义。 */
internal fun partitionHomeworkDay(
    items: List<HomeworkItem>,
    dayFilter: (HomeworkItem) -> Boolean,
): HomeworkDayPartitions {
    val pending = ArrayList<HomeworkItem>(items.size)
    val completed = ArrayList<HomeworkItem>(items.size)
    items.forEach { item ->
        if (!dayFilter(item)) return@forEach
        if (item.completed) completed += item else pending += item
    }
    return HomeworkDayPartitions(
        pending = pending,
        completed = completed,
        unplannedCount = items.count { it.plannedDate == null },
    )
}
