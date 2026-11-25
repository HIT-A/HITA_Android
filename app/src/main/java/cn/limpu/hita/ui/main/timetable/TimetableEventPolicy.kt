package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.TimetableEventAccess

/** Events generated from a maintained projection must be changed at their source. */
internal object TimetableEventPolicy {
    fun isReadOnlyProjection(event: EventItem): Boolean =
        TimetableEventAccess.forEvent(event) == TimetableEventAccess.READ_ONLY
}
