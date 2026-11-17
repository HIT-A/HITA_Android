package cn.limpu.hita.ui.main.timeline

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.limpu.component.data.Trigger
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.repository.today.TodayReadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FragmentTimelineViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    scopeResolver: DisplayScopeResolver
) : ViewModel() {

    /**
     * 数据区
     *
     * 今日与明日共享日期窗口查询；旧考试/后续接口在新 UI 接入前继续保留。
     * 显示目标尚未解析完成时保持空列表,避免闪现其他课表事件。
     */
    private val todayEventsController: MutableLiveData<Trigger> = MutableLiveData()
    private val queryInput = MediatorLiveData<List<String>>()

    private val window = MutableStateFlow<TodayDateWindow?>(null)
    private val retry = MutableStateFlow(0)
    private val timelineQueries = combine(scopeResolver.readState.asFlow(), window) { scope, day ->
        when {
            day == null || scope == TodayReadState.Loading -> TodayReadState.Loading
            scope is TodayReadState.Failed -> TodayReadState.Failed(null, scope.failure)
            scope is TodayReadState.Ready -> TodayReadState.Ready(scope.value?.let { TodayTimelineQuery.from(it, day) })
            else -> TodayReadState.Loading
        }
    }

    val timelineReadState: LiveData<TodayReadState<TodayTimelineSnapshot?>> = observeTodayTimeline(
        timelineQueries, retry,
    ) { query ->
        timetableRepository.observeTimelineWindow(query.window.start, query.window.dayAfterStart, query.timetableIds)
    }.asLiveData(context = viewModelScope.coroutineContext, timeoutInMs = 0L)

    /** Compatibility for the existing UI until the focused agenda screen is connected. */
    val todayEventsLiveData: LiveData<List<EventItem>> = timelineReadState.map { state ->
        val snapshot = when (state) {
            is TodayReadState.Ready -> state.value
            is TodayReadState.Failed -> state.retained
            TodayReadState.Loading -> null
        }
        snapshot?.let { TodayProjection.project(it.query.window, it.events, it.query.window.start).allToday }.orEmpty()
    }

    val upcomingExamLiveData: LiveData<List<EventItem>> = queryInput.switchMap { ids ->
        return@switchMap timetableRepository.getUpcomingExamsWithinReminderWindow(
            System.currentTimeMillis(), ids)
    }

    val weekEventsLiveData: LiveData<List<EventItem>> = queryInput.switchMap { ids ->
        return@switchMap timetableRepository.getEventsAfter(System.currentTimeMillis(), 4, ids)
    }


    private val _enterTick = MutableLiveData(0L)
    val enterTick: LiveData<Long> = _enterTick

    init {
        queryInput.addSource(todayEventsController) { refreshIds(scopeResolver, force = true) }
        queryInput.addSource(scopeResolver.resolved) { refreshIds(scopeResolver) }
    }

    private fun refreshIds(scopeResolver: DisplayScopeResolver, force: Boolean = false) {
        if (todayEventsController.value == null) return
        val ids = scopeResolver.resolved.value?.timetableIds.orEmpty()
        if (force || queryInput.value != ids) queryInput.value = ids
    }

    fun startRefresh() {
        window.value = TodayDateWindow.from(Instant.now(), ZoneId.systemDefault())
        todayEventsController.value = Trigger.actioning
    }

    fun retryTimelineRead() {
        retry.value += 1
    }

    fun triggerEnterAnimation() {
        _enterTick.value = System.currentTimeMillis()
    }
}
