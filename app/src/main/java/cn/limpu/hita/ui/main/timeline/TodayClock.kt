package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem

/** Next instant that can change what the page shows: the next minute boundary or an event edge. */
object TodayClock {
    fun nextWake(now: Long, window: TodayDateWindow, events: List<EventItem>): Long {
        val boundaries = sequenceOf(now - Math.floorMod(now, 60_000L) + 60_000L, window.preparationStart, window.tomorrowStart) +
            events.asSequence().filter { it.type != EventItem.TYPE.TAG && it.to.time > it.from.time }
                .flatMap { sequenceOf(it.from.time, it.to.time) }
        return boundaries.filter { it > now }.minOrNull() ?: (now + 60_000L)
    }
}
