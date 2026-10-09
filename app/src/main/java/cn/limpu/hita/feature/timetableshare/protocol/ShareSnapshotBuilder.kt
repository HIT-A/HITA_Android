package cn.limpu.hita.feature.timetableshare.protocol

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.Timetable

/** Reads only the selected personal timetable. Call within the repository's read transaction. */
class ShareSnapshotBuilder {
    fun build(timetable: Timetable, events: List<EventItem>, shareId: String, nickname: String, mode: ShareMode): SharedTimetable {
        val selected = events.filter { it.timetableId == timetable.id && it.type == EventItem.TYPE.CLASS }
        if (selected.isEmpty()) throw ShareFormatException(ShareError.EMPTY_SCHEDULE)
        if (selected.size > ShareLimits.OCCURRENCES) invalidFields()
        val term = ShareTermResolver.resolve(timetable.code, timetable.startTime.time)
        val periods = timetable.scheduleStructure.map { SharedPeriod(minutes(it.from), minutes(it.to)) }
        // Local grouping keys never enter the model. Empty subject IDs fall back to names.
        val groups = if (mode == ShareMode.FULL) selected.groupBy {
            // Different labels under one subject must retain each display name.
            Triple(it.subjectId.isNotEmpty(), it.subjectId, it.name)
        }.values.toList() else emptyList()
        val courses = groups.map { group -> group.first().name }
        val occurrences = if (mode == ShareMode.BUSY) selected.map {
            SharedOccurrence(it.from.time, it.to.time)
        } else groups.flatMapIndexed { index, group -> group.map {
            SharedOccurrence(it.from.time, it.to.time, index, it.place)
        } }
        val snapshot = SharedTimetable(shareId, nickname, mode,
            SharedTerm(if (mode == ShareMode.BUSY) "忙闲课表" else timetable.name ?: "", term.displayName, timetable.startTime.time, timetable.endTime.time),
            periods, courses, occurrences)
        // Validate every raw occurrence before canonical merging. Reuse the single protocol
        // validation/normalization boundary so builder and decoder accept the same fields.
        val codec = TimetableShareCodec()
        return codec.decodeJson(codec.canonicalJson(snapshot))
    }

    private fun minutes(value: TimeInDay): Int {
        if (value.hour !in 0..24 || value.minute !in 0..59 || (value.hour == 24 && value.minute != 0)) invalidFields()
        return value.hour * 60 + value.minute
    }
}
