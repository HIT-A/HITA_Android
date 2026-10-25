package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.limpu.component.data.MTransformations
import com.limpu.component.data.Trigger
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.DisplayScopeSelector
import cn.limpu.hita.data.repository.ResolvedDisplayScope
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableChangeState
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.repository.TimetableSourceApplier
import cn.limpu.hita.data.repository.TimetableStyleRepository
import cn.limpu.hita.data.repository.KEY_WALLPAPER_PATH
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCaseFactory
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.data.model.timetable.WEEK_MILLS
import cn.limpu.hita.ui.subject.SubjectBatchDeleteScope
import cn.limpu.hita.ui.subject.SubjectBatchEditScope
import cn.limpu.hita.ui.main.timetable.TimetableFragment.Companion.WINDOW_SIZE
import com.limpu.component.data.DataState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val timetableStyleRepository: TimetableStyleRepository,
    private val changeStore: TimetableChangeStore,
    private val sourceApplier: TimetableSourceApplier,
    private val scopeResolver: DisplayScopeResolver,
    private val scopeSelector: DisplayScopeSelector,
    private val academicCalendarRepository: cn.limpu.hita.data.repository.AcademicCalendarRepository,
    writeUseCaseFactory: TimetableWriteUseCaseFactory,
) : ViewModel() {
    private val timetableWrites = writeUseCaseFactory.create(timetableRepository)

    private val timetableController = MutableLiveData<Trigger>()
    val timetableLiveData: LiveData<List<Timetable>> = timetableController.switchMap{
            return@switchMap timetableRepository.getTimetables()
        }
    /** 课表变更状态：刷新摘要、待确认决策、整批挂起。 */
    val changeStateLiveData: LiveData<TimetableChangeState> = changeStore.observeState()
    /** 用户决策（采用/保留）的执行结果，供界面提示失败原因。 */
    private val decisionResultLiveData = MutableLiveData<DataState<Boolean>>()
    val decisionResult: LiveData<DataState<Boolean>> = decisionResultLiveData
    private val batchSessionLiveData = MutableLiveData<TimetableBatchSession?>(null)
    internal val batchSession: LiveData<TimetableBatchSession?> = batchSessionLiveData
    val startTimeLiveData: LiveData<Int>
        get() = timetableStyleRepository.startTimeLiveData
    val periodLabelLiveData: LiveData<Boolean>
        get() = timetableStyleRepository.periodLabelLiveData
    val eveningHintLiveData: LiveData<Boolean>
        get() = timetableStyleRepository.eveningHintLiveData
    fun isEveningHintEnabled(): Boolean = timetableStyleRepository.isEveningHintEnabled()
    fun currentStartTime(): Int = timetableStyleRepository.currentStartTime()
    fun isPeriodLabelEnabled(): Boolean = timetableStyleRepository.isPeriodLabelEnabled()
    fun isZoomCompressed(): Boolean = timetableStyleRepository.isZoomCompressed()
    fun wallpaperPath(): String = timetableStyleRepository.wallpaperPath()
    val zoomCompressedLiveData: LiveData<Boolean>
        get() = timetableStyleRepository.zoomCompressedLiveData
    val wallpaperPathLiveData: LiveData<String>
        get() = timetableStyleRepository.wallpaperPathLiveData
    val wallpaperDateColorLiveData: LiveData<Int>
        get() = timetableStyleRepository.wallpaperDateColorLiveData
    val wallpaperLabelColorLiveData: LiveData<Int>
        get() = timetableStyleRepository.wallpaperLabelColorLiveData

    var currentPageStartDate: MutableLiveData<Long>
    var currentIndex = 0
    var startIndex = 0

    private val timetableStyleLiveData: LiveData<TimetableStyleSheet> =
        timetableStyleRepository.getStyleSheetLiveData()

    /** 当前显示目标（独立课表或组合课表）；主课表/标题/添加事件的归属都经它。 */
    val resolvedScope: LiveData<ResolvedDisplayScope?> = scopeResolver.resolved
    val academicCalendarPeriods = academicCalendarRepository.periods
    val scopeOptions: LiveData<List<DisplayScopeResolver.ScopeOption>> = scopeResolver.options

    /** 显示目标内全部课程的存储色（subjectId → color）；颜色分配器输入,不随周导航变化。 */
    val scopeCourseColors: LiveData<Map<String, Int>> = scopeResolver.resolved.switchMap { scope ->
        if (scope == null || scope.timetableIds.isEmpty()) {
            return@switchMap MutableLiveData(emptyMap())
        }
        MTransformations.map(timetableRepository.getSubjectColorsOfTimetables(scope.timetableIds)) { colors ->
            colors.associate { it.id to it.color }
        }
    }

    fun selectScope(scope: DisplayScope) = scopeSelector.select(scope)

    val windowEventsData: MutableList<MediatorLiveData<EventStylePair>> =
        mutableListOf()
    val windowStartData: MutableList<MutableLiveData<Long>> = mutableListOf()
    val windowHashesData = mutableListOf<Int>()

    init {
        val ws = Calendar.getInstance()
        ws.firstDayOfWeek = Calendar.MONDAY
        ws[Calendar.DAY_OF_WEEK] = Calendar.MONDAY
        ws[Calendar.HOUR_OF_DAY] = 0
        ws[Calendar.MINUTE] = 0
        ws[Calendar.SECOND] = 0
        ws[Calendar.MILLISECOND] = 0
        currentPageStartDate = MutableLiveData(ws.timeInMillis)
        for (i in 0 until WINDOW_SIZE) {
            windowHashesData.add(0)
            val startLD = MutableLiveData<Long>()
            windowStartData.add(startLD)
            val scopeAndStart = MediatorLiveData<Pair<Long, List<String>>>()
            fun refreshScopeAndStart() {
                val start = startLD.value ?: return
                // resolver 尚未产出时保持空窗,避免闪现全量叠加事件。
                val ids = scopeResolver.resolved.value?.timetableIds.orEmpty()
                val current = scopeAndStart.value
                val next = start to ids
                if (current != next) scopeAndStart.value = next
            }
            scopeAndStart.addSource(startLD) { refreshScopeAndStart() }
            scopeAndStart.addSource(scopeResolver.resolved) { refreshScopeAndStart() }
            val eventsRawData = scopeAndStart.switchMap { (start, ids) ->
                return@switchMap timetableRepository.getEventsDuringWithColor(
                    start,
                    start + WEEK_MILLS,
                    ids
                )
            }
            val eventsData = MTransformations.switchMap(eventsRawData,timetableStyleLiveData){
                return@switchMap MutableLiveData(EventStylePair(it.first, it.second))
            }
            windowEventsData.add(eventsData)
        }
    }

    fun startRefresh() {
        viewModelScope.launch(Dispatchers.IO) { academicCalendarRepository.refresh() }
        timetableRepository.actionPrepareTimetableList()
        timetableController.value = Trigger.actioning
    }

    /** 用户点开变更详情：信息类摘要查看后清除，待确认项保留。 */
    fun markChangeInfoViewed() {
        changeStore.markInfoViewed()
    }

    fun adoptIncomingCourse(termId: String, courseKey: String) {
        runDecision { sourceApplier.adoptCourse(termId, courseKey) }
    }

    fun dismissIncomingCourse(termId: String, courseKey: String) {
        runDecision { sourceApplier.dismissCourse(termId, courseKey) }
    }

    fun adoptHeldBatch() {
        runDecision { sourceApplier.adoptHeldBatch() }
    }

    fun dismissHeldBatch() {
        runDecision { sourceApplier.dismissHeldBatch() }
    }

    fun confirmPendingChanges(adopted: Set<String>, remember: Collection<String>) {
        runDecision { sourceApplier.applyPendingConfirmations(adopted, remember) }
    }

    internal fun beginBatchEdit(session: TimetableBatchSession) {
        batchSessionLiveData.postValue(session)
    }

    internal fun clearBatchSession() {
        batchSessionLiveData.postValue(null)
    }

    fun updateEvents(events: Collection<EventItem>) {
        timetableWrites.update(events)
    }

    fun deleteEvents(events: Collection<EventItem>) {
        timetableWrites.delete(events)
    }

    fun classesOfSubjectSync(subjectId: String): List<EventItem> =
        timetableRepository.getClassesOfSubjectSync(subjectId)

    fun timetableByIdSync(id: String): Timetable? = timetableRepository.getTimetableByIdSync(id)

    private fun runDecision(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { block() }
            result.exceptionOrNull()?.let { error ->
                LogUtils.e("timetable decision failed", error)
            }
            decisionResultLiveData.postValue(
                if (result.isSuccess) {
                    DataState(true, DataState.STATE.SUCCESS)
                } else {
                    DataState(DataState.STATE.FETCH_FAILED, result.exceptionOrNull()?.message)
                }
            )
        }
    }

    fun clearWallpaperPath() {
        timetableStyleRepository.putData(KEY_WALLPAPER_PATH, "")
    }

    fun addStartDate(offset: Long) {
        currentPageStartDate.value?.let {
            currentPageStartDate.value = it + offset
        }
    }

}

internal data class TimetableBatchSession(
    val edit: Boolean,
    val editScope: SubjectBatchEditScope,
    val deleteScope: SubjectBatchDeleteScope,
    val events: List<EventItem>,
    val timetable: Timetable?,
)

/** Wrapper that uses reference equality so Compose always recomposes on new emissions. */
class EventStylePair(
    val events: List<EventItem>,
    val style: TimetableStyleSheet
)
