package cn.limpu.hita.ui.main.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.repository.homework.HomeworkRepository
import cn.limpu.hita.data.repository.today.TodayReadState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class FragmentTimelineViewModel internal constructor(
    private val sources: TodayReadSources,
    private val clock: Clock,
) : ViewModel() {
    @Inject constructor(timetable: TimetableRepository, resolver: DisplayScopeResolver,
        local: HomeworkRepository, grader: GraderRepository, eas: EASRepository,
    ) : this(RepositoryTodaySources(timetable, resolver, local, grader, eas), Clock.systemUTC())

    /** Existing isolated storage harnesses do not require summary repositories. */
    internal constructor(timetable: TimetableRepository, resolver: DisplayScopeResolver) :
        this(TimetableTodaySources(timetable, resolver), Clock.systemUTC())

    private data class Reading(val now: Long, val window: TodayDateWindow)
    private val reading = MutableStateFlow(reading(clock.millis(), ZoneId.systemDefault()))
    private val visible = MutableStateFlow(false)
    private val retries = MutableStateFlow(0)
    private val scopes = sources.scopes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), TodayReadState.Loading)
    private val sessions = sources.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), sources.currentSession())
    private val queries = combine(scopes, reading) { scope, tick -> when (scope) {
        TodayReadState.Loading -> TodayReadState.Loading
        is TodayReadState.Failed -> TodayReadState.Failed(null, scope.failure)
        is TodayReadState.Ready -> TodayReadState.Ready(scope.value?.let { TodayTimelineQuery.from(it, tick.window) })
    } }
    private val timeline = observeTodayTimeline(queries, retries, sources::courseColors, sources::read)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), TodayReadState.Loading)
    private data class Summaries(val homework: cn.limpu.hita.data.repository.homework.HomeworkState,
        val grader: cn.limpu.hita.data.repository.grader.GraderState,
        val exams: cn.limpu.hita.data.repository.ExamReadSnapshot, val session: TodaySession)
    private val summaries = combine(sources.homework, sources.grader, sources.exams, sessions, ::Summaries)

    val uiState: StateFlow<TodayUiState> = combine(timeline, scopes, reading, summaries, sources.scopeOptions) { data, scopeState, tick, summary, options ->
        val scope = (scopeState as? TodayReadState.Ready)?.value
        val expected = scope?.let { TodayTimelineQuery.from(it, tick.window) }
        val snapshot = snapshot(data)?.takeIf { it.query == expected }
        val status = when {
            scopeState == TodayReadState.Loading -> TodayPageStatus.LOADING
            scopeState is TodayReadState.Failed -> TodayPageStatus.FAILED
            scope == null -> TodayPageStatus.NO_TARGET
            data is TodayReadState.Failed -> TodayPageStatus.FAILED
            snapshot == null -> TodayPageStatus.LOADING
            else -> TodayPageStatus.READY
        }
        val agenda = snapshot?.let { TodayProjection.project(tick.window, it.events, tick.now) }
        val schedules = scope?.timetables.orEmpty().map { table -> table.scheduleStructure.map {
            (it.from.hour * 60 + it.from.minute) to (it.to.hour * 60 + it.to.minute)
        } }
        TodayUiState(tick.now, tick.window, summary.session,
            scope?.let { "${DisplayScope.typeOf(it.scope)}:${it.scope.id}" } ?: if (status == TodayPageStatus.NO_TARGET) "none" else "unresolved",
            scope?.name, status, agenda, TodayHeaderResolver.resolve(tick.now, tick.window.zoneId, agenda, schedules),
            TodayHomeworkProjection.project(summary.homework, summary.grader, summary.session.graderAvailable, tick.now, tick.window.zoneId),
            TodayExamProjection.project(tick.window, tick.now, snapshot?.events.orEmpty(), expected?.timetableIds.orEmpty().toSet(),
                summary.exams, summary.session.ownerKey, summary.session.generation), expected?.timetableIds.orEmpty(),
            scopeOptions = options.map { TodayScopeOption(it.scope, it.name, it.memberCount, it.scope == scope?.scope) },
            courseColors = snapshot?.courseColors.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), TodayUiState(reading.value.now, reading.value.window))

    // Kept for non-UI consumers and the existing storage regression harness.
    val timelineReadState = timeline.asLiveData(context = viewModelScope.coroutineContext, timeoutInMs = 0L)
    val todayEventsLiveData = timelineReadState.map { data -> snapshot(data)?.let {
        TodayProjection.project(it.query.window, it.events, reading.value.now).allToday
    }.orEmpty() }

    init {
        viewModelScope.launch {
            combine(visible, sessions) { shown, session -> (shown && session.graderAvailable) to session }
                .distinctUntilChanged().collectLatest { (enabled, _) ->
                    sources.visible(enabled)
                    if (enabled) sources.load(TodayRetrySource.GRADER)
                }
        }
    }

    fun onVisible(now: Long = clock.millis(), zone: ZoneId = ZoneId.systemDefault()) {
        onTimeChanged(now, zone)
        if (!visible.value) {
            visible.value = true
            viewModelScope.launch { sources.load(TodayRetrySource.HOMEWORK) }
        }
    }
    fun onHidden() { visible.value = false; sources.visible(false) }
    fun onTimeChanged(now: Long = clock.millis(), zone: ZoneId = ZoneId.systemDefault()) { reading.value = reading(now, zone) }
    fun startRefresh() = onTimeChanged()
    fun retry(source: TodayRetrySource) {
        if (source == TodayRetrySource.AGENDA) retries.value++
        else if (visible.value) viewModelScope.launch { sources.load(source, retry = true) }
    }
    fun retryTimelineRead() = retry(TodayRetrySource.AGENDA)
    fun nextWakeDelay(state: TodayUiState = uiState.value): Long =
        (TodayClock.nextWake(state.now, state.window, state.agenda?.allToday.orEmpty()) - clock.millis()).coerceAtLeast(1L)
    fun accepts(expected: TodayUiState): Boolean {
        val resolved = sources.currentScope()
        val currentScope = resolved?.let { "${DisplayScope.typeOf(it.scope)}:${it.scope.id}" } ?: "none"
        return expected.session == sources.currentSession() && expected.scopeKey == currentScope && expected.window == reading.value.window && expected.memberIds == resolved?.timetableIds.orEmpty().distinct().sorted()
    }
    fun acceptsHomework(expected: TodayUiState, source: TodayHomeworkSource): Boolean = accepts(expected) &&
        (source != TodayHomeworkSource.GRADER || expected.homework?.grader?.accountId == sources.currentGraderAccount())
    suspend fun resolveEvent(expected: TodayUiState, id: String): EventItem? {
        if (!accepts(expected)) return null
        val scope = sources.currentScope() ?: return null
        val event = sources.findEvent(TodayTimelineQuery.from(scope, expected.window), id)
        return event?.takeIf { accepts(expected) }
    }
    private var widgetKey: Any? = null
    fun consumeWidgetChange(state: TodayUiState): Boolean {
        if (state.status == TodayPageStatus.LOADING || (state.status == TodayPageStatus.FAILED && state.agenda == null)) return false
        val key = Triple(state.scopeKey, state.window, state.agenda?.allToday.orEmpty().map {
            listOf(it.id, it.type, it.source, it.name, it.place, it.from.time, it.to.time, it.fromNumber, it.lastNumber, it.color)
        })
        if (widgetKey == key) return false
        widgetKey = key
        return true
    }
    override fun onCleared() { sources.visible(false); super.onCleared() }
    private fun reading(now: Long, zone: ZoneId) = Reading(now, TodayDateWindow.from(Instant.ofEpochMilli(now), zone))
    private fun snapshot(state: TodayReadState<TodayTimelineSnapshot?>): TodayTimelineSnapshot? = when (state) {
        is TodayReadState.Ready -> state.value
        is TodayReadState.Failed -> state.retained
        TodayReadState.Loading -> null
    }
}
