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

    /** Gives a single personal deletion's exact history identity, never a later deletion of the same row. */
    fun deleteForUndo(event: EventItem, onResult: (Result<String>) -> Unit) {
        val historyId = java.util.UUID.randomUUID().toString()
        submit(TimetableWriteOperation.Delete(listOf(event.id), historyId)) { outcome ->
            onResult(outcome.map { historyId })
        }
    }

    fun deleteSubjects(subjects: Collection<TermSubject>, onResult: ((Result<Unit>) -> Unit)? = null) =
        submit(TimetableWriteOperation.DeleteSubjects(subjects.toList()), onResult)

    fun setTeachingVisibility(events: Collection<EventItem>, hidden: Boolean, onResult: ((Result<Unit>) -> Unit)? = null) =
        submit(TimetableWriteOperation.SetTeachingVisibility(events.map { it.id }, hidden), onResult)

    fun copyAsPersonal(event: EventItem, onResult: ((Result<Unit>) -> Unit)? = null) =
        submit(TimetableWriteOperation.CopyAsPersonal(event.id), onResult)

    fun restoreVersion(timetableId: String, versionId: String, revision: Long, onResult: (Result<Unit>) -> Unit) =
        submit(TimetableWriteOperation.RestoreVersion(timetableId, versionId, revision), onResult)

    fun restoreDeleted(timetableId: String, historyId: String, onResult: (Result<Unit>) -> Unit) =
        submit(TimetableWriteOperation.RestoreDeleted(timetableId, historyId), onResult)

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
    fun addSync(
        result: ScheduleEventCreator.Result,
        subjectToSave: TermSubject? = null,
        beforeWrite: () -> Unit = {},
    ) = run {
        // Agent 调用需要真实失败：未提交必须抛给工具，而不是静默成功。
        check(execute(TimetableWriteOperation.Add(result, subjectToSave), beforeWrite)) {
            "No timetable changes were committed"
        }
    }

    private fun submit(operation: TimetableWriteOperation, onResult: ((Result<Unit>) -> Unit)?) {
        if (operation.isEmpty()) {
            if (onResult != null) deliverResult { onResult(Result.success(Unit)) }
            return
        }
        enqueueWrite {
            var committed = false
            val outcome = runCatching { committed = execute(operation) }
            val failure = outcome.exceptionOrNull()
                ?: if (committed) null else IllegalStateException("No timetable changes were committed")
            failure?.let(onWriteFailure)
            if (onResult != null) {
                val result = failure?.let { Result.failure<Unit>(it) } ?: Result.success(Unit)
                deliverResult { onResult(result) }
            }
        }
    }

    /**
     * 返回是否应视为「已处理」。
     *
     * 表结构/开始日期这类没有变更时也会返回未提交的操作，语义等同 no-op，不当作失败；
     * 事件增删改的未提交必须回传失败，调用方才能保留表单并提供重试。
     */
    private fun execute(operation: TimetableWriteOperation, beforeWrite: () -> Unit = {}): Boolean {
        if (operation.isEmpty()) return true
        val committed = store.writeSync(operation, beforeWrite)
        if (!committed && operation is TimetableWriteOperation.ChangeStartDate) return true
        if (!committed && operation is TimetableWriteOperation.ChangeStructure) return true
        if (!committed) return false
        // Persistence has committed. A refresh error must not invite a duplicate write on retry.
        runCatching(refreshWidgets).onFailure(onRefreshFailure)
        return true
    }

    private fun TimetableWriteOperation.isEmpty(): Boolean = when (this) {
        is TimetableWriteOperation.Add -> result.events.isEmpty()
        is TimetableWriteOperation.SetTeachingVisibility -> eventIds.isEmpty()
        is TimetableWriteOperation.CopyAsPersonal -> eventId.isBlank()
        is TimetableWriteOperation.Update -> events.isEmpty()
        is TimetableWriteOperation.Delete -> eventIds.isEmpty()
        is TimetableWriteOperation.DeleteSubjects -> subjects.isEmpty()
        is TimetableWriteOperation.ChangeStartDate, is TimetableWriteOperation.ChangeStructure,
        is TimetableWriteOperation.RestoreDeleted, is TimetableWriteOperation.RestoreVersion -> false
    }
}
