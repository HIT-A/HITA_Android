package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.feature.timetableshare.protocol.ShareError
import cn.limpu.hita.feature.timetableshare.protocol.ShareFormatException
import cn.limpu.hita.feature.timetableshare.protocol.ShareLimits
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.feature.timetableshare.protocol.ShareTermResolver
import cn.limpu.hita.feature.timetableshare.protocol.SharedOccurrence
import cn.limpu.hita.feature.timetableshare.protocol.SharedPeriod
import cn.limpu.hita.feature.timetableshare.protocol.SharedTerm
import cn.limpu.hita.feature.timetableshare.protocol.SharedTimetable
import cn.limpu.hita.feature.timetableshare.protocol.TimetableShareCodec
import java.time.Instant

/** Reads only the selected personal timetable. Call within the repository's read transaction. */
class ShareSnapshotBuilder {
    fun build(timetable: Timetable, events: List<EventItem>, shareId: String, nickname: String, mode: ShareMode): SharedTimetable {
        val selected = selectEvents(timetable, events)
        if (selected.isEmpty()) throw ShareFormatException(ShareError.EMPTY_SCHEDULE)
        if (selected.size > ShareLimits.OCCURRENCES) throw ShareFormatException(ShareError.INVALID_FIELDS)
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

    /** Classes wholly outside the timetable's displayed dates must not invalidate its share. */
    internal fun selectEvents(timetable: Timetable, events: List<EventItem>): List<EventItem> {
        val firstDay = Instant.ofEpochMilli(timetable.startTime.time).atZone(ShareLimits.ZONE).toLocalDate()
        val lastDay = Instant.ofEpochMilli(timetable.endTime.time).atZone(ShareLimits.ZONE).toLocalDate()
        val lower = firstDay.atStartOfDay(ShareLimits.ZONE).toInstant().toEpochMilli()
        val upper = lastDay.plusDays(1).atStartOfDay(ShareLimits.ZONE).toInstant().toEpochMilli()
        return events.filter {
            it.timetableId == timetable.id && it.type == EventItem.TYPE.CLASS &&
                it.to.time > lower && it.from.time < upper
        }
    }

    private fun minutes(value: TimeInDay): Int {
        if (value.hour !in 0..24 || value.minute !in 0..59 || (value.hour == 24 && value.minute != 0)) throw ShareFormatException(ShareError.INVALID_FIELDS)
        return value.hour * 60 + value.minute
    }
}
