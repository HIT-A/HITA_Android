package cn.limpu.hita.ui.eas.classroom

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import androidx.lifecycle.*
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableRepository
import com.limpu.component.data.DataState
import com.limpu.component.data.Trigger
import cn.limpu.hita.ui.eas.EASViewModel
import cn.limpu.hita.ui.eas.TermQueryLiveData
import cn.limpu.hita.ui.eas.mapTerms
import cn.limpu.hita.ui.eas.reconcileSelectedTerm
import cn.limpu.hita.ui.eas.sessionBoundData
import cn.limpu.hita.ui.eas.TermSelectionDisplaySource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class EmptyClassroomViewModel internal constructor(
    easRepo: EASRepository,
    private val inputs: EmptyClassroomPageInputs,
) : EASViewModel(easRepo) {
    @Inject constructor(
        easRepo: EASRepository,
        timetableRepository: TimetableRepository,
    ) : this(
        easRepo,
        EmptyClassroomPageInputs(
            getTerms = { easRepo.createTermQueryRequest() },
            createTeachingBuildingsRequest = easRepo::createTeachingBuildingsRequest,
            getCurrentWeek = timetableRepository::getCurrentWeekOfTimetable,
            getScheduleStructure = { term -> easRepo.sessionBoundData { session ->
                easRepo.getScheduleStructure(session, term,
                    session.studentType == cn.limpu.hita.data.model.eas.EASToken.TYPE.UNDERGRAD)
            } },
            createClassroomQueryRequest = { term, building, week ->
                easRepo.createEmptyClassroomQueryRequest(term, building, week)
            },
        ),
    )

    private val pageController = MutableLiveData<Trigger>()
    private val queries = EmptyClassroomQueryCoordinator(viewModelScope)
    private var termGeneration = 0L
    private var termIdentity: TermIdentity? = null
    private var currentTerm: TermItem? = null
    private var currentBuilding: BuildingItem? = null
    private var buildingKey: String? = null
    private var currentWeek: Int? = null
    private var manualWeek = false
    private var defaultWeekSource: LiveData<Int>? = null
    private var lastQueryKey: ClassroomQueryKey? = null
    private var pendingForce = false
    private var cleared = false
    private val termQueries = TermQueryLiveData(viewModelScope, easRepo.querySessions, inputs.getTerms,
        transform = { session, state -> state.mapTerms { data ->
            val year = session.calendarYear.toString()
            data.filter { it.yearCode.contains(year) || it.yearName.contains(year) || it.name.contains(year) }
                .ifEmpty { data }
        } },
        onSessionChanged = {
            selectTerm(null)
            selectedTermDisplay.updateOptions(emptyList())
            selectBuilding(null)
            classroomResults.value = DataState(DataState.STATE.NOTHING)
            pageController.value = Trigger.actioning
        },
        onResult = { _, state -> if (state.state == DataState.STATE.SUCCESS) {
            selectedTermDisplay.updateOptions(state.data.orEmpty())
            selectTerm(reconcileSelectedTerm(state.data.orEmpty(), currentTerm))
        } },
    )
    val termsLiveData: LiveData<DataState<List<TermItem>>> = termQueries
    val buildingsLiveData: LiveData<DataState<List<BuildingItem>>> =
        pageController.loadTeachingBuildings(viewModelScope, inputs.createTeachingBuildingsRequest).map { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                val values = state.data.orEmpty()
                selectBuilding(values.firstOrNull { it.id.ifBlank { it.name.orEmpty() } == buildingKey } ?: values.firstOrNull())
            }
            state
        }
    private val selectedTerm = MutableLiveData<TermItem>()
    private val selectedBuilding = MutableLiveData<BuildingItem>()
    private val selectedWeek = object : MediatorLiveData<Int>() {
        override fun onActive() {
            super.onActive()
            ensureDefaultWeekSource()
        }
    }
    val selectedTermLiveData: LiveData<TermItem> = selectedTerm
    internal val selectedTermDisplay = TermSelectionDisplaySource(selectedTermLiveData)
    val selectedBuildingLiveData: LiveData<BuildingItem> = selectedBuilding
    val selectedWeekLiveData: LiveData<Int> = selectedWeek
    val timetableStructureLiveData: LiveData<DataState<MutableList<TimePeriodInDay>>> = selectedTermLiveData.switchMap {
        if (it == null) MutableLiveData(DataState(DataState.STATE.NOTHING)) else inputs.getScheduleStructure(it)
    }
    private val classroomResults = object : MediatorLiveData<DataState<List<ClassroomItem>>>() {
        override fun onActive() {
            super.onActive()
            launchCurrentQuery()
        }
    }
    val classroomLiveData: LiveData<DataState<List<ClassroomItem>>> = classroomResults

    init {
        // Activate default-week loading even when only the results are observed.
        classroomResults.addSource(selectedWeek) { launchCurrentQuery() }
    }

    private fun ensureDefaultWeekSource() {
        if (cleared || !selectedWeek.hasActiveObservers() || defaultWeekSource != null) return
        val term = currentTerm ?: return
        val generation = termGeneration
        val source = inputs.getCurrentWeek(term)
        if (cleared || generation != termGeneration) return
        defaultWeekSource = source
        selectedWeek.addSource(source) { week ->
            if (!cleared && generation == termGeneration && !manualWeek) {
                updateWeek(week)
            }
        }
    }

    private fun selectionChanged() {
        queries.cancel()
        lastQueryKey = null
    }

    fun selectTerm(term: TermItem?) {
        if (cleared) return
        term?.let(selectedTermDisplay::updateTerm)
        val identity = term?.let { TermIdentity(it.yearCode, it.termCode) }
        if (identity == termIdentity) {
            currentTerm = term
            return
        }
        selectionChanged()
        val generation = ++termGeneration
        termIdentity = identity
        currentTerm = term
        currentWeek = null
        manualWeek = false
        defaultWeekSource?.let(selectedWeek::removeSource)
        defaultWeekSource = null
        selectedWeek.value = null
        if (cleared || generation != termGeneration) return
        selectedTerm.value = term
        if (cleared || generation != termGeneration) return
        ensureDefaultWeekSource()
        launchCurrentQuery()
    }

    fun selectBuilding(building: BuildingItem?) {
        if (cleared) return
        val key = building?.let { it.id.ifBlank { it.name.orEmpty() } }
        if (key == buildingKey) {
            currentBuilding = building
            return
        }
        selectionChanged()
        buildingKey = key
        currentBuilding = building
        selectedBuilding.value = building
        launchCurrentQuery()
    }

    fun selectWeek(week: Int?) {
        if (cleared) return
        manualWeek = true
        updateWeek(week)
    }

    private fun updateWeek(week: Int?) {
        if (currentWeek == week) return
        selectionChanged()
        currentWeek = week
        selectedWeek.value = week
        launchCurrentQuery()
    }

    fun startRefresh() {
        if (cleared) return
        forceCurrentQuery()
        termQueries.refresh()
        pageController.value = Trigger.actioning
    }

    fun retryCurrentQuery(): Boolean {
        if (cleared || currentTerm == null || currentBuilding == null || currentWeek == null) return false
        forceCurrentQuery()
        return true
    }

    private fun forceCurrentQuery() {
        queries.cancel()
        pendingForce = true
        launchCurrentQuery()
    }

    private fun launchCurrentQuery() {
        if (cleared || !classroomResults.hasActiveObservers()) return
        val term = currentTerm ?: return
        val identity = termIdentity ?: return
        val building = currentBuilding ?: return
        val week = currentWeek ?: return
        val key = ClassroomQueryKey(identity, buildingKey.orEmpty(), week)
        if (!pendingForce && key == lastQueryKey) return
        pendingForce = false
        lastQueryKey = key
        val generation = queries.cancel()
        val operation = inputs.beginOperation(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED)
        if (!queries.isCurrent(generation)) return
        val request = inputs.createClassroomQueryRequest(term, building, week)
        if (!queries.isCurrent(generation)) return
        queries.start(generation, request) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> inputs.finishOperation(operation, UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_SUCCEEDED, emptyMap())
                DataState.STATE.NOTHING, DataState.STATE.LOADING -> Unit
                else -> inputs.finishOperation(operation, UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_FAILED,
                    mapOf("error_category" to if (state.state in setOf(DataState.STATE.NOT_LOGGED_IN, DataState.STATE.TOKEN_INVALID)) "authentication" else "unknown"))
            }
            if (queries.isCurrent(generation)) classroomResults.value = state
        }
    }

    override fun onCleared() {
        cleared = true
        queries.cancel()
        termGeneration++
        defaultWeekSource?.let(selectedWeek::removeSource)
        defaultWeekSource = null
        super.onCleared()
    }

    private data class TermIdentity(val yearCode: String, val termCode: String)

    private data class ClassroomQueryKey(
        val term: TermIdentity,
        val buildingId: String,
        val week: Int
    )
}
