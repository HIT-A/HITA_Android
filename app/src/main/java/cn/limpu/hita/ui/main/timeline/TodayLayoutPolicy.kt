package cn.limpu.hita.ui.main.timeline

internal enum class TodayLayoutMode { FIXED_FULL, FIXED_COMPACT, PAGE_SCROLL }

/** Geometry only: source failures, ticks and scope changes cannot switch the page layout. */
internal object TodayLayoutPolicy {
    /** 单行课程条的高度与间距；行高不足 [FULL_ROW_HEIGHT_DP] 时改用它们。 */
    const val DENSE_ROW_HEIGHT_DP = 44f
    const val DENSE_ROW_GAP_DP = 8f
    const val FULL_ROW_HEIGHT_DP = 108f

    fun choose(widthDp: Float, heightDp: Float, bottomClearanceDp: Float, fontScale: Float): TodayLayoutMode {
        val contentWidth = widthDp - 40f
        val minimumAgenda = 97f + 60f * fontScale
        // 问候块删除后列表上方只剩可选的紧急提醒行，按 24dp 预留。
        val fullAgenda = heightDp - bottomClearanceDp - 24f - summariesHeight(false, fontScale) - 48f
        if (fontScale <= 1.25f && contentWidth >= 320f && fullAgenda >= minimumAgenda) return TodayLayoutMode.FIXED_FULL
        val compactAgenda = heightDp - bottomClearanceDp - 24f - summariesHeight(true, fontScale) - 48f
        return if (fontScale <= 1.6f && contentWidth >= 280f && compactAgenda >= minimumAgenda)
            TodayLayoutMode.FIXED_COMPACT else TodayLayoutMode.PAGE_SCROLL
    }

    /** 单张摘要卡的高度：上排标签 + 最多三行内容。 */
    fun summaryCardHeight(compact: Boolean, fontScale: Float): Float =
        if (compact) 24f + 40f * fontScale else 40f + 64f * fontScale

    /** 两张摘要卡上下排列时占用的总高度。 */
    fun summariesHeight(compact: Boolean, fontScale: Float): Float =
        summaryCardHeight(compact, fontScale) * 2 + 12f

    /** 列表每行分到的高度；一天课程多时用它决定要不要换成单行课程条。 */
    fun agendaRowHeight(heightDp: Float, bottomClearanceDp: Float, compact: Boolean, fontScale: Float, count: Int): Float {
        if (count <= 0) return Float.MAX_VALUE
        val available = heightDp - bottomClearanceDp - summariesHeight(compact, fontScale) - 24f
        return (available - (count - 1) * 12f) / count
    }

    fun agendaRowsAreDense(heightDp: Float, bottomClearanceDp: Float, compact: Boolean, fontScale: Float, count: Int): Boolean =
        agendaRowHeight(heightDp, bottomClearanceDp, compact, fontScale, count) < FULL_ROW_HEIGHT_DP

    /** 换成单行课程条后六节课占用的总高度，用来验证首屏不用滚动。 */
    fun denseAgendaHeight(count: Int): Float =
        count * DENSE_ROW_HEIGHT_DP + (count - 1) * DENSE_ROW_GAP_DP
}
