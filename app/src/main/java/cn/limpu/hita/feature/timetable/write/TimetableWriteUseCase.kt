package cn.limpu.hita.feature.timetable.write

import androidx.annotation.WorkerThread
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.ScheduleEventCreator
import cn.limpu.hita.data.repository.TimetableWriteOperation
import cn.limpu.hita.data.repository.TimetableWriteStore

/** Completes persistence before requesting widgets or delivering the write result. */
class TimetableWriteUseCase internal constructor(
    private val store: TimetableWriteStore,
    private val refreshWidgets: () -> Unit,
    private val deliverResult: (() -> Unit) -> Unit,
    private val onWriteFailure: (Throwable) -> Unit,
    private val onRefreshFailure: (Throwable) -> Unit,
    private val enqueueWrite: (() -> Unit) -> Unit = store::enqueueWrite,
) {
    fun add(
        result: ScheduleEventCreator.Result,
        subjectToSave: TermSubject? = null,
        onResult: ((Result<Unit>) -> Unit)? = null,
    ) = submit(TimetableWriteOperation.Add(result, subjectToSave), onResult)

    fun update(events: Collection<EventItem>, onResult: ((Result<Unit>) -> Unit)? = null) =
        submit(TimetableWriteOperation.Update(events.toList()), onResult)

    fun delete(events: Collection<EventItem>, onResult: ((Result<Unit>) -> Unit)? = null) =
        submit(TimetableWriteOperation.Delete(events.map { it.id }), onResult)

    fun deleteSubjects(subjects: Collection<TermSubject>, onResult: ((Result<Unit>) -> Unit)? = null) =
        submit(TimetableWriteOperation.DeleteSubjects(subjects.toList()), onResult)

    fun changeStartDate(
        timetable: Timetable,
        startTime: Long,
        onResult: ((Result<Unit>) -> Unit)? = null,
    ) = submit(TimetableWriteOperation.ChangeStartDate(timetable, startTime), onResult)

    fun changeStructure(
        timetable: Timetable,
        period: TimePeriodInDay,
        position: Int,
        onResult: ((Result<Unit>) -> Unit)? = null,
    ) = submit(TimetableWriteOperation.ChangeStructure(timetable, period, position), onResult)

    /** Agent callers receive write failures directly, after persistence has actually finished. */
    @WorkerThread
    fun addSync(result: ScheduleEventCreator.Result, subjectToSave: TermSubject? = null) =
        execute(TimetableWriteOperation.Add(result, subjectToSave))

    private fun submit(operation: TimetableWriteOperation, onResult: ((Result<Unit>) -> Unit)?) {
        if (operation.isEmpty()) {
            if (onResult != null) deliverResult { onResult(Result.success(Unit)) }
            return
        }
        enqueueWrite {
            val outcome = runCatching { execute(operation) }
            outcome.exceptionOrNull()?.let(onWriteFailure)
            if (onResult != null) deliverResult { onResult(outcome) }
        }
    }

    private fun execute(operation: TimetableWriteOperation) {
        if (operation.isEmpty() || !store.writeSync(operation)) return
        // Persistence has committed. A refresh error must not invite a duplicate write on retry.
        runCatching(refreshWidgets).onFailure(onRefreshFailure)
    }

    private fun TimetableWriteOperation.isEmpty(): Boolean = when (this) {
        is TimetableWriteOperation.Add -> result.events.isEmpty()
        is TimetableWriteOperation.Update -> events.isEmpty()
        is TimetableWriteOperation.Delete -> eventIds.isEmpty()
        is TimetableWriteOperation.DeleteSubjects -> subjects.isEmpty()
        is TimetableWriteOperation.ChangeStartDate, is TimetableWriteOperation.ChangeStructure -> false
    }
}
