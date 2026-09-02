package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem

/** Events generated from a maintained projection must be changed at their source. */
internal object TimetableEventPolicy {
    fun isReadOnlyProjection(event: EventItem): Boolean =
        event.source.startsWith("${EventItem.SOURCE_COURSE_PLAN}:") ||
            event.source.startsWith("${EventItem.SOURCE_FOLLOWED_SCHOOL}:")
}
