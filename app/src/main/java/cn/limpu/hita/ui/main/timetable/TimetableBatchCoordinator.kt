package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.subject.SubjectBatchDeleteScope
import cn.limpu.hita.ui.subject.SubjectBatchEditScope
import cn.limpu.hita.ui.subject.applySubjectBatchEdit
import cn.limpu.hita.ui.subject.subjectEditCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class TimetableSelection(val owner: Long, val generation: Long)

internal enum class TimetableBatchScope { THIS, SLOT, ALL }

internal object TimetableTargetPolicy {
    fun hitTimetable(timetables: List<Timetable>, atMillis: Long): Timetable? = timetables
        .filter { atMillis in it.startTime.time..it.endTime.time }
        .minByOrNull { it.startTime.time }

    fun scheduleTimetable(timetables: List<Timetable>, atMillis: Long): Timetable? =
        hitTimetable(timetables, atMillis) ?: timetables.minByOrNull { it.startTime.time }

    fun targets(event: EventItem, all: List<EventItem>, scope: TimetableBatchScope): List<EventItem> {
        if (TimetableEventPolicy.isReadOnlyProjection(event)) return emptyList()
        val classes = all.filter {
            it.type == EventItem.TYPE.CLASS && it.subjectId == event.subjectId &&
                it.timetableId == event.timetableId && !TimetableEventPolicy.isReadOnlyProjection(it)
        }
        return when (scope) {
            TimetableBatchScope.THIS -> listOf(event)
            TimetableBatchScope.SLOT -> classes.filter {
                it.getDow() == event.getDow() && it.fromNumber == event.fromNumber && it.lastNumber == event.lastNumber
            }.ifEmpty { listOf(event) }
            TimetableBatchScope.ALL -> classes.ifEmpty { listOf(event) }
        }.distinctBy { it.id }.map(EventItem::subjectEditCopy)
    }
}

internal data class TimetableBatchSession(
    val edit: Boolean,
    val scope: TimetableBatchScope,
    val events: List<EventItem>,
    val timetable: Timetable?,
) {
    val editScope get() = when (scope) {
        TimetableBatchScope.THIS -> SubjectBatchEditScope.THIS
        TimetableBatchScope.SLOT -> SubjectBatchEditScope.SLOT
        TimetableBatchScope.ALL -> SubjectBatchEditScope.ALL
    }
    val deleteScope get() = when (scope) {
        TimetableBatchScope.THIS -> SubjectBatchDeleteScope.THIS
        TimetableBatchScope.SLOT -> SubjectBatchDeleteScope.SLOT
        TimetableBatchScope.ALL -> SubjectBatchDeleteScope.ALL
    }
}

internal data class TimetableBatchState(val session: TimetableBatchSession? = null, val writing: Boolean = false)
internal sealed interface TimetableNotice {
    data class Failure(val message: String?) : TimetableNotice
    data class Updated(val count: Int, val skippedTime: Boolean) : TimetableNotice
    data object MissingTarget : TimetableNotice
    data object ReadOnly : TimetableNotice
}

