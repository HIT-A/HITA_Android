package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.ResolvedDisplayScope
import cn.limpu.hita.data.repository.today.TodayReadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** A minute tick never changes this key; scope, membership, date and time zone do. */
data class TodayTimelineQuery private constructor(
    val scopeType: String,
    val scopeId: String,
    val timetableIds: List<String>,
    val window: TodayDateWindow,
) {
    companion object {
        fun from(scope: ResolvedDisplayScope, window: TodayDateWindow) = TodayTimelineQuery(
            DisplayScope.typeOf(scope.scope), scope.scope.id, scope.timetableIds.distinct().sorted(), window,
        )
    }
}

data class TodayTimelineSnapshot(val query: TodayTimelineQuery, val events: List<EventItem>, val courseColors: Map<String, Int> = emptyMap())

/** Switching queries cancels the old subscription and clears its retained snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeTodayTimeline(
    queries: Flow<TodayReadState<TodayTimelineQuery?>>,
    retries: Flow<Int>,
    readCourseColors: (TodayTimelineQuery) -> Flow<Map<String, Int>> = { flowOf(emptyMap()) },
    read: (TodayTimelineQuery) -> Flow<TodayReadState<List<EventItem>>>,
): Flow<TodayReadState<TodayTimelineSnapshot?>> = combine(queries, retries) { query, retry -> query to retry }
    .distinctUntilChanged()
    .flatMapLatest { (state, _) ->
        when (state) {
            TodayReadState.Loading -> flowOf(TodayReadState.Loading)
            is TodayReadState.Failed -> flowOf(TodayReadState.Failed(null, state.failure))
            is TodayReadState.Ready -> {
                val query = state.value
                if (query == null) flowOf(TodayReadState.Ready(null))
                else read(query).combine(readCourseColors(query)) { result, colors ->
                    when (result) {
                        TodayReadState.Loading -> TodayReadState.Loading
                        is TodayReadState.Ready -> TodayReadState.Ready(TodayTimelineSnapshot(query, result.value, colors))
                        is TodayReadState.Failed -> TodayReadState.Failed(
                            result.retained?.let { TodayTimelineSnapshot(query, it, colors) }, result.failure,
                        )
                    }
                }.onStart { emit(TodayReadState.Loading) }.distinctUntilChanged()
            }
        }
    }.buffer(0)
