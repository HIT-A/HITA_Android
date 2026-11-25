package cn.limpu.hita.ui.main.blog

/**
 * 资讯系列的展开判定。
 *
 * 优先级由高到低：用户手动展开 → 用户手动收起 → 该行默认（根系列看未读，子系列默认展开）。
 * 稳定的文章 ID 是唯一键，手动操作会跨重组、旋转和滚动保留。
 */
internal object BlogExpansionPolicy {

    fun isExpanded(
        id: String,
        isSeries: Boolean,
        autoExpand: Boolean,
        expandedIds: Set<String>,
        collapsedIds: Set<String>,
    ): Boolean = when {
        !isSeries -> false
        id in expandedIds -> true
        id in collapsedIds -> false
        else -> autoExpand
    }

    /** 返回更新后的 (expandedIds, collapsedIds)；同一 ID 只会出现在一侧。 */
    fun toggle(
        id: String,
        expand: Boolean,
        expandedIds: Set<String>,
        collapsedIds: Set<String>,
    ): Pair<Set<String>, Set<String>> = if (expand) {
        (expandedIds + id) to (collapsedIds - id)
    } else {
        (expandedIds - id) to (collapsedIds + id)
    }
}
