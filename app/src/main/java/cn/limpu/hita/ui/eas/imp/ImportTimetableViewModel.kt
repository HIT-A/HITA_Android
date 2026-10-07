package cn.limpu.hita.ui.eas.imp

import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.limpu.component.data.DataState
import com.limpu.component.data.Trigger
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableVersionSnapshot
import cn.limpu.hita.data.source.preference.BenbuStartDatePreferenceSource
import cn.limpu.hita.ui.eas.EASViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class ImportTimetableViewModel @Inject constructor(
    easRepo: EASRepository,
    private val benbuStartDatePreference: BenbuStartDatePreferenceSource
) : EASViewModel(easRepo) {

    private val termsController = MutableLiveData<Trigger>()
    private var startDateSource: LiveData<DataState<Calendar>>? = null

    val termsLiveData: LiveData<DataState<List<TermItem>>> = termsController.switchMap {
        easRepo.getAllTerms()
    }

    val selectedTermLiveData: MutableLiveData<TermItem?> = MutableLiveData()
    val startDateLiveData = MediatorLiveData<DataState<Calendar>>()
    val benbuCalibrationConfirmedLiveData = MediatorLiveData<Boolean>()
    private var importOperation: UsageAnalyticsClient.Operation? = null
    val importTimetableResultLiveData = object : MediatorLiveData<DataState<Boolean>>() {
        override fun setValue(value: DataState<Boolean>?) {
            if (value != null && value.state !in setOf(DataState.STATE.NOTHING, DataState.STATE.LOADING)) {
                val success = value.state == DataState.STATE.SUCCESS
                UsageAnalyticsClient.finish(importOperation,
                    if (success) UsageAnalyticsEvent.TIMETABLE_IMPORT_SUCCEEDED else UsageAnalyticsEvent.TIMETABLE_IMPORT_FAILED,
                    if (success) mapOf("source" to "eas") else mapOf("source" to "eas", "error_category" to if (value.state in setOf(DataState.STATE.NOT_LOGGED_IN, DataState.STATE.TOKEN_INVALID)) "authentication" else "unknown"))
                importOperation = null
            }
            super.setValue(value)
        }
    }
    val isUndergraduateLiveData = MutableLiveData<Boolean>()
    val scheduleStructureLiveData: MediatorLiveData<DataState<MutableList<TimePeriodInDay>>> =
        MediatorLiveData()
    val snapshotsLiveData: LiveData<DataState<List<TimetableVersionSnapshot>>> =
        selectedTermLiveData.switchMap { term ->
            if (term == null) {
                MutableLiveData<DataState<List<TimetableVersionSnapshot>>>(
                    DataState(emptyList(), DataState.STATE.SUCCESS)
                )
            } else {
                easRepo.getTimetableSnapshots(term)
            }
        }
    val restoreSnapshotLiveData = MediatorLiveData<DataState<TimetableVersionSnapshot>>()
    private var restoreSnapshotSource: LiveData<DataState<TimetableVersionSnapshot>>? = null
    private var scheduleInnerSource1: LiveData<DataState<MutableList<TimePeriodInDay>>>? = null
    private var scheduleInnerSource2: LiveData<DataState<MutableList<TimePeriodInDay>>>? = null

    init {
        startDateLiveData.value = DataState(Calendar.getInstance())
        benbuCalibrationConfirmedLiveData.value = true

        startDateLiveData.addSource(selectedTermLiveData) { term ->
            startDateSource?.let { startDateLiveData.removeSource(it) }
            if (term == null) {
                startDateLiveData.value = DataState(Calendar.getInstance())
                benbuCalibrationConfirmedLiveData.value = true
                return@addSource
            }
            val source = easRepo.getStartDateOfTerm(term)
            startDateSource = source
            startDateLiveData.addSource(source) { state ->
                startDateLiveData.value = resolveStartDateState(term, state)
                benbuCalibrationConfirmedLiveData.value = isBenbuCalibrationConfirmed(term)
            }
        }

        benbuCalibrationConfirmedLiveData.addSource(selectedTermLiveData) { term ->
            benbuCalibrationConfirmedLiveData.value = term?.let { isBenbuCalibrationConfirmed(it) } ?: true
        }

        scheduleStructureLiveData.addSource(selectedTermLiveData) { term ->
            scheduleInnerSource1?.let { scheduleStructureLiveData.removeSource(it) }
            if (term == null) return@addSource
            isUndergraduateLiveData.value?.let { isu ->
                val src = easRepo.getScheduleStructure(term, isu)
                scheduleInnerSource1 = src
                scheduleStructureLiveData.addSource(src) { itt ->
                    scheduleStructureLiveData.value = itt
                }
            }
        }
        scheduleStructureLiveData.addSource(isUndergraduateLiveData) { isu ->
            scheduleInnerSource2?.let { scheduleStructureLiveData.removeSource(it) }
            selectedTermLiveData.value?.let { st ->
                val src = easRepo.getScheduleStructure(st, isu)
                scheduleInnerSource2 = src
                scheduleStructureLiveData.addSource(src) { itt ->
                    scheduleStructureLiveData.value = itt
                }
            }
        }
    }

    fun startRefreshTerms() {
        termsController.value = Trigger.actioning
    }

    fun changeSelectedTerm(termItem: TermItem) {
        selectedTermLiveData.value = termItem
    }

    fun changeIsUndergraduate(isUnder: Boolean) {
        // 威海只有本科生作息：忽略任何切换到“研究生结构”的请求。
        if (isWeihaiTerm() && !isUnder) return
        isUndergraduateLiveData.value = isUnder
    }

    fun startGetAllTerms(): List<TermItem> {
        return termsLiveData.value?.data ?: listOf()
    }

    fun startImportTimetable(): Boolean {
        selectedTermLiveData.value?.let { term ->
            startDateLiveData.value?.let { date ->
                scheduleStructureLiveData.value?.let { schedule ->
                    if (schedule.data != null && date.state == DataState.STATE.SUCCESS && date.data != null) {
                        importOperation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.TIMETABLE_IMPORT_STARTED, mapOf("source" to "eas"))
                        easRepo.startImportTimetableOfTerm(
                            term,
                            date.data!!,
                            schedule.data!!,
                            importTimetableResultLiveData
                        )
                        return true
                    }
                }
            }
        }
        return false
    }

    fun retryImportTimetable(): Boolean {
        return startImportTimetable()
    }

    fun restoreSnapshot(snapshotId: String) {
        restoreSnapshotSource?.let(restoreSnapshotLiveData::removeSource)
        val source = easRepo.restoreTimetableSnapshot(snapshotId)
        restoreSnapshotSource = source
        restoreSnapshotLiveData.addSource(source) { state ->
            restoreSnapshotLiveData.value = state
            if (state.state != DataState.STATE.NOTHING) {
                restoreSnapshotLiveData.removeSource(source)
                restoreSnapshotSource = null
                if (state.state == DataState.STATE.SUCCESS) {
                    selectedTermLiveData.value = selectedTermLiveData.value
                }
            }
        }
    }

    fun refreshSnapshots() {
        selectedTermLiveData.value = selectedTermLiveData.value
    }


    fun setStructureData(periodInDay: TimePeriodInDay, position: Int) {
        if (position < (scheduleStructureLiveData.value?.data?.size ?: 0)) {
            scheduleStructureLiveData.value?.data?.set(position, periodInDay)
            scheduleStructureLiveData.value = scheduleStructureLiveData.value
        }
    }

    fun changeStartDate(date: Calendar) {
        startDateLiveData.value = DataState(cloneCalendar(date))
    }

    fun shiftStartDateByWeek(offsetWeeks: Int) {
        val current = startDateLiveData.value?.data ?: return
        val shifted = cloneCalendar(current).apply {
            add(Calendar.DAY_OF_MONTH, offsetWeeks * 7)
        }
        startDateLiveData.value = DataState(shifted)
    }

    fun saveBenbuCalibration() {
        val term = selectedTermLiveData.value ?: return
        val date = startDateLiveData.value?.data ?: return
        if (!isBenbuTerm(term)) return
        benbuStartDatePreference.saveCalibration(term.getCode(), date.timeInMillis, true)
        benbuCalibrationConfirmedLiveData.value = true
    }

    fun isBenbuTerm(term: TermItem? = selectedTermLiveData.value): Boolean {
        return term != null && easRepo.getEasToken().isBenbuCampus()
    }

    /**
     * 威海老教务（jwts）只有本科生作息，不存在“本科生/研究生结构”之分。
     * 导入页由此隐藏切换开关（对齐 iOS 行为）。
     */
    fun isWeihaiTerm(term: TermItem? = selectedTermLiveData.value): Boolean {
        return term != null && easRepo.getEasToken().campus == EASToken.Campus.WEIHAI
    }

    private fun resolveStartDateState(term: TermItem, state: DataState<Calendar>): DataState<Calendar> {
        val sourceDate = state.data ?: return state
        val resolved = cloneCalendar(sourceDate)
        if (isBenbuTerm(term)) {
            benbuStartDatePreference.getStartDateMillis(term.getCode())?.let {
                resolved.timeInMillis = it
            }
        }
        return DataState(resolved, state.state).apply {
            message = state.message
        }
    }

    private fun isBenbuCalibrationConfirmed(term: TermItem): Boolean {
        return !isBenbuTerm(term) || benbuStartDatePreference.isConfirmed(term.getCode())
    }

    private fun cloneCalendar(calendar: Calendar): Calendar {
        return (calendar.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
