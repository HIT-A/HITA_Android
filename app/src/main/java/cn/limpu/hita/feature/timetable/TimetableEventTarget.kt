package cn.limpu.hita.feature.timetable

import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/** Shared routing fields for calendar rows and course-reminder notifications. */
internal object TimetableEventTarget {
    const val ACTION_WIDGET_ROW = "cn.limpu.hita.WIDGET_EVENT_CLICK"
    const val ACTION_WIDGET_SLIM_ROW = "cn.limpu.hita.WIDGET_EVENT_CLICK2"
    const val ACTION_COURSE_REMINDER = "cn.limpu.hita.COURSE_REMINDER_CLICK"
    const val EXTRA_EVENT_ID = "hita.widget.EXTRA_EVENT_ID"
    const val EXTRA_DATE = "hita.widget.EXTRA_DATE"

    fun dateFor(startMillis: Long): String =
        Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun write(intent: Intent, eventId: String, date: String) {
        intent.putExtra(EXTRA_EVENT_ID, eventId)
        intent.putExtra(EXTRA_DATE, date)
    }
}
