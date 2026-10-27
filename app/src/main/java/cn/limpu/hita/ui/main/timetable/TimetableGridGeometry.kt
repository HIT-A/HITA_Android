package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay

/** Neutral coordinates. Callers choose pixels or dp consistently; no density, repository or host. */
internal object TimetableGridGeometry {
    data class Card(val x: Float, val y: Float, val width: Float, val height: Float)

    fun card(day: Int, startMinute: Int, durationMinutes: Int, startHour: Int, columnWidth: Float, unitsPerMinute: Float): Card = Card(
        x = columnWidth * (day.coerceIn(1, 7) - 1) + 2f,
        y = (startMinute - startHour * 60).coerceAtLeast(0) * unitsPerMinute.coerceAtLeast(0f),
        width = (columnWidth - 4f).coerceAtLeast(0f),
        height = durationMinutes.coerceAtLeast(15) * unitsPerMinute.coerceAtLeast(0f),
    )

    fun dayAt(x: Float, width: Float): Int? = if (width <= 0f || !width.isFinite() || !x.isFinite()) null
        else ((x / (width / 7f)).toInt() + 1).coerceIn(1, 7)

    fun periodAt(y: Float, startHour: Int, endHour: Int, unitsPerMinute: Float, schedule: List<TimePeriodInDay>): TimePeriodInDay? {
        if (unitsPerMinute <= 0f || !unitsPerMinute.isFinite() || !y.isFinite() || y < 0f) return null
        val minutes = startHour * 60 + (y / unitsPerMinute).toInt()
        if (minutes > endHour * 60) return null
        val time = TimeInDay(minutes / 60, minutes % 60)
        for (i in schedule.indices) {
            val period = schedule[i]
            if (i == 0 && period.after(time)) return TimePeriodInDay(TimeInDay(startHour, 0), period.from)
            if (period.contains(time)) return period.clone()
            if (i + 1 < schedule.size && schedule[i + 1].after(time)) return TimePeriodInDay(period.to, schedule[i + 1].from)
            if (i == schedule.lastIndex && period.before(time)) return TimePeriodInDay(period.to, TimeInDay(endHour, 0))
        }
        return null
    }

    fun compressedScale(usableHeight: Float, totalMinutes: Int): Float? =
        if (totalMinutes <= 0 || !usableHeight.isFinite()) null else (usableHeight / totalMinutes).coerceAtLeast(0.12f)

    fun labelStep(unitsPerMinute: Float): Int = if (unitsPerMinute <= 0f || !unitsPerMinute.isFinite()) 1
        else (40f / (60f * unitsPerMinute)).toInt().coerceAtLeast(1)

    /** One release chooses at most one direction; exact threshold snaps back. */
    fun swipe(offset: Float, width: Float): Int = when {
        width <= 0f || !width.isFinite() || !offset.isFinite() -> 0
        offset > width * 0.15f -> -1
        offset < -width * 0.15f -> 1
        else -> 0
    }

    fun eveningHint(enabled: Boolean, hasEveningEvents: Boolean, scroll: Int, maxScroll: Int, hideThreshold: Float): Boolean =
        enabled && hasEveningEvents && maxScroll > 0 && scroll < maxScroll - hideThreshold
}
