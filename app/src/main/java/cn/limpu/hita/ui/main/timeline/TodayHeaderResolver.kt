package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetable.display.EventTimePolicy
import java.time.Instant
import java.time.ZoneId

enum class TodayGreeting { EARLY, MORNING, AFTERNOON, EVENING, LUNCH, DINNER }
enum class TodayFocus { NONE, ACTIVE, UPCOMING, FINISHED, EMPTY }

data class TodayHeaderState(
    val greeting: TodayGreeting = TodayGreeting.MORNING,
    val focus: TodayFocus = TodayFocus.NONE,
    val title: String? = null,
    val type: EventItem.TYPE? = null,
    val count: Int = 0,
    val remainingMinutes: Long? = null,
    val progress: Float? = null,
)

object TodayHeaderResolver {
    /** Schedule pairs are local minutes. Only identical member schedules may imply meal gaps. */
    fun resolve(now: Long, zone: ZoneId, agenda: TodayAgendaState?, schedules: List<List<Pair<Int, Int>>>): TodayHeaderState {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val minute = local.hour * 60 + local.minute
        val shared = schedules.firstOrNull()?.takeIf { schedule -> schedules.all { it == schedule } }
        val gaps = shared.orEmpty().sortedBy { it.first }.zipWithNext { a, b -> a.second to b.first }
        val meal = gaps.firstOrNull { (end, start) -> start - end >= 30 && minute in end until start &&
            ((end in 660..810 && start in 720..900) || (end in 960..1110 && start in 1080..1200)) }
        val greeting = when {
            meal != null && meal.first < 900 -> TodayGreeting.LUNCH
            meal != null -> TodayGreeting.DINNER
            minute < 360 -> TodayGreeting.EARLY
            minute < 720 -> TodayGreeting.MORNING
            minute < 1080 -> TodayGreeting.AFTERNOON
            else -> TodayGreeting.EVENING
        }
        if (agenda == null) return TodayHeaderState(greeting = greeting)
        val group = agenda.active.ifEmpty { agenda.nextGroup }
        val focus = when {
            agenda.active.isNotEmpty() -> TodayFocus.ACTIVE
            agenda.nextGroup.isNotEmpty() -> TodayFocus.UPCOMING
            agenda.allToday.isEmpty() -> TodayFocus.EMPTY
            agenda.allFinished -> TodayFocus.FINISHED
            else -> TodayFocus.NONE
        }
        val single = group.singleOrNull()
        val boundary = single?.let { if (focus == TodayFocus.ACTIVE) it.to.time else it.from.time }
        return TodayHeaderState(greeting, focus, single?.name, single?.type, group.size,
            boundary?.let { EventTimePolicy.remainingMinutes(it, now) },
            single?.takeIf { focus == TodayFocus.ACTIVE }?.let { EventTimePolicy.progress(it.from.time, it.to.time, now) })
    }
}

object TodayClock {
    fun nextWake(now: Long, window: TodayDateWindow, events: List<EventItem>): Long {
        val boundaries = sequenceOf(now - Math.floorMod(now, 60_000L) + 60_000L, window.preparationStart, window.tomorrowStart) +
            events.asSequence().filter { it.type != EventItem.TYPE.TAG && it.to.time > it.from.time }
                .flatMap { sequenceOf(it.from.time, it.to.time) }
        return boundaries.filter { it > now }.minOrNull() ?: (now + 60_000L)
    }
}
