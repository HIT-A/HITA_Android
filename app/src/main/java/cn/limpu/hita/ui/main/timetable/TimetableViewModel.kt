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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
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
    internal val batches = TimetableBatchCoordinator(
        viewModelScope,
        classes = { id -> withContext(Dispatchers.IO) { timetableRepository.getClassesOfSubjectSync(id) } },
        timetable = { id -> withContext(Dispatchers.IO) { timetableRepository.getTimetableByIdSync(id) } },
        update = { events, completed -> timetableWrites.update(events, completed) },
        delete = { events, completed -> timetableWrites.delete(events, completed) },
    )
    private var viewOwner: Long? = null
    internal fun attachView(): Long = batches.attach().also { viewOwner = it }
    internal fun detachView(owner: Long) {
        if (viewOwner == owner) viewOwner = null
        batches.detach(owner)
    }
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

    private val pageStart = MutableLiveData(mondayOf(System.currentTimeMillis()))
    val currentPageStartDate: LiveData<Long> = pageStart

    fun navigateToWeek(mondayMillis: Long) {
        if (pageStart.value == mondayMillis) return
        batches.cancel()
        pageStart.value = mondayMillis
    }

    internal fun currentTimetableAndWeek(): Pair<Timetable?, Int?> {
        val start = pageStart.value ?: return null to null
        val table = TimetableTargetPolicy.hitTimetable(resolvedScope.value?.timetables.orEmpty(), start)
        return table to table?.getWeekNumber(start)
    }

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

    /** One observable window: Compose has no ring indices or writable intermediate dates. */
    private val scopeAndStart = MediatorLiveData<Pair<Long, List<String>>>().apply {
        fun refresh() {
            val next = (pageStart.value ?: return) to scopeResolver.resolved.value?.timetableIds.orEmpty()
            if (value != next) {
                batches.cancel()
                value = next
            }
        }
        addSource(pageStart) { refresh() }
        addSource(scopeResolver.resolved) { refresh() }
    }
    val currentWindow: LiveData<EventStylePair> = MTransformations.switchMap(
        scopeAndStart.switchMap { (start, ids) ->
            timetableRepository.getEventsDuringWithColor(start, start + WEEK_MILLS, ids)
        },
        timetableStyleLiveData,
    ) { MutableLiveData(EventStylePair(it.first, it.second)) }

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

    private fun runDecision(block: () -> Unit) {
        val owner = viewOwner ?: return
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { block() }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure<Unit>(error)
            }
            result.exceptionOrNull()?.let { error ->
                LogUtils.e("timetable decision failed", error)
                batches.reportFailure(owner, error)
            }
        }
    }

    fun clearWallpaperPath() {
        timetableStyleRepository.putData(KEY_WALLPAPER_PATH, "")
    }

    fun addStartDate(offset: Long) {
        currentPageStartDate.value?.let {
            navigateToWeek(it + offset)
        }
    }

}

/** Wrapper that uses reference equality so Compose always recomposes on new emissions. */
class EventStylePair(
    val events: List<EventItem>,
    val style: TimetableStyleSheet
)

internal fun mondayOf(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    firstDayOfWeek = Calendar.MONDAY
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
