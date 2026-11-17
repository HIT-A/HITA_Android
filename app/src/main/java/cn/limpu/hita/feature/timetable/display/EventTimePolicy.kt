package cn.limpu.hita.feature.timetable.display

import cn.limpu.hita.data.model.timetable.EventItem

/** Shared half-open time semantics. No system clock or Android/UI dependency. */
object EventTimePolicy {
    enum class Phase { INVALID, UPCOMING, ACTIVE, ENDED }

    val eventOrder: Comparator<EventItem> = compareBy<EventItem> { it.from.time }
        .thenBy { it.to.time }.thenBy { it.id }

    fun phase(start: Long, end: Long, now: Long): Phase = when {
        end <= start -> Phase.INVALID
        end <= now -> Phase.ENDED
        start <= now -> Phase.ACTIVE
        else -> Phase.UPCOMING
    }

    fun progress(start: Long, end: Long, now: Long): Float {
        if (end <= start) return 0f
        return ((now.toDouble() - start.toDouble()) / (end.toDouble() - start.toDouble()))
            .coerceIn(0.0, 1.0).toFloat()
    }

    /** Positive fractions round up; 20 seconds remaining must not read as 0 minutes. */
    fun remainingMinutes(boundary: Long, now: Long): Long {
        if (boundary <= now) return 0
        return kotlin.math.ceil((boundary.toDouble() - now.toDouble()) / 60_000.0).toLong()
    }
}
