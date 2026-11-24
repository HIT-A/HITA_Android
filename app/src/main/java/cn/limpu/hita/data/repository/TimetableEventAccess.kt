package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventItem

/** What an event source permits; stored table ownership is also checked in the write transaction. */
enum class TimetableEventAccess {
    PERSONAL, TEACHING, READ_ONLY;

    companion object {
        const val FRIEND_SNAPSHOT = "FRIEND_SNAPSHOT"

        fun forEvent(event: EventItem): TimetableEventAccess = when (event.source.substringBefore(':')) {
            EventItem.SOURCE_COURSE_PLAN, EventItem.SOURCE_FOLLOWED_SCHOOL, FRIEND_SNAPSHOT -> READ_ONLY
            else -> if (event.isTeachingCourse()) TEACHING else PERSONAL
        }
    }
}
