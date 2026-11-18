package cn.limpu.hita.ui.main.timeline

internal enum class TodayLayoutMode { FIXED_FULL, FIXED_COMPACT, PAGE_SCROLL }

/** Geometry only: source failures, ticks and scope changes cannot switch the page layout. */
internal object TodayLayoutPolicy {
    fun choose(widthDp: Float, heightDp: Float, bottomClearanceDp: Float, fontScale: Float): TodayLayoutMode {
        val contentWidth = widthDp - 40f
        val minimumAgenda = 97f + 60f * fontScale
        val fullAgenda = heightDp - bottomClearanceDp - (52f + 62f * fontScale) - (56f + 82f * fontScale) - 48f
        if (fontScale <= 1.25f && contentWidth >= 320f && fullAgenda >= minimumAgenda) return TodayLayoutMode.FIXED_FULL
        val compactAgenda = heightDp - bottomClearanceDp - (30f + 52f * fontScale) - (28f + 40f * fontScale) - 48f
        return if (fontScale <= 1.6f && contentWidth >= 280f && compactAgenda >= minimumAgenda)
            TodayLayoutMode.FIXED_COMPACT else TodayLayoutMode.PAGE_SCROLL
    }
}
