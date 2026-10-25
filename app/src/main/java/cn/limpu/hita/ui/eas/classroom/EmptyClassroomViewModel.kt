package cn.limpu.hita.ui.eas.classroom

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import androidx.lifecycle.*
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableRepository
import com.limpu.component.data.DataState
import com.limpu.component.data.Trigger
import cn.limpu.hita.ui.eas.EASViewModel
import com.limpu.component.data.MTransformations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class EmptyClassroomViewModel @Inject constructor(
    easRepo: EASRepository,
    private val timetableRepository: TimetableRepository
) : EASViewModel(easRepo) {


    private val pageController = MutableLiveData<Trigger>()
    val termsLiveData: LiveData<DataState<List<TermItem>>> = pageController.switchMap {
        return@switchMap easRepo.getAllTerms().map { state ->
            val data = state.data
            if (state.state != DataState.STATE.SUCCESS || data.isNullOrEmpty()) {
                return@map state
            }
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
            val filtered = data.filter { term ->
                term.yearCode.contains(currentYear) || term.yearName.contains(currentYear) || term.name.contains(currentYear)
            }
            val finalList = if (filtered.isNotEmpty()) filtered else data
            DataState(finalList, state.state)
        }
    }
    val buildingsLiveData: LiveData<DataState<List<BuildingItem>>> = pageController.switchMap {
            return@switchMap easRepo.getTeachingBuildings()
        }
    val selectedTermLiveData = MutableLiveData<TermItem>()
    val selectedBuildingLiveData = MutableLiveData<BuildingItem>()
    val selectedWeekLiveData: MediatorLiveData<Int> =
        MTransformations.switchMap(selectedTermLiveData) { term ->
            return@switchMap timetableRepository.getCurrentWeekOfTimetable(term)
        }
    val timetableStructureLiveData: LiveData<DataState<MutableList<TimePeriodInDay>>> = selectedTermLiveData.switchMap {
        return@switchMap easRepo.getScheduleStructure(it)
    }
    val classroomLiveData: MediatorLiveData<DataState<List<ClassroomItem>>> =
        MediatorLiveData<DataState<List<ClassroomItem>>>().apply {
            var currentQuerySource: LiveData<DataState<List<ClassroomItem>>>? = null
            var lastQueryKey: ClassroomQueryKey? = null

            fun launchClassroomQuery() {
                val term = selectedTermLiveData.value ?: return
                val building = selectedBuildingLiveData.value ?: return
                val week = selectedWeekLiveData.value ?: return
                val queryKey = ClassroomQueryKey(term.getCode(), building.id.ifBlank { building.name.orEmpty() }, week)
                if (queryKey == lastQueryKey) return
                lastQueryKey = queryKey

                currentQuerySource?.let { removeSource(it) }
                val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED)
                val source = easRepo.queryEmptyClassroom(term, building, week)
                currentQuerySource = source
                addSource(source) { state ->
                    when (state.state) {
                        DataState.STATE.SUCCESS -> UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_SUCCEEDED)
                        DataState.STATE.NOTHING, DataState.STATE.LOADING -> Unit
                        else -> UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_FAILED,
                            mapOf("error_category" to if (state.state in setOf(DataState.STATE.NOT_LOGGED_IN, DataState.STATE.TOKEN_INVALID)) "authentication" else "unknown"))
                    }
                    value = state
                }
            }

            addSource(selectedTermLiveData) {
                lastQueryKey = null
                launchClassroomQuery()
            }
            addSource(selectedBuildingLiveData) {
                launchClassroomQuery()
            }
            addSource(selectedWeekLiveData) {
                launchClassroomQuery()
            }
        }


    fun startRefresh() {
        pageController.value = Trigger.actioning
    }

    fun retryCurrentQuery(): Boolean {
        val term = selectedTermLiveData.value ?: return false
        val building = selectedBuildingLiveData.value ?: return false
        val week = selectedWeekLiveData.value ?: return false
        selectedTermLiveData.value = term
        selectedBuildingLiveData.value = building
        selectedWeekLiveData.value = week
        return true
    }

    private data class ClassroomQueryKey(
        val termCode: String,
        val buildingId: String,
        val week: Int
    )
}