/** All calls and write completions are on Main; blocking Room reads belong to the supplied IO adapter. */
internal class TimetableBatchCoordinator(
    private val scope: CoroutineScope,
    private val classes: suspend (String) -> List<EventItem>,
    private val timetable: suspend (String) -> Timetable?,
    private val update: (List<EventItem>, (Result<Unit>) -> Unit) -> Unit,
    private val delete: (List<EventItem>, (Result<Unit>) -> Unit) -> Unit,
) {
    private val mutableState = MutableStateFlow(TimetableBatchState())
    val state = mutableState.asStateFlow()
    private var ownerCounter = 0L
    private var activeOwner: Long? = null
    private var generation = 0L
    private var writeAttempt = 0L
    private var load: Job? = null
    private data class OwnedNotice(val owner: Long, val generation: Long, val value: TimetableNotice)
    private val pendingNotices = Channel<OwnedNotice>(Channel.BUFFERED)
    val notices = pendingNotices.receiveAsFlow()
        .filter { it.owner == activeOwner && it.generation == generation }
        .map { it.value }

    fun attach(): Long {
        cancel()
        return (++ownerCounter).also { activeOwner = it }
    }

    fun detach(owner: Long) {
        if (activeOwner != owner) return
        activeOwner = null
        cancel()
    }

    fun owns(owner: Long): Boolean = activeOwner == owner
    fun selection(owner: Long): TimetableSelection? = if (owns(owner)) TimetableSelection(owner, generation) else null
    fun owns(selection: TimetableSelection): Boolean = owns(selection.owner) && selection.generation == generation

    fun cancel(session: TimetableBatchSession?) {
        if (session != null && state.value.session === session) cancel()
    }

    fun cancel() {
        generation++
        load?.cancel()
        load = null
        mutableState.value = TimetableBatchState()
    }

    fun request(owner: Long, event: EventItem, edit: Boolean, selection: TimetableBatchScope) {
        request(this.selection(owner) ?: return, event, edit, selection)
    }

    fun request(token: TimetableSelection, event: EventItem, edit: Boolean, selection: TimetableBatchScope) {
        if (!owns(token)) return
        val owner = token.owner
        cancel()
        val request = generation
        val selected = event.subjectEditCopy()
        if (TimetableEventPolicy.isReadOnlyProjection(selected)) {
            notice(owner, request, TimetableNotice.ReadOnly)
            return
        }
        load = scope.launch {
            try {
                val targets = TimetableTargetPolicy.targets(selected, classes(selected.subjectId), selection)
                val target = timetable(selected.timetableId)
                if (!owns(owner) || request != generation) return@launch
                if (edit && target == null) {
                    notice(owner, request, TimetableNotice.MissingTarget)
                } else if (targets.isNotEmpty()) {
                    mutableState.value = TimetableBatchState(TimetableBatchSession(edit, selection, targets, target))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(owner, request, TimetableNotice.Failure(error.message))
            }
        }
    }

    fun apply(expected: TimetableBatchSession?, place: String?, teacher: String?, time: Triple<Int, Int, Int>?, slotTimes: Map<Triple<Int, Int, Int>, Triple<Int, Int, Int>>) {
        if (expected != null && state.value.session === expected) apply(place, teacher, time, slotTimes)
    }

    fun apply(place: String?, teacher: String?, time: Triple<Int, Int, Int>?, slotTimes: Map<Triple<Int, Int, Int>, Triple<Int, Int, Int>>) {
        val session = state.value.session?.takeIf { it.edit } ?: return
        // applySubjectBatchEdit mutates its input. Preserve the editor's original state on failed writes.
        val (events, skippedTime) = applySubjectBatchEdit(session.events.map(EventItem::subjectEditCopy), session.timetable, place, teacher, time, slotTimes)
        write(session, events, skippedTime)
    }

    fun delete(expected: TimetableBatchSession?) {
        if (expected != null && state.value.session === expected) delete()
    }

    fun delete() {
        val session = state.value.session?.takeUnless { it.edit } ?: return
        write(session, session.events.map(EventItem::subjectEditCopy), false)
    }

    private fun write(session: TimetableBatchSession, events: List<EventItem>, skippedTime: Boolean) {
        val owner = activeOwner ?: return
        if (state.value.writing || events.any(TimetableEventPolicy::isReadOnlyProjection)) return
        val request = generation
        val attempt = ++writeAttempt
        mutableState.value = state.value.copy(writing = true)
        val completed: (Result<Unit>) -> Unit = { result ->
            if (owns(owner) && request == generation && attempt == writeAttempt && state.value.session === session && state.value.writing) {
                mutableState.value = if (result.isSuccess) TimetableBatchState() else state.value.copy(writing = false)
                if (result.isSuccess) {
                    if (session.edit) notice(owner, request, TimetableNotice.Updated(events.size, skippedTime))
                } else notice(owner, request, TimetableNotice.Failure(result.exceptionOrNull()?.message))
            }
        }
        try {
            if (session.edit) update(events, completed) else delete(events, completed)
        } catch (error: Exception) {
            completed(Result.failure(error))
        }
    }

    fun reportFailure(owner: Long, error: Throwable) = notice(owner, generation, TimetableNotice.Failure(error.message))

    private fun notice(owner: Long, request: Long, value: TimetableNotice) {
        if (owns(owner) && request == generation) pendingNotices.trySend(OwnedNotice(owner, request, value))
    }
}
