package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.feature.timetableshare.protocol.ShareSnapshotBuilder
import cn.limpu.hita.feature.timetableshare.protocol.ShareTermResolver
import java.util.UUID

/** Uses the same canonical snapshot path as generation; does not create a persisted identity. */
class ShareSummaryBuilder(private val builder: ShareSnapshotBuilder = ShareSnapshotBuilder()) {
    fun build(timetable: Timetable, events: List<EventItem>, mode: ShareMode): ShareSummary {
        if (events.none { it.timetableId == timetable.id && it.type == EventItem.TYPE.CLASS }) {
            val term = ShareTermResolver.resolve(timetable.code, timetable.startTime.time)
            return ShareSummary(timetable.name.orEmpty(), term.displayName, 0, 0, mode)
        }
        // BUSY occurrences are counted only after the protocol's canonical overlap/adjacency merge.
        val snapshot = builder.build(timetable, events, UUID.randomUUID().toString(), "summary", mode)
        // The selected personal timetable keeps its display name even when the outgoing BUSY name is scrubbed.
        return ShareSummary(timetable.name.orEmpty(), snapshot.term.termName,
            snapshot.courses.size, snapshot.occurrences.size, mode)
    }
}
