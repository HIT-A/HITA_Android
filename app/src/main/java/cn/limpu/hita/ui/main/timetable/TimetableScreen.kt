package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import cn.limpu.hita.data.model.timetable.WEEK_MILLS
import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import cn.limpu.hita.ui.main.timetable.compare.*
import cn.limpu.hita.data.repository.ResolvedDisplayScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.lifecycle.map
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.TimetableDecisionItem
import cn.limpu.hita.data.repository.TimetableHeldBatch
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.LocalCourseColorAssignments
import cn.limpu.hita.ui.design.TimetableColorAssigner
import cn.limpu.hita.ui.design.hitaColorToArgb
import cn.limpu.hita.ui.design.hitaCoursePalette
import com.limpu.style.ThemeTools
import cn.limpu.hita.ui.subject.SubjectBatchDeleteDialog
import cn.limpu.hita.ui.subject.SubjectBatchEditDialog

internal sealed interface TimetableTitleState {
    data class Single(val title: String) : TimetableTitleState
    data class Week(val title: String, val name: String) : TimetableTitleState
}

@Composable
internal fun TimetableRoute(
    viewModel: TimetableViewModel,
    onTitleState: (TimetableTitleState) -> Unit,
    onEventClick: (EventItem) -> Unit,
    onEventLongClick: (EventItem, IntOffset) -> Unit,
    onAddClick: (Int, TimePeriodInDay) -> Unit,
    onChangeInfoViewed: () -> Unit,
    onAdoptCourse: (TimetableDecisionItem) -> Unit,
    onDismissCourse: (TimetableDecisionItem) -> Unit,
    onAdoptBatch: (TimetableHeldBatch) -> Unit,
    onDismissBatch: (TimetableHeldBatch) -> Unit,
    onConfirmPending: (Set<String>, Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val currentPageStart by viewModel.currentPageStartDate.observeAsState(
        initial = viewModel.currentPageStartDate.value ?: mondayOf(System.currentTimeMillis())
    )
    val resolvedScope by viewModel.resolvedScope.observeAsState()
    val calendarPeriods by viewModel.academicCalendarPeriods.observeAsState(emptyList())
    val startTime by viewModel.startTimeLiveData.observeAsState(viewModel.currentStartTime())
    val periodLabel by viewModel.periodLabelLiveData.observeAsState(viewModel.isPeriodLabelEnabled())
    val wallpaperPath by viewModel.wallpaperPathLiveData.observeAsState(viewModel.wallpaperPath())
    val eveningHintEnabled by viewModel.eveningHintLiveData.observeAsState(viewModel.isEveningHintEnabled())
    val zoomCompressed by viewModel.zoomCompressedLiveData.observeAsState(viewModel.isZoomCompressed())
    val dateColorInt by viewModel.wallpaperDateColorLiveData.observeAsState(AndroidColor.WHITE)
    val labelColorInt by viewModel.wallpaperLabelColorLiveData.observeAsState(AndroidColor.WHITE)
    val windowEvents by viewModel.currentWindow.observeAsState()

    LaunchedEffect(currentPageStart, resolvedScope, calendarPeriods) {
        onTitleState(buildTitleState(context, currentPageStart, resolvedScope, calendarPeriods))
    }

    val style = remember(windowEvents, startTime, periodLabel) {
        (windowEvents?.style ?: TimetableStyleSheet()).copy(
            startTime = startTime,
            usePeriodLabel = periodLabel,
        )
    }
    val events = windowEvents?.events.orEmpty()

    // 课程色分配：按显示目标全量课程稳定占槽,同屏课程两两不同色;P5/Sumi 的全同色是设计意图,跳过。
    val scopeCourseColors by viewModel.scopeCourseColors.observeAsState(emptyMap())
    val currentPaletteArgb = hitaCoursePalette().map(::hitaColorToArgb).toIntArray()
    val colorAssignerEnabled = HitaTheme.preferenceStyle != ThemeTools.STYLE.P5 &&
        HitaTheme.preferenceStyle != ThemeTools.STYLE.SUMI
    val colorAssignments: Map<String, Int>? = remember(events, scopeCourseColors, currentPaletteArgb, colorAssignerEnabled) {
        if (!colorAssignerEnabled) {
            null
        } else {
            val keys = LinkedHashSet<String>()
            val stored = HashMap<String, Int>()
            scopeCourseColors.forEach { (id, color) -> keys += id; stored[id] = color }
            events.filter { it.subjectId.isBlank() }.forEach {
                keys += it.name
                stored.putIfAbsent(it.name, it.color)
            }
            TimetableColorAssigner.assign(keys, stored, currentPaletteArgb)
        }
    }
    val displayStyle = style
    val currentScheduleStructure = remember(resolvedScope, currentPageStart) {
        // 作息结构与周数同源:显示目标内当前周命中的课表;未命中用目标内最早课表,无课表用默认结构。
        val scope = resolvedScope ?: return@remember Timetable().scheduleStructure
        val hit = TimetableTargetPolicy.scheduleTimetable(scope.timetables, currentPageStart)
        hit?.scheduleStructure ?: Timetable().scheduleStructure
    }
    val showTodayFab = currentPageStart > System.currentTimeMillis() ||
            System.currentTimeMillis() >= currentPageStart + WEEK_MILLS

    val changeState by viewModel.changeStateLiveData.observeAsState()
    val batchState by viewModel.batches.state.collectAsState()
    // Capture the rendered form's identity, not the delegated State's value at callback time.
    val batchSession = batchState.session
    TimetableScreen(
        state = TimetableScreenState(
            currentPageStart, events, displayStyle, currentScheduleStructure, wallpaperPath,
            dateColorInt, labelColorInt, eveningHintEnabled, zoomCompressed, colorAssignments,
            showTodayFab, changeState, batchState,
        ),
        onWeek = viewModel::navigateToWeek,
        onEventClick = onEventClick,
        onEventLongClick = onEventLongClick,
        onAddClick = onAddClick,
        onChangeInfoViewed = onChangeInfoViewed,
        onAdoptCourse = onAdoptCourse,
        onDismissCourse = onDismissCourse,
        onAdoptBatch = onAdoptBatch,
        onDismissBatch = onDismissBatch,
        onConfirmPending = onConfirmPending,
        onCancelBatch = { viewModel.batches.cancel(batchSession) },
        onApplyBatch = { place, teacher, time, slots -> viewModel.batches.apply(batchSession, place, teacher, time, slots) },
        onDeleteBatch = { viewModel.batches.delete(batchSession) },
    )
}

internal data class TimetableScreenState(
    val currentPageStart: Long,
    val events: List<EventItem>,
    val style: TimetableStyleSheet,
    val schedule: List<TimePeriodInDay>,
    val wallpaperPath: String,
    val dateColor: Int,
    val labelColor: Int,
    val eveningHintEnabled: Boolean,
    val compressed: Boolean,
    val colorAssignments: Map<String, Int>?,
    val showToday: Boolean,
    val changes: cn.limpu.hita.data.repository.TimetableChangeState?,
    val batch: TimetableBatchState,
)

@Composable
internal fun TimetableScreen(
    state: TimetableScreenState,
    onWeek: (Long) -> Unit,
    onEventClick: (EventItem) -> Unit,
    onEventLongClick: (EventItem, IntOffset) -> Unit,
    onAddClick: (Int, TimePeriodInDay) -> Unit,
    onChangeInfoViewed: () -> Unit,
    onAdoptCourse: (TimetableDecisionItem) -> Unit,
    onDismissCourse: (TimetableDecisionItem) -> Unit,
    onAdoptBatch: (TimetableHeldBatch) -> Unit,
    onDismissBatch: (TimetableHeldBatch) -> Unit,
    onConfirmPending: (Set<String>, Set<String>) -> Unit,
    onCancelBatch: () -> Unit,
    onApplyBatch: (String?, String?, Triple<Int, Int, Int>?, Map<Triple<Int, Int, Int>, Triple<Int, Int, Int>>) -> Unit,
    onDeleteBatch: () -> Unit,
) {
    val currentPageStart = state.currentPageStart
    val changeState = state.changes
    Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalCourseColorAssignments provides state.colorAssignments) {
                TimetableWeekContent(
                    startDate = currentPageStart,
                    events = state.events,
                    style = state.style,
                    scheduleStructure = state.schedule,
                    dateColor = if (state.wallpaperPath.isBlank()) MaterialTheme.colorScheme.onSurface else Color(state.dateColor),
                    labelColor = if (state.wallpaperPath.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color(state.labelColor),
                    onPrevWeek = {
                        onWeek(currentPageStart - WEEK_MILLS)
                    },
                    onNextWeek = {
                        onWeek(currentPageStart + WEEK_MILLS)
                    },
                    onEventClick = onEventClick,
                    onEventLongClick = onEventLongClick,
                    onAddClick = onAddClick,
                    eveningHintEnabled = state.eveningHintEnabled,
                    compressed = state.compressed,
                )
                }
            }
        }

        if (state.showToday) {
            FloatingActionButton(
                onClick = { onWeek(mondayOf(System.currentTimeMillis())) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        bottom = dimensionResource(R.dimen.bottom_navigation_height) + HitaTheme.tokens.spacing.lg,
                        start = HitaTheme.tokens.spacing.lg,
                        top = HitaTheme.tokens.spacing.lg,
                        end = HitaTheme.tokens.spacing.lg,
                    )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                    contentDescription = null
                )
            }
        }

        var showChangeDialog by remember { mutableStateOf(false) }
        val changeInfo = changeState?.info
        val changePendingCount = changeState?.pendingCount ?: 0
        val changeAdjustCount = changeInfo?.keptCourses?.size ?: 0
        val pendingRows = changeState?.pendingUpdates.orEmpty().sumOf { it.rows.size }
        LaunchedEffect(
            changeState?.pendingRevision,
            changeState?.decisions?.size,
            changeState?.heldBatch != null,
        ) {
            if (pendingRows > 0 ||
                (changeState?.decisions?.isNotEmpty() == true) ||
                changeState?.heldBatch != null
            ) {
                showChangeDialog = true
            }
        }
        if (showChangeDialog) {
            changeState?.let { state ->
                TimetableChangeDialog(
                    state = state,
                    onDismiss = {
                        showChangeDialog = false
                        onChangeInfoViewed()
                    },
                    onAdoptCourse = onAdoptCourse,
                    onDismissCourse = onDismissCourse,
                    onAdoptBatch = onAdoptBatch,
                    onDismissBatch = onDismissBatch,
                    onConfirmPending = onConfirmPending,
                )
            }
        }
        if (changePendingCount > 0 || changeAdjustCount > 0) {
            TimetableChangePill(
                pending = changePendingCount > 0,
                count = if (changePendingCount > 0) changePendingCount else changeAdjustCount,
                onClick = { showChangeDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = dimensionResource(R.dimen.bottom_navigation_height) +
                            HitaTheme.tokens.spacing.lg + 44.dp,
                        start = HitaTheme.tokens.spacing.lg,
                        end = HitaTheme.tokens.spacing.lg,
                    )
            )
        }
        state.batch.session?.let { session -> key(session) {
            if (session.edit) {
                SubjectBatchEditDialog(
                    scope = session.editScope,
                    events = session.events,
                    timetable = session.timetable,
                    onDismiss = onCancelBatch,
                    onApply = onApplyBatch,
                )
            } else {
                SubjectBatchDeleteDialog(
                    scope = session.deleteScope,
                    count = session.events.size,
                    onDismiss = onCancelBatch,
                    onConfirm = onDeleteBatch,
                )
            }
        } }
    }
}

private fun buildTitleState(
    context: Context,
    currentPageStart: Long,
    scope: ResolvedDisplayScope?,
    periods: List<cn.limpu.hita.data.source.web.eas.AcademicCalendarPeriod>,
): TimetableTitleState {
    if (scope == null) {
        return TimetableTitleState.Single(context.getString(R.string.no_timetable))
    }
    val result = cn.limpu.hita.data.repository.TimetableCalendarTitle.resolve(scope.timetables, currentPageStart, periods)
    return TimetableTitleState.Week(
        title = when {
            result.week != null -> context.getString(R.string.week_title, result.week)
            result.vacation -> context.getString(R.string.holiday)
            else -> context.getString(R.string.outside_selected_term)
        },
        name = scope.name,
    )
}
