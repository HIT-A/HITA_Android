package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Display-only formatting; event classification continues to use EventTimePolicy. */
internal fun todayEventTime(event: EventItem, window: TodayDateWindow, day: LocalDate = window.date): String {
    val from = event.from.toInstant().atZone(window.zoneId)
    val to = event.to.toInstant().atZone(window.zoneId)
    val pattern = when {
        from.year != day.year || to.year != day.year -> "yyyy-MM-dd HH:mm"
        from.toLocalDate() != to.toLocalDate() || from.toLocalDate() != day -> "MM-dd HH:mm"
        else -> "HH:mm"
    }
    val format = DateTimeFormatter.ofPattern(pattern)
    return "${from.format(format)}–${to.format(format)}"
}
