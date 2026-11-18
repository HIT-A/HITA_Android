package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetable.display.EventTimePolicy
import cn.limpu.hita.feature.timetable.display.EventTimePolicy.Phase

object TodayProjection {
    fun project(window: TodayDateWindow, events: List<EventItem>, now: Long): TodayAgendaState {
        val real = events.asSequence().filter { it.type != EventItem.TYPE.TAG && it.hidden == 0 }
            .sortedWith(EventTimePolicy.eventOrder).distinctBy { it.id }.toList()
        val today = real.filter { belongsTo(it, window.start, window.tomorrowStart) }
        val invalid = today.filter { phase(it, now) == Phase.INVALID }
        val valid = today.filter { phase(it, now) != Phase.INVALID }
        val active = valid.filter { phase(it, now) == Phase.ACTIVE }
        val upcoming = valid.filter { phase(it, now) == Phase.UPCOMING }
        val next = upcoming.firstOrNull()?.from?.time?.let { start ->
            upcoming.takeWhile { it.from.time == start }
        }.orEmpty()
        val allFinished = valid.all { phase(it, now) == Phase.ENDED }
        return TodayAgendaState(
            allToday = today,
            active = active,
            nextGroup = next,
            ended = valid.filter { phase(it, now) == Phase.ENDED },
            // All future items and invalid intervals remain visible; nextGroup is only for the greeting.
            remaining = today.filter { phase(it, now) != Phase.ENDED },
            completedCount = valid.count { phase(it, now) == Phase.ENDED },
            invalidEvents = invalid,
            conflictGroups = conflicts(valid),
            allFinished = allFinished,
        )
    }

    private fun phase(event: EventItem, now: Long) = EventTimePolicy.phase(event.from.time, event.to.time, now)

    private fun belongsTo(event: EventItem, start: Long, end: Long): Boolean =
        if (event.to.time <= event.from.time) event.from.time >= start && event.from.time < end
        else event.from.time < end && event.to.time > start

    private fun conflicts(events: List<EventItem>): List<List<EventItem>> {
        val result = mutableListOf<List<EventItem>>()
        var group = mutableListOf<EventItem>()
        var end = Long.MIN_VALUE
        for (event in events) {
            if (event.from.time >= end) {
                if (group.size > 1) result += group.toList()
                group = mutableListOf()
            }
            group += event
            end = if (group.size == 1) event.to.time else maxOf(end, event.to.time)
        }
        if (group.size > 1) result += group.toList()
        return result
    }
}
