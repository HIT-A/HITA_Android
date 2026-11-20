package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.ExamEventMapper
import cn.limpu.hita.data.repository.ExamReadResult
import cn.limpu.hita.data.repository.ExamReadSnapshot
import cn.limpu.hita.data.repository.today.TodayReadState
import java.time.LocalDate

enum class TodayExamSource { IMPORTED, EAS_QUERY }

data class TodayExamEntry(
    val id: String,
    val source: TodayExamSource,
    val title: String,
    val place: String?,
    val start: Long?,
    val end: Long?,
    val timetableId: String?,
    val termId: String? = null,
    val retained: Boolean = false,
)

data class TodayExamCoverage(
    val queryKey: String,
    val status: TodaySourceStatus,
    val targetTimetableId: String?,
    val truncated: Boolean,
    val partial: Boolean,
)

data class TodayExamSummary(
    /** Known remaining exams only; this is never labelled as all academic exams. */
    val remaining: List<TodayExamEntry>,
    val timeToConfirm: List<TodayExamEntry>,
    /** Unbound academic rows remain separate from the current timetable count. */
    val unbound: List<TodayExamEntry>,
    val coverage: List<TodayExamCoverage>,
    val importedCount: Int,
    val ended: List<TodayExamEntry> = emptyList(),
)

object TodayExamProjection {
    fun project(
        window: TodayDateWindow,
        now: Long,
        events: List<EventItem>,
        timetableIds: Set<String>,
        remote: ExamReadSnapshot,
        currentOwnerKey: String?,
        sessionGeneration: Long,
    ): TodayExamSummary {
        val imported = events.filter {
            it.type == EventItem.TYPE.EXAM && it.hidden == 0 && it.timetableId in timetableIds &&
                it.to.time > it.from.time
        }.distinctBy { it.id }.sortedWith(compareBy<EventItem> { it.from.time }.thenBy { it.to.time }.thenBy { it.id })
        val importedKeys = imported.filter { it.source == EventItem.SOURCE_EAS_IMPORT }
            .mapTo(hashSetOf()) { it.timetableId to ExamEventMapper.identityKey(it) }
        val entries = imported.map { event ->
            TodayExamEntry(event.id, TodayExamSource.IMPORTED, event.name, event.place,
                event.from.time, event.to.time, event.timetableId)
        }
        val remaining = entries.filter { requireNotNull(it.end) > now }.toMutableList()
        val ended = entries.filter { requireNotNull(it.end) <= now }.toMutableList()
        val missing = mutableListOf<TodayExamEntry>()
        val unbound = mutableListOf<TodayExamEntry>()
        val coverage = mutableListOf<TodayExamCoverage>()
        val seen = hashSetOf<String>()
        if (currentOwnerKey != null && remote.ownerKey == currentOwnerKey && remote.sessionGeneration == sessionGeneration) {
            remote.terms.forEach { (queryKey, state) ->
                val result = when (state) {
                    is TodayReadState.Ready -> state.value
                    is TodayReadState.Failed -> state.retained
                    TodayReadState.Loading -> null
                }
                coverage += TodayExamCoverage(queryKey, when (state) {
                    TodayReadState.Loading -> TodaySourceStatus.LOADING
                    is TodayReadState.Ready -> TodaySourceStatus.READY
                    is TodayReadState.Failed -> TodaySourceStatus.FAILED
                }, result?.targetTimetableId, result?.truncated ?: false, result?.partial ?: false)
                result?.let {
                    appendRemote(it, state is TodayReadState.Failed, window, now, timetableIds, importedKeys, seen, remaining, missing, unbound, ended)
                }
            }
        }
        return TodayExamSummary(
            remaining.sortedWith(compareBy<TodayExamEntry> { it.start }.thenBy { it.id }),
            missing, unbound, coverage, imported.size,
            ended.sortedWith(compareByDescending<TodayExamEntry> { it.end }.thenBy { it.id }),
        )
    }

    private fun appendRemote(
        result: ExamReadResult, retained: Boolean, window: TodayDateWindow, now: Long, timetableIds: Set<String>,
        importedKeys: Set<Pair<String, String>>, seen: MutableSet<String>,
        remaining: MutableList<TodayExamEntry>, missing: MutableList<TodayExamEntry>, unbound: MutableList<TodayExamEntry>,
        ended: MutableList<TodayExamEntry>,
    ) {
        for (item in result.items) {
            try { LocalDate.parse(item.examDate) } catch (_: Exception) { continue }
            val term = item.termId ?: result.requestedTermId
            // An explicit mismatch must never inherit the request's target binding.
            val target = result.targetTimetableId?.takeIf {
                it in timetableIds && result.requestedTermId != null && term == result.requestedTermId
            }
            val mapped = ExamEventMapper.toEvent(item.toExamItem(), target.orEmpty(), zoneId = window.zoneId)
            val identity = if (mapped != null) listOf(ExamEventMapper.identityKey(mapped))
                else listOf(item.courseName, item.examDate, item.examTime, item.examLocation, item.examType)
            val key = (listOf(result.targetTimetableId, term) + identity)
                .joinToString("") { value -> value?.let { "${it.length}:$it" } ?: "-1:" }
            if (!seen.add(key)) continue
            if (target != null && mapped != null && (target to ExamEventMapper.identityKey(mapped)) in importedKeys) continue
            val entry = TodayExamEntry("remote:$key", TodayExamSource.EAS_QUERY, item.courseName.orEmpty(), item.examLocation,
                mapped?.from?.time, mapped?.to?.time, target, term, retained)
            when {
                target == null -> if (mapped == null || mapped.to.time > now) unbound += entry
                mapped == null -> missing += entry
                mapped.to.time <= now -> ended += entry
                else -> remaining += entry
            }
        }
    }
}
