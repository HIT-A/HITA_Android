package cn.limpu.hita.data.repository

import androidx.annotation.WorkerThread
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.event.add.CourseTime
import java.sql.Timestamp
import java.util.Calendar

object ScheduleEventCreator {
    data class Content(
        val name: String,
        val place: String = "",
        val teacher: String = "",
        val subject: TermSubject? = null,
        val subjectId: String = subject?.id.orEmpty(),
        val type: EventItem.TYPE = if (subject != null) EventItem.TYPE.CLASS else EventItem.TYPE.OTHER,
    )

    data class FixedRange(
        val fromMs: Long,
        val toMs: Long,
        val fromNumber: Int = 0,
        val lastNumber: Int = 0,
    )

    data class Result(
        val events: List<EventItem>,
        val timetable: Timetable,
    )

    fun buildEvents(
        timetable: Timetable,
        content: Content,
        courseTime: CourseTime,
        source: String,
    ): Result {
        val ranges = courseTime.weeks.map { week ->
            val fromTo = timetable.getTimestamps(week, courseTime.dow, courseTime.period)
            val nums = timetable.transformCourseNumber(courseTime.period)
            FixedRange(
                fromMs = fromTo[0],
                toMs = fromTo[1],
                fromNumber = nums.first,
                lastNumber = nums.second - nums.first + 1,
            )
        }
        return buildEvents(timetable, content, ranges, source)
    }

    fun buildEvents(
        timetable: Timetable,
        content: Content,
        ranges: List<FixedRange>,
        source: String,
    ): Result {
        val events = ranges
            .filter { it.toMs > it.fromMs }
            .map { range ->
                EventItem().apply {
                    type = content.type
                    this.source = source
                    name = content.name.trim()
                    timetableId = timetable.id
                    subjectId = content.subjectId
                    place = content.place
                    teacher = content.teacher
                    from = Timestamp(range.fromMs)
                    to = Timestamp(range.toMs)
                    fromNumber = range.fromNumber
                    lastNumber = range.lastNumber
                    color = -1
                }
            }
        return Result(events, timetable)
    }

    @WorkerThread
    fun persist(
        result: Result,
        timetableRepository: TimetableRepository,
        subjectToSave: TermSubject? = null,
    ) {
        if (result.events.isEmpty()) return
        if (subjectToSave != null) {
            timetableRepository.addCourseEventsSync(subjectToSave, result.events)
        } else {
            timetableRepository.addEventsSync(result.events)
        }
        extendTimetableIfNeeded(timetableRepository, result.timetable, result.events)
    }

    fun persistAsync(
        result: Result,
        timetableRepository: TimetableRepository,
        subjectToSave: TermSubject? = null,
    ) {
        if (result.events.isEmpty()) return
        if (subjectToSave != null) {
            timetableRepository.actionAddCourseEvents(subjectToSave, result.events)
        } else {
            timetableRepository.actionAddEvents(result.events)
        }
        val maxEndTime = result.events.maxOfOrNull { it.to.time } ?: return
        if (maxEndTime > result.timetable.endTime.time) {
            val timetable = result.timetable
            timetable.endTime.time = expandToWeekEnd(maxEndTime)
            timetableRepository.actionSaveTimetable(timetable)
        }
    }

    private fun extendTimetableIfNeeded(
        timetableRepository: TimetableRepository,
        timetable: Timetable,
        events: List<EventItem>,
    ) {
        val maxEndTime = events.maxOfOrNull { it.to.time } ?: return
        if (maxEndTime <= timetable.endTime.time) return
        timetable.endTime.time = expandToWeekEnd(maxEndTime)
        timetableRepository.saveTimetableSync(timetable)
    }

    private fun expandToWeekEnd(timestamp: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
