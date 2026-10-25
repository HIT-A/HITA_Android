package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable

/** Persistence port for ordinary event/course edits; platform refresh belongs to the caller. */
interface TimetableWriteStore {
    /** Use the repository's existing queue so reads and writes retain their ordering. */
    fun enqueueWrite(block: () -> Unit)

    /** Runs on a worker thread. False means empty input or a missing timetable for a schedule edit. */
    fun writeSync(operation: TimetableWriteOperation): Boolean
}

sealed interface TimetableWriteOperation {
    data class Add(val result: ScheduleEventCreator.Result, val subject: TermSubject?) : TimetableWriteOperation
    data class Update(val events: List<EventItem>) : TimetableWriteOperation
    data class Delete(val eventIds: List<String>) : TimetableWriteOperation
    data class DeleteSubjects(val subjects: List<TermSubject>) : TimetableWriteOperation
    data class ChangeStartDate(val timetable: Timetable, val startTime: Long) : TimetableWriteOperation
    data class ChangeStructure(
        val timetable: Timetable,
        val period: TimePeriodInDay,
        val position: Int,
    ) : TimetableWriteOperation
}
