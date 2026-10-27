package cn.limpu.hita.ui.eas.imp

import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.limpu.component.data.DataState
import com.limpu.component.data.Trigger
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableVersionSnapshot
import cn.limpu.hita.data.source.preference.BenbuStartDatePreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.ui.eas.EASViewModel
import cn.limpu.hita.ui.eas.TermQueryLiveData
import cn.limpu.hita.ui.eas.reconcileSelectedTerm
import cn.limpu.hita.ui.eas.FiniteEasQueryLiveData
import cn.limpu.hita.data.repository.copyForQuery
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import cn.limpu.hita.ui.eas.TermSelectionDisplaySource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class ImportTimetableViewModel @Inject constructor(
    easRepo: EASRepository,
    private val benbuStartDatePreference: BenbuStartDatePreferenceSource,
    private val timetablePreferenceSource: TimetablePreferenceSource
) : EASViewModel(easRepo) {

    private var startDateSource: LiveData<DataState<Calendar>>? = null
    private var termSession = easRepo.captureQuerySession()
    private val termQueries = TermQueryLiveData(viewModelScope, easRepo.querySessions,
        createRequest = {
            termSession = easRepo.captureQuerySession()
            easRepo.createTermQueryRequest(termSession)
        },
        onSessionChanged = { cancelSelectionOperations(); selectedTermLiveData.value = null; selectedTermDisplay.updateOptions(emptyList()) },
        onResult = { session, state ->
            termSession = session
            if (state.state == DataState.STATE.SUCCESS) {
                selectedTermDisplay.updateOptions(state.data.orEmpty())
                val selected = reconcileSelectedTerm(state.data.orEmpty(), selectedTermLiveData.value)
                if (selected == null) selectedTermLiveData.value = null else changeSelectedTerm(selected)
            }
        },
    )
    val termsLiveData: LiveData<DataState<List<TermItem>>> = termQueries

    val selectedTermLiveData: MutableLiveData<TermItem?> = MutableLiveData()
    internal val selectedTermDisplay = TermSelectionDisplaySource(selectedTermLiveData)
    val startDateLiveData = MediatorLiveData<DataState<Calendar>>()
    val benbuCalibrationConfirmedLiveData = MediatorLiveData<Boolean>()
    private var importOperation: UsageAnalyticsClient.Operation? = null
    val importTimetableResultLiveData = object : MutableLiveData<DataState<Boolean>>() {
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
    val scheduleStructureLiveData: MediatorLiveData<DataState<MutableList<TimePeriodInDay>>> =
        MediatorLiveData()
    val snapshotsLiveData = MediatorLiveData<DataState<List<TimetableVersionSnapshot>>>()
    private var snapshotsSource: LiveData<DataState<List<TimetableVersionSnapshot>>>? = null
    val restoreSnapshotLiveData = MutableLiveData<DataState<TimetableVersionSnapshot>>()
    private var restoreJob: Job? = null
    private var restoreGeneration = 0L
    private var importJob: Job? = null
    private var importGeneration = 0L
    private var scheduleInnerSource1: LiveData<DataState<MutableList<TimePeriodInDay>>>? = null

    init {
        startDateLiveData.value = DataState(Calendar.getInstance())
        benbuCalibrationConfirmedLiveData.value = true

        startDateLiveData.addSource(selectedTermLiveData) { term ->
            startDateSource?.let { startDateLiveData.removeSource(it) }
            startDateSource = null
            if (term == null) {
                startDateLiveData.value = DataState(Calendar.getInstance())
                benbuCalibrationConfirmedLiveData.value = true
                return@addSource
            }
            if (!termSession.loggedIn) {
                startDateLiveData.value = DataState(DataState.STATE.NOT_LOGGED_IN)
                return@addSource
            }
            startDateLiveData.value = DataState(DataState.STATE.LOADING)
            val session = termSession
            val capturedTerm = term.copyForQuery()
            val source = FiniteEasQueryLiveData(viewModelScope, session) {
                easRepo.getStartDateOfTermOnce(session, capturedTerm)
            }
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
            scheduleInnerSource1 = null
            if (term == null) {
                scheduleStructureLiveData.value = DataState(DataState.STATE.NOTHING)
                return@addSource
            }
            val isUndergraduate = isUndergraduateStudent()
            // 用户已编辑过课表结构（SP 有值）时以用户偏好为准，否则用教务接口/默认值。
            if (!termSession.loggedIn) {
                scheduleStructureLiveData.value = DataState(DataState.STATE.NOT_LOGGED_IN)
            } else if (timetablePreferenceSource.hasSavedSchedule()) {
                scheduleStructureLiveData.value = DataState(
                    timetablePreferenceSource.getSchedule(isUndergraduate), DataState.STATE.SUCCESS
                )
            } else {
                scheduleStructureLiveData.value = DataState(DataState.STATE.LOADING)
                val session = termSession
                val capturedTerm = term.copyForQuery()
                val src = FiniteEasQueryLiveData(viewModelScope, session) {
                    easRepo.getScheduleStructureOnce(session, capturedTerm, isUndergraduate)
                }
                scheduleInnerSource1 = src
                scheduleStructureLiveData.addSource(src) { itt ->
                    scheduleStructureLiveData.value = itt
                }
            }
        }
        snapshotsLiveData.addSource(selectedTermLiveData) { loadSnapshots() }
    }

    private fun loadSnapshots() {
        snapshotsSource?.let(snapshotsLiveData::removeSource)
        snapshotsSource = null
        val term = selectedTermDisplay.current()
        if (term == null) {
            snapshotsLiveData.value = DataState(emptyList(), DataState.STATE.SUCCESS)
            return
        }
        val session = termSession
        val captured = term.copyForQuery()
        val source = FiniteEasQueryLiveData(viewModelScope, session) { easRepo.getTimetableSnapshots(session, captured) }
        snapshotsSource = source
        snapshotsLiveData.addSource(source) { snapshotsLiveData.value = it }
    }

    /**
     * 作息结构按登录身份自动选择：本科生取本科作息，研究生取研究生作息。
     *
     * 导入页已取消“本科生结构/研究生结构”手动切换，避免同一账号出现自相矛盾的作息表；
     * 威海老教务没有研究生作息，其 [cn.limpu.hita.data.source.web.eas.WeihaiEASWebSource]
     * 无论传什么都返回本科作息表，因此这里统一按身份传入即可。
     */
    private fun isUndergraduateStudent(): Boolean =
        termSession.studentType == EASToken.TYPE.UNDERGRAD

    fun startRefreshTerms() {
        termQueries.refresh()
    }

    fun changeSelectedTerm(termItem: TermItem) {
        selectedTermDisplay.updateTerm(termItem)
        val current = selectedTermLiveData.value
        if (current?.yearCode == termItem.yearCode && current?.termCode == termItem.termCode) return
        cancelSelectionOperations()
        selectedTermLiveData.value = termItem
    }

    private fun cancelSelectionOperations() {
        val cancelledImport = importJob?.isActive == true
        importGeneration++
        importJob?.cancel()
        importJob = null
        if (cancelledImport) {
            importOperation = null
            importTimetableResultLiveData.value = DataState(DataState.STATE.NOTHING)
        }
        restoreGeneration++
        restoreJob?.cancel()
        restoreJob = null
    }

    fun filterTermOptions(terms: List<TermItem>): List<TermItem> =
        cn.limpu.hita.utils.TermUtils.filterTermsForTimetableImport(terms, termSession.grade)

    fun startGetAllTerms(): List<TermItem> {
        return termsLiveData.value?.data ?: listOf()
    }

    fun startImportTimetable(): Boolean {
        if (!termSession.isCurrent() || importJob?.isActive == true) return false
        val term = selectedTermDisplay.current()?.copyForQuery() ?: return false
        val date = startDateLiveData.value?.takeIf { it.state == DataState.STATE.SUCCESS }?.data ?: return false
        val schedule = scheduleStructureLiveData.value?.takeIf { it.state == DataState.STATE.SUCCESS }?.data ?: return false
        val capturedDate = cloneCalendar(date)
        val capturedSchedule = schedule.map { it.clone() }
        val session = termSession
        val generation = ++importGeneration
        importOperation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.TIMETABLE_IMPORT_STARTED, mapOf("source" to "eas"))
        importTimetableResultLiveData.value = DataState(DataState.STATE.LOADING)
        importJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = easRepo.importTimetableOfTerm(session, term, capturedDate, capturedSchedule)
            if (isActive && generation == importGeneration && session.isCurrent()) {
                importTimetableResultLiveData.value = result
                if (result.state == DataState.STATE.SUCCESS) refreshSnapshots()
            }
        }.also { it.start() }
        return true
    }

    fun retryImportTimetable(): Boolean = startImportTimetable()

    fun restoreSnapshot(snapshotId: String) {
        restoreJob?.cancel()
        val generation = ++restoreGeneration
        val session = termSession
        restoreSnapshotLiveData.value = DataState(DataState.STATE.LOADING)
        restoreJob = viewModelScope.launch {
            val result = easRepo.restoreTimetableSnapshot(session, snapshotId)
            if (isActive && generation == restoreGeneration && session.isCurrent()) {
                restoreSnapshotLiveData.value = result
                if (result.state == DataState.STATE.SUCCESS) refreshSnapshots()
            }
        }
    }

    fun refreshSnapshots() = loadSnapshots()

    fun setStructureData(periodInDay: TimePeriodInDay, position: Int) {
        val current = scheduleStructureLiveData.value ?: return
        val list = current.data ?: return
        if (position !in list.indices) return
        // A local edit owns this selection; an outstanding response must not overwrite it.
        scheduleInnerSource1?.let(scheduleStructureLiveData::removeSource)
        scheduleInnerSource1 = null
        // 必须发布一个新列表 + 新 DataState 对象：Compose 的 observeAsState 对同一个
        // 对象引用会因 equals 短路而跳过重组，导致编辑后行内时间不刷新。
        val newList = list.toMutableList().apply { this[position] = periodInDay }
        // 持久化用户编辑：“SP 有值”即代表用户偏好作息，下次进导入页优先使用；
        // 导入时该结构会被写入 Timetable.scheduleStructure。
        timetablePreferenceSource.saveSchedules(newList)
        scheduleStructureLiveData.value = DataState(newList, current.state).apply {
            message = current.message
            listAction = current.listAction
            fromCache = current.fromCache
            stateRetried = current.stateRetried
        }
    }

    fun changeStartDate(date: Calendar) {
        startDateSource?.let(startDateLiveData::removeSource)
        startDateSource = null
        startDateLiveData.value = DataState(cloneCalendar(date))
    }

    fun shiftStartDateByWeek(offsetWeeks: Int) {
        val current = startDateLiveData.value?.data ?: return
        val shifted = cloneCalendar(current).apply {
            add(Calendar.DAY_OF_MONTH, offsetWeeks * 7)
        }
        changeStartDate(shifted)
    }

    fun saveBenbuCalibration() {
        val term = selectedTermLiveData.value ?: return
        val date = startDateLiveData.value?.data ?: return
        if (!isBenbuTerm(term)) return
        benbuStartDatePreference.saveCalibration(term.getCode(), date.timeInMillis, true)
        benbuCalibrationConfirmedLiveData.value = true
    }

    fun isBenbuTerm(term: TermItem? = selectedTermLiveData.value): Boolean {
        return term != null && termSession.campus == EASToken.Campus.BENBU
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
