package cn.limpu.hita.ui.main.timetable

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import javax.inject.Inject
import cn.limpu.hita.ui.main.timetable.compare.*
import cn.limpu.hita.data.repository.FriendTimetableRepository
import cn.limpu.hita.ui.timetable.friend.FriendTimetableActivity
import cn.limpu.hita.ui.timetable.share.TimetableShareActivity
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.map
import androidx.fragment.app.viewModels
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.TimetableDecisionItem
import cn.limpu.hita.data.repository.TimetableHeldBatch
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseFragment
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaCourseBubbleTreatment
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaCourseColor
import cn.limpu.hita.ui.design.hitaCourseCrystalGlassModifier
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface
import cn.limpu.hita.ui.design.hitaIsCyber
import cn.limpu.hita.ui.design.hitaIsDeepSpace
import cn.limpu.hita.ui.design.hitaIsPersona
import cn.limpu.hita.ui.design.hitaIsSumi
import cn.limpu.hita.ui.design.hitaIsSoraCloud
import cn.limpu.hita.ui.design.hitaPersonaTitleFont
import cn.limpu.hita.ui.design.hitaSoraTitleFont
import cn.limpu.hita.ui.design.hitaSumiTitleFont
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.design.soraCloudCardShape
import cn.limpu.hita.ui.design.soraCloudCourseContentColor
import cn.limpu.hita.ui.design.SoraIndigo
import cn.limpu.hita.ui.design.SoraOchre
import cn.limpu.hita.ui.design.SoraVermilion
import cn.limpu.hita.ui.event.add.PopupAddEvent
import cn.limpu.hita.ui.main.timetable.views.TimetableCardTextScale
import cn.limpu.hita.ui.main.timetable.views.TimetableOverlapLayout
import cn.limpu.hita.ui.main.timetable.views.TimetableOverlapLayout.PositionedEvent
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.ui.subject.SubjectBatchDeleteDialog
import cn.limpu.hita.ui.subject.SubjectBatchDeleteScope
import cn.limpu.hita.ui.subject.SubjectBatchEditDialog
import cn.limpu.hita.ui.subject.SubjectBatchEditScope
import cn.limpu.hita.ui.subject.applySubjectBatchEdit
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.EventsUtils
import dagger.hilt.android.AndroidEntryPoint
import java.time.ZoneId
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class TimetableFragment : HiltBaseFragment<ComposeViewBinding>() {

    protected val viewModel: TimetableViewModel by viewModels()
    val comparisonViewModel: TimetableComparisonViewModel by viewModels()
    private val comparisonChoices by lazy {
        viewModel.timetableLiveData.map { tables -> tables.map(::comparisonPersonalChoice) }
    }
    @Inject lateinit var friendTimetableRepository: FriendTimetableRepository
    private var socialSheetOpen by mutableStateOf(false)
    private var comparisonSheetOpen by mutableStateOf(false)

    fun openTimetableSocialSheet() { socialSheetOpen = true }
    fun openComparisonConfigSheet() { socialSheetOpen = false; comparisonSheetOpen = true }

    private var mainPageController: MainPageController? = null

    companion object {
        const val WINDOW_SIZE: Int = 5
        const val WEEK_MILLS: Long = 1000 * 60 * 60 * 24 * 7
    }

    fun navigateToWeek(mondayMillis: Long) {
        viewModel.currentPageStartDate.value = mondayMillis
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(requireContext()))
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is MainPageController) {
            mainPageController = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        mainPageController = null
    }

    override fun onStart() {
        super.onStart()
        viewModel.startRefresh()
    }

    override fun initViews(view: View) {
        (binding?.root as? ComposeView)?.setContent {
            HitaComposeTheme() {
                val personalChoices by comparisonChoices.observeAsState(emptyList())
                val friends by remember { friendTimetableRepository.observeFriends() }.observeAsState(emptyList())
                val activeConfig by comparisonViewModel.config.collectAsState()
                if (socialSheetOpen) TimetableSocialSheet(
                    onDismiss = { socialSheetOpen = false },
                    onShare = {
                        socialSheetOpen = false
                        val shareIntent = Intent(requireContext(), TimetableShareActivity::class.java)
                        (comparisonViewModel.state.value as? ComparisonUiState.Ready)?.config?.personalId?.let {
                            shareIntent.putExtra(TimetableShareActivity.EXTRA_TIMETABLE_ID, it)
                        }
                        startActivity(shareIntent)
                    },
                    onImport = { socialSheetOpen = false; startActivity(FriendTimetableActivity.intent(requireContext(), true)) },
                    onFriends = { socialSheetOpen = false; startActivity(FriendTimetableActivity.intent(requireContext())) },
                    onCompare = ::openComparisonConfigSheet,
                )
                if (comparisonSheetOpen) ComparisonConfigSheet(personalChoices, friends, activeConfig,
                    onDismiss = { comparisonSheetOpen = false },
                    onConfirm = { comparisonViewModel.enable(it); comparisonSheetOpen = false },
                    onManage = { comparisonSheetOpen = false; ActivityUtils.startTimetableManager(requireActivity()) },
                    onImport = { comparisonSheetOpen = false; startActivity(FriendTimetableActivity.intent(requireContext(), true)) },
                    onFriends = { comparisonSheetOpen = false; startActivity(FriendTimetableActivity.intent(requireContext())) },
                )
                TimetableScreen(
                    viewModel = viewModel,
                    comparisonViewModel = comparisonViewModel,
                    onAdjustComparison = ::openComparisonConfigSheet,
                    onTitleState = ::applyTitleState,
                    onEventClick = { EventsUtils.showEventItem(requireActivity(), it) },
                    onEventLongClick = { event, position -> showEventMenu(event, position) },
                    onAddClick = { dow, period -> showAddEvent(dow, period) },
                    onChangeInfoViewed = { viewModel.markChangeInfoViewed() },
                    onAdoptCourse = { item ->
                        viewModel.adoptIncomingCourse(item.termId, item.courseKey)
                    },
                    onDismissCourse = { item ->
                        viewModel.dismissIncomingCourse(item.termId, item.courseKey)
                    },
                    onAdoptBatch = { viewModel.adoptHeldBatch() },
                    onDismissBatch = { viewModel.dismissHeldBatch() },
                    onConfirmPending = { adopted, remember ->
                        viewModel.confirmPendingChanges(adopted, remember)
                    },
                )
            }
        }
        viewModel.decisionResult.observe(this) { state ->
            if (state.state == DataState.STATE.FETCH_FAILED) {
                Toast.makeText(
                    requireContext(),
                    state.message ?: getString(R.string.fail),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun applyTitleState(state: TimetableTitleState) {
        when (state) {
            is TimetableTitleState.Single -> mainPageController?.setSingleTitle(state.title)
            is TimetableTitleState.Week -> {
                mainPageController?.setTitleText(state.title)
                mainPageController?.setTimetableName(state.name)
                mainPageController?.setTimetableOptions(state.timetableOptions)
            }
        }
    }

    private fun getCurrentTimetableAndWeek(): Pair<Timetable?, Int?> {
        viewModel.timetableLiveData.value?.let { tts ->
            viewModel.currentPageStartDate.value?.let {
                val cdCalendar = Calendar.getInstance()
                cdCalendar.timeInMillis = it
                var minTT: Timetable? = null
                var minWk = Int.MAX_VALUE
                for (tt in tts) {
                    val isEASTimetable = !tt.code.isNullOrEmpty()
                    if (!isEASTimetable) continue

                    val wk = tt.getWeekNumber(cdCalendar.timeInMillis)
                    if (wk in 1 until minWk) {
                        minWk = wk
                        minTT = tt
                    }
                }
                return Pair(minTT, minWk)
            }
        }
        return Pair(null, null)
    }

    private fun showAddEvent(dow: Int, period: TimePeriodInDay) {
        viewModel.timetableLiveData.value?.let {
            val cp = getCurrentTimetableAndWeek()
            cp.first?.let { timetable ->
                PopupAddEvent().setInitTimetable(timetable).setInitTime(
                    dow,
                    week = if ((cp.second ?: 1) <= 0) 1 else cp.second!!,
                    period
                ).show(childFragmentManager, "add")
            } ?: run {
                Toast.makeText(context, getString(R.string.add_timetable_first), Toast.LENGTH_SHORT).show()
                activity?.let(ActivityUtils::startTimetableManager)
            }
        }
    }

    private fun showEditEventDialog(eventItem: EventItem) {
        val timetable = viewModel.timetableLiveData.value
            ?.firstOrNull { it.id == eventItem.timetableId }
        if (timetable == null) {
            Toast.makeText(requireContext(), R.string.loading, Toast.LENGTH_SHORT).show()
            return
        }
        PopupAddEvent()
            .setInitTimetable(timetable)
            .setEditEvent(eventItem)
            .show(childFragmentManager, "edit_event")
    }

    private fun showEventMenu(eventItem: EventItem, positionInWindow: IntOffset) {
        if (TimetableEventPolicy.isReadOnlyProjection(eventItem)) {
            Toast.makeText(
                requireContext(),
                R.string.timetable_projection_read_only,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val root = requireActivity().findViewById<FrameLayout>(android.R.id.content) ?: return
        val rootLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        val anchorX = (positionInWindow.x - rootLocation[0]).coerceIn(0, (root.width - 1).coerceAtLeast(0))
        val anchorY = (positionInWindow.y - rootLocation[1]).coerceIn(0, (root.height - 1).coerceAtLeast(0))
        val anchor = View(requireContext()).apply {
            alpha = 0f
            isHapticFeedbackEnabled = true
        }
        root.addView(
            anchor,
            FrameLayout.LayoutParams(1, 1).apply {
                leftMargin = anchorX
                topMargin = anchorY
            }
        )
        anchor.post {
            val pm = PopupMenu(requireContext(), anchor, Gravity.NO_GRAVITY)
            pm.menu.add(0, R.id.menu_edit_event, 0, R.string.menu_edit)
            pm.menu.add(0, R.id.menu_delete_event, 1, R.string.menu_delete)
                .setIcon(R.drawable.ic_baseline_delete_24)
            pm.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_edit_event -> showBatchScopePicker(eventItem, edit = true)
                    R.id.menu_delete_event -> showBatchScopePicker(eventItem, edit = false)
                }
                true
            }
            pm.setOnDismissListener {
                (anchor.parent as? ViewGroup)?.removeView(anchor)
            }
            pm.show()
        }
    }

    private fun showBatchScopePicker(eventItem: EventItem, edit: Boolean) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.subject_batch_scope_title)
            .setItems(
                arrayOf(
                    getString(R.string.subject_batch_scope_this),
                    getString(R.string.subject_batch_scope_slot),
                    getString(R.string.subject_batch_scope_all),
                )
            ) { _, which ->
                Thread {
                    val all = viewModel.classesOfSubjectSync(eventItem.subjectId)
                        .filter { it.type == EventItem.TYPE.CLASS }
                    val targets = when (which) {
                        0 -> listOf(eventItem)
                        1 -> all.filter {
                            it.getDow() == eventItem.getDow() &&
                                it.fromNumber == eventItem.fromNumber &&
                                it.lastNumber == eventItem.lastNumber
                        }.ifEmpty { listOf(eventItem) }
                        else -> all.ifEmpty { listOf(eventItem) }
                    }
                    val timetable = viewModel.timetableByIdSync(eventItem.timetableId)
                    val editScope = when (which) {
                        0 -> SubjectBatchEditScope.THIS
                        1 -> SubjectBatchEditScope.SLOT
                        else -> SubjectBatchEditScope.ALL
                    }
                    val deleteScope = when (which) {
                        0 -> SubjectBatchDeleteScope.THIS
                        1 -> SubjectBatchDeleteScope.SLOT
                        else -> SubjectBatchDeleteScope.ALL
                    }
                    activity?.runOnUiThread {
                        viewModel.beginBatchEdit(
                            TimetableBatchSession(
                                edit = edit,
                                editScope = editScope,
                                deleteScope = deleteScope,
                                events = targets,
                                timetable = timetable,
                            )
                        )
                    }
                }.start()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun confirmDeleteEvents(eventItems: List<EventItem>) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_title_sure_delete)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.button_confirm) { _, _ ->
                view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                Thread {
                    activity?.application?.let {
                        TimetableRepository(it).actionDeleteEvents(eventItems)
                        activity?.let { act -> WidgetUtils.sendRefreshToAll(act) }
                    }
                }.start()
            }
            .show()
    }

    interface MainPageController {
        fun setTitleText(string: String)
        fun setTimetableName(String: String)
        fun setSingleTitle(string: String)
        fun setTimetableOptions(options: List<Timetable>)
    }
}

private sealed interface TimetableTitleState {
    data class Single(val title: String) : TimetableTitleState
    data class Week(val title: String, val name: String, val timetableOptions: List<Timetable> = emptyList()) : TimetableTitleState
}

@Composable
private fun TimetableScreen(
    viewModel: TimetableViewModel,
    comparisonViewModel: TimetableComparisonViewModel,
    onAdjustComparison: () -> Unit,
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
    val timetables by viewModel.timetableLiveData.observeAsState(emptyList())
    val startTime by viewModel.startTimeLiveData.observeAsState(viewModel.currentStartTime())
    val periodLabel by viewModel.periodLabelLiveData.observeAsState(viewModel.isPeriodLabelEnabled())
    val wallpaperPath by viewModel.wallpaperPathLiveData.observeAsState(viewModel.wallpaperPath())
    val eveningHintEnabled by viewModel.eveningHintLiveData.observeAsState(viewModel.isEveningHintEnabled())
    val zoomCompressed by viewModel.zoomCompressedLiveData.observeAsState(viewModel.isZoomCompressed())
    val dateColorInt by viewModel.wallpaperDateColorLiveData.observeAsState(AndroidColor.WHITE)
    val labelColorInt by viewModel.wallpaperLabelColorLiveData.observeAsState(AndroidColor.WHITE)
    val windowEvents by viewModel.windowEventsData[viewModel.startIndex].observeAsState()

    LaunchedEffect(currentPageStart) {
        viewModel.windowStartData[viewModel.startIndex].value = currentPageStart
    }
    val comparisonState by comparisonViewModel.state.collectAsState()
    val comparisonConfig by comparisonViewModel.config.collectAsState()
    val comparisonActive = comparisonState != ComparisonUiState.Off
    // The ordinary anchor remains in the system zone, including on exit and restoration.
    var anchorMillis by rememberSaveable { mutableLongStateOf(currentPageStart) }
    var anchorZoneId by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    val anchorZone = if (comparisonActive && anchorMillis == currentPageStart) ZoneId.of(anchorZoneId)
        else ZoneId.systemDefault()
    SideEffect { anchorMillis = currentPageStart; anchorZoneId = anchorZone.id }
    val campusMonday = comparisonMonday(currentPageStart, anchorZone)
    val displayedStart = if (comparisonActive) campusMonday else currentPageStart
    LaunchedEffect(campusMonday, comparisonConfig != null) {
        comparisonViewModel.changeWeek(campusMonday)
    }
    // Do not draw the previous week during the frame before changeWeek clears it.
    val currentComparison = (comparisonState as? ComparisonUiState.Ready)?.takeIf {
        it.result.days.firstOrNull()?.date == java.time.Instant.ofEpochMilli(campusMonday).atZone(CAMPUS_ZONE).toLocalDate()
    }
    val visibleComparisonState = if (comparisonState is ComparisonUiState.Ready && currentComparison == null)
        ComparisonUiState.Loading else comparisonState
    LaunchedEffect(currentPageStart, timetables, comparisonActive, campusMonday) {
        onTitleState(if (comparisonActive) TimetableTitleState.Single(context.getString(
            R.string.comparison_week_title, java.time.Instant.ofEpochMilli(campusMonday).atZone(CAMPUS_ZONE).toLocalDate().toString()))
            else buildTitleState(context, currentPageStart, timetables))
    }

    val style = remember(windowEvents, startTime, periodLabel) {
        (windowEvents?.style ?: TimetableStyleSheet()).copy(
            startTime = startTime,
            usePeriodLabel = periodLabel,
        )
    }
    val ordinaryEvents = windowEvents?.events.orEmpty()
    val projectedCourses = remember(currentComparison, campusMonday, ordinaryEvents) {
        currentComparison?.let {
            // Existing aggregation contributes colors only, never course times or membership.
            val colors = ordinaryEvents.filter { it.color != 0 && it.subjectId.isNotBlank() }
                .associate { it.subjectId to it.color }
            projectComparisonCourses(it.source.ownCourses, campusMonday, colors)
        }.orEmpty()
    }
    val events = comparisonDisplayEvents(visibleComparisonState, ordinaryEvents) { projectedCourses.map { it.display } }
    val originalByDisplayId = remember(projectedCourses) { projectedCourses.associate { it.display.id to it.original } }
    val displayStyle = if (comparisonActive) {
        val firstHour = (comparisonConfig?.startMinute ?: 510) / 60
        val lastHour = ((comparisonConfig?.endMinute ?: 1350) + 59) / 60
        val hours = TimetableDisplayTime.hourRange(events, minOf(style.startHour, firstHour),
            maxOf(style.endHour, lastHour), CAMPUS_ZONE)
        style.copy(startTime = hours.first * 100, endHour = hours.second)
    } else style
    var comparisonCourseDetail by remember { mutableStateOf<EventItem?>(null) }
    var comparisonDetailsOpen by remember { mutableStateOf(false) }
    var selectedComparisonSpan by remember { mutableStateOf<cn.limpu.hita.feature.timetablecompare.TimeSpan?>(null) }
    LaunchedEffect(currentComparison) {
        comparisonDetailsOpen = false; selectedComparisonSpan = null; comparisonCourseDetail = null
    }


    val currentScheduleStructure = remember(timetables, currentPageStart) {
        if (timetables.isEmpty()) {
            Timetable().scheduleStructure
        } else {
            val cdCalendar = Calendar.getInstance().apply { timeInMillis = currentPageStart }
            var minTT: Timetable? = null
            var minWk = Int.MAX_VALUE
            for (tt in timetables) {
                if (tt.code.isNullOrEmpty()) continue
                val wk = tt.getWeekNumber(cdCalendar.timeInMillis)
                if (wk in 1 until minWk) {
                    minWk = wk
                    minTT = tt
                }
            }
            minTT?.scheduleStructure ?: Timetable().scheduleStructure
        }
    }
    val showTodayFab = if (comparisonActive)
        !TimetableDisplayTime.isInWeek(campusMonday, System.currentTimeMillis(), CAMPUS_ZONE)
        else currentPageStart > System.currentTimeMillis() ||
            System.currentTimeMillis() >= currentPageStart + TimetableFragment.WEEK_MILLS

    Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (comparisonActive) ComparisonStatusBar(visibleComparisonState,
                outside = currentComparison?.let { hasOutOfStructureCourses(projectedCourses, it.source.own.periods) } == true,
                onAdjust = onAdjustComparison, onExit = {
                    viewModel.currentPageStartDate.value = ordinaryMonday(campusMonday, ZoneId.systemDefault())
                    comparisonViewModel.disable()
                },
                onDetails = { comparisonDetailsOpen = true })
            Box(Modifier.weight(1f)) {
                TimetableWeekContent(
                    startDate = displayedStart,
                    events = events,
                    style = displayStyle,
                    scheduleStructure = currentComparison?.source?.ownTimetable?.scheduleStructure ?: currentScheduleStructure,
                    dateColor = if (wallpaperPath.isBlank()) MaterialTheme.colorScheme.onSurface else Color(dateColorInt),
                    labelColor = if (wallpaperPath.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color(labelColorInt),
                    onPrevWeek = {
                        viewModel.currentPageStartDate.value = if (comparisonActive)
                            ordinaryMonday(campusMonday - TimetableFragment.WEEK_MILLS, ZoneId.systemDefault())
                        else currentPageStart - TimetableFragment.WEEK_MILLS
                    },
                    onNextWeek = {
                        viewModel.currentPageStartDate.value = if (comparisonActive)
                            ordinaryMonday(campusMonday + TimetableFragment.WEEK_MILLS, ZoneId.systemDefault())
                        else currentPageStart + TimetableFragment.WEEK_MILLS
                    },
                    onEventClick = {
                        if (comparisonActive) comparisonCourseDetail = originalByDisplayId[it.id]
                        else onEventClick(it)
                    },
                    onEventLongClick = { event, offset -> if (!comparisonActive) onEventLongClick(event, offset) },
                    onAddClick = { day, period -> if (!comparisonActive) onAddClick(day, period) },
                    eveningHintEnabled = eveningHintEnabled,
                    compressed = zoomCompressed,
                    displayTimeZone = if (comparisonActive) CAMPUS_ZONE else null,
                    comparison = currentComparison,
                    comparisonReadOnly = comparisonActive,
                    onComparisonSelect = { span, _ -> selectedComparisonSpan = span; comparisonDetailsOpen = true },
                )
            }
        }
        comparisonCourseDetail?.let { ComparisonOwnCourseDetails(it) { comparisonCourseDetail = null } }
        if (comparisonDetailsOpen) currentComparison?.let {
            ComparisonDetails(it, selectedComparisonSpan, onDismiss = { comparisonDetailsOpen = false })
        }

        if (showTodayFab) {
            FloatingActionButton(
                onClick = { viewModel.currentPageStartDate.value = if (comparisonActive)
                    ordinaryMonday(comparisonMonday(System.currentTimeMillis(), CAMPUS_ZONE), ZoneId.systemDefault())
                else mondayOf(System.currentTimeMillis()) },
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

        val changeState by viewModel.changeStateLiveData.observeAsState()
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
        val batchSession by viewModel.batchSession.observeAsState()
        batchSession?.let { session ->
            if (session.edit) {
                SubjectBatchEditDialog(
                    scope = session.editScope,
                    events = session.events,
                    timetable = session.timetable,
                    onDismiss = { viewModel.clearBatchSession() },
                    onApply = { place, teacher, time ->
                        val (edited, skippedTime) = applySubjectBatchEdit(
                            events = session.events,
                            timetable = session.timetable,
                            place = place,
                            teacher = teacher,
                            time = time,
                        )
                        viewModel.updateEvents(edited)
                        WidgetUtils.sendRefreshToAll(context)
                        Toast.makeText(
                            context,
                            context.getString(R.string.subject_batch_updated, edited.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (skippedTime) {
                            Toast.makeText(context, R.string.subject_batch_week_missing, Toast.LENGTH_SHORT).show()
                        }
                        viewModel.clearBatchSession()
                    },
                )
            } else {
                SubjectBatchDeleteDialog(
                    scope = session.deleteScope,
                    count = session.events.size,
                    onDismiss = { viewModel.clearBatchSession() },
                    onConfirm = {
                        viewModel.deleteEvents(session.events)
                        WidgetUtils.sendRefreshToAll(context)
                        viewModel.clearBatchSession()
                    },
                )
            }
        }
    }
}

private fun buildTitleState(
    context: Context,
    currentPageStart: Long,
    timetables: List<Timetable>,
): TimetableTitleState {
    if (timetables.isEmpty()) {
        return TimetableTitleState.Single(context.getString(R.string.no_timetable))
    }
    val cdCalendar = Calendar.getInstance().apply { timeInMillis = currentPageStart }
    var minTT: Timetable? = null
    var minWk = Int.MAX_VALUE
    for (tt in timetables) {
        val isEASTimetable = !tt.code.isNullOrEmpty()
        if (!isEASTimetable) continue

        val wk = tt.getWeekNumber(cdCalendar.timeInMillis)
        if (wk in 1 until minWk) {
            minWk = wk
            minTT = tt
        }
    }
    return minTT?.let {
        TimetableTitleState.Week(
            title = context.getString(R.string.week_title, minWk),
            name = it.name.orEmpty(),
            timetableOptions = timetables.filter { tt -> !tt.code.isNullOrEmpty() }
        )
    } ?: TimetableTitleState.Single(context.getString(R.string.holiday))
}

@Composable
private fun TimetableWeekContent(
    startDate: Long,
    events: List<EventItem>,
    style: TimetableStyleSheet,
    scheduleStructure: List<TimePeriodInDay>,
    dateColor: Color,
    labelColor: Color,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onEventClick: (EventItem) -> Unit,
    onEventLongClick: (EventItem, IntOffset) -> Unit,
    onAddClick: (Int, TimePeriodInDay) -> Unit,
    eveningHintEnabled: Boolean = true,
    compressed: Boolean = false,
    displayTimeZone: ZoneId? = null,
    comparison: ComparisonUiState.Ready? = null,
    comparisonReadOnly: Boolean = false,
    onComparisonSelect: (cn.limpu.hita.feature.timetablecompare.TimeSpan, Boolean) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val startHour = style.startHour
    val endHour = style.endHour
    val cardHeightDp = with(density) { style.cardHeight.toDp() }
    val expandedDpPerMinute = cardHeightDp / 60f
    val totalMinutes = (endHour - startHour) * 60
    // 缩小模式：把 startHour..endHour 的全部分钟压缩进一屏，纵向不滚动；
    // 可用高度 = 根容器高度 - 星期表头 - 顶部留白 - 底部导航栏（悬浮于内容之上）高度
    val bottomInset = dimensionResource(R.dimen.bottom_navigation_height) + HitaTheme.tokens.spacing.lg
    val topInset = 24.dp
    var availableHeightPx by remember { mutableStateOf(0) }
    var headerHeightPx by remember { mutableStateOf(0) }
    val compressedDpPerMinute = if (compressed && availableHeightPx > 0 && totalMinutes > 0) {
        with(density) {
            val usable = availableHeightPx.toDp() - headerHeightPx.toDp() - bottomInset - topInset
            (usable.value / totalMinutes.toFloat()).coerceAtLeast(0.12f).dp
        }
    } else {
        null
    }
    val dpPerMinute = compressedDpPerMinute ?: expandedDpPerMinute
    val tableHeight = totalMinutes.toFloat() * dpPerMinute
    // 时间标签抽稀：压缩模式下相邻标签至少间隔 40dp，太密就隔小时显示
    val labelStepHours = if (compressed) {
        val perHour = 60f * dpPerMinute.value
        (40f / perHour).toInt().coerceAtLeast(1)
    } else {
        1
    }
    var tableWidthPx by remember { mutableStateOf(0) }
    var contentWidthPx by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    var dragAccum by remember { mutableStateOf(0f) }
    val animOffset = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    val isAppleGlass = hitaIsAppleGlassSurface()

    val displayOffset = if (isAnimating) animOffset.value else dragAccum
    val displayAlpha = if (contentWidthPx > 0f) {
        (1f - (abs(displayOffset) / contentWidthPx).coerceIn(0f, 1f))
    } else 1f

    // 晚间课程提示：当前周有 ≥18:30 开始的课程，且晚间课程尚未滚入视野时显示；
    // 滚到底部附近自动隐藏，避免遮挡正常课表内容；可在课表显示设置中关闭
    val eveningHintHideThresholdPx = with(density) { 64.dp.toPx() }
    val showEveningHint by remember(events, eveningHintEnabled, displayTimeZone) {
        derivedStateOf {
            eveningHintEnabled &&
                events.any { eventMinutes(it.from.time, displayTimeZone) >= EVENING_HINT_START_MINUTES } &&
                scrollState.maxValue > 0 &&
                scrollState.value < scrollState.maxValue - eveningHintHideThresholdPx
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { availableHeightPx = it.height }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = displayOffset
                    alpha = displayAlpha
                }
                .onSizeChanged { contentWidthPx = it.width.toFloat() }
                .pointerInput(startDate) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (isAnimating) return@detectHorizontalDragGestures
                            coroutineScope.launch {
                                val threshold = contentWidthPx * 0.15f
                                val cur = dragAccum
                                dragAccum = 0f
                                when {
                                    cur > threshold -> {
                                        isAnimating = true
                                        animOffset.snapTo(cur)
                                        animOffset.animateTo(contentWidthPx, tween(150))
                                        onPrevWeek()
                                        animOffset.snapTo(-contentWidthPx)
                                        animOffset.animateTo(0f, tween(150))
                                        isAnimating = false
                                    }
                                    cur < -threshold -> {
                                        isAnimating = true
                                        animOffset.snapTo(cur)
                                        animOffset.animateTo(-contentWidthPx, tween(150))
                                        onNextWeek()
                                        animOffset.snapTo(contentWidthPx)
                                        animOffset.animateTo(0f, tween(150))
                                        isAnimating = false
                                    }
                                    cur != 0f -> {
                                        isAnimating = true
                                        animOffset.snapTo(cur)
                                        animOffset.animateTo(0f, spring())
                                        isAnimating = false
                                    }
                                }
                            }
                        },
                        onHorizontalDrag = { _, drag ->
                            dragAccum = (dragAccum + drag).coerceIn(-contentWidthPx, contentWidthPx)
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier.onSizeChanged { headerHeightPx = it.height }
            ) {
                TimetableDowHeader(startDate = startDate, monthColor = dateColor, displayTimeZone = displayTimeZone)
            }
            // 压缩模式等根容器测得高度后再渲染表格：否则首帧会按放大尺度闪一下再压缩
            val tableReady = !compressed || availableHeightPx > 0
            if (tableReady) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (compressed) {
                            // 缩小模式：整天一屏，纵向禁滚；横向滑周保留
                            Modifier
                        } else {
                            Modifier.verticalScroll(scrollState)
                        }
                    )
                    .padding(
                        top = topInset,
                        bottom = if (compressed) bottomInset else 120.dp
                    )
            ) {
                TimetableLeftLabels(
                    startHour = startHour,
                    endHour = endHour,
                    scheduleStructure = scheduleStructure,
                    labelColor = labelColor,
                    style = style,
                    dpPerMinute = dpPerMinute,
                    compressed = compressed,
                    labelStepHours = labelStepHours,
                    modifier = Modifier
                        .width(48.dp)
                        .height(tableHeight)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(tableHeight)
                        .onSizeChanged { tableWidthPx = it.width }
                        .pointerInput(startDate, style, tableWidthPx, startHour, endHour, scheduleStructure, dpPerMinute, comparisonReadOnly) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (comparisonReadOnly) return@detectTapGestures
                                    val width = tableWidthPx.takeIf { it > 0 } ?: return@detectTapGestures
                                    val dow = ((offset.x / (width / 7f)).toInt() + 1).coerceIn(1, 7)
                                    val period = pickPeriodFromOffsetDp(
                                        y = offset.y,
                                        startHour = startHour,
                                        endHour = endHour,
                                        style = style,
                                        scheduleStructure = scheduleStructure,
                                        density = density,
                                        dpPerMinute = dpPerMinute,
                                    ) ?: return@detectTapGestures
                                    onAddClick(dow, period)
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        TimetableGrid(
                            startDate = startDate,
                            startHour = startHour,
                            endHour = endHour,
                            style = style,
                            dpPerMinute = dpPerMinute,
                            showTodayHighlight = !isAppleGlass,
                            displayTimeZone = displayTimeZone,
                        )
                    }
                    comparison?.let { ready ->
                        Row(Modifier.fillMaxSize()) {
                            ready.result.days.forEach { day ->
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    ComparisonOverlay(day, startHour, dpPerMinute, ready.config.showFree,
                                        false, onComparisonSelect)
                                }
                            }
                        }
                    }
                    TimetableEventLayer(
                        events = events,
                        startDate = startDate,
                        startHour = startHour,
                        style = style,
                        dpPerMinute = dpPerMinute,
                        onEventClick = onEventClick,
                        onEventLongClick = onEventLongClick,
                        compressed = compressed,
                        displayTimeZone = displayTimeZone,
                        preserveDuration = comparisonReadOnly,
                        comparisonSession = comparison,
                    )
                    comparison?.let { ready ->
                        Row(Modifier.fillMaxSize()) {
                            ready.result.days.forEach { day ->
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    ComparisonOverlay(day, startHour, dpPerMinute, false,
                                        ready.config.showConflicts, onComparisonSelect)
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        if (showEveningHint) {
            TimetableEveningHintPill(
                onClick = {
                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = dimensionResource(R.dimen.bottom_navigation_height) +
                            HitaTheme.tokens.spacing.lg
                    )
            )
        }
    }
}

/** 晚间课程提示的开始阈值：18:30（含） */
private const val EVENING_HINT_START_MINUTES = 18 * 60 + 30

/**
 * "还有更多课程"悬浮提示 pill：配色全部走 colorScheme 令牌，
 * 七种风格 × 深浅色 × 壁纸模式下均保持可读；点击平滑滚动到课表最底部。
 */
@Composable
private fun TimetableEveningHintPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 与 TimetableChangePill 共用配色（含 AppleGlass 暗色黑字修正）
    val pillColors = resolveTimetablePillColors(alert = false)
    val pillBackground = pillColors.background
    val contentColor = pillColors.content
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(pillBackground)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_moon),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.timetable_more_courses_hint),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ReadOnlyTimetableWeek(
    startDate: Long,
    events: List<EventItem>,
    scheduleStructure: List<TimePeriodInDay>,
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    onEventClick: (EventItem) -> Unit = {},
    displayTimeZone: ZoneId? = null,
    expandEventHourRange: Boolean = false,
) {
    val firstPeriod = scheduleStructure.firstOrNull()
    val lastPeriod = scheduleStructure.lastOrNull()
    val startTime = firstPeriod?.from?.let { it.hour * 100 + it.minute } ?: 800
    val endHour = lastPeriod?.to?.let {
        it.hour + if (it.minute > 0) 1 else 0
    }?.coerceAtLeast(startTime / 100 + 1) ?: 22
    val hourRange = if (expandEventHourRange) TimetableDisplayTime.hourRange(events, startTime / 100, endHour, displayTimeZone ?: ZoneId.systemDefault()) else (startTime / 100 to endHour)
    TimetableWeekContent(
        startDate = startDate,
        events = events,
        style = TimetableStyleSheet(
            usePeriodLabel = true,
            startTime = if (hourRange.first < startTime / 100) hourRange.first * 100 else startTime,
            endHour = hourRange.second,
            drawNowLine = false
        ),
        scheduleStructure = scheduleStructure,
        dateColor = MaterialTheme.colorScheme.onSurface,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onPrevWeek = onPreviousWeek,
        onNextWeek = onNextWeek,
        onEventClick = onEventClick,
        onEventLongClick = { _, _ -> },
        onAddClick = { _, _ -> },
        displayTimeZone = displayTimeZone,
    )
}

@Composable
private fun TimetableDowHeader(startDate: Long, monthColor: Color, displayTimeZone: ZoneId? = null) {
    val months = stringArrayResource(R.array.months)
    val dows = listOf(
        stringResource(R.string.tt_monday),
        stringResource(R.string.tt_tuesday),
        stringResource(R.string.tt_wednesday),
        stringResource(R.string.tt_thursday),
        stringResource(R.string.tt_friday),
        stringResource(R.string.tt_saturday),
        stringResource(R.string.tt_sunday),
    )
    val days = remember(startDate, displayTimeZone) {
        (0..6).map { offset ->
            TimetableDisplayTime.calendar(startDate, displayTimeZone).apply {
                timeInMillis = startDate
                add(Calendar.DATE, offset)
            }
        }
    }
    val isAppleGlass = hitaIsAppleGlassSurface()
    val isSoraCloud = hitaIsSoraCloud()
    val todayDow = TimetableDisplayTime.dayOfWeek(System.currentTimeMillis(), displayTimeZone)
    val isCurrentWeek = remember(startDate, displayTimeZone) {
        TimetableDisplayTime.isInWeek(startDate, System.currentTimeMillis(), displayTimeZone)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = HitaTheme.tokens.spacing.xs),
        verticalAlignment = Alignment.Bottom
    ) {
        // Month label
        Column(
            modifier = Modifier.width(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = months[days.first()[Calendar.MONTH]],
                color = monthColor.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        // Day columns with dow circle + date
        days.forEachIndexed { index, day ->
            val isToday = (isAppleGlass || isSoraCloud) && isCurrentWeek && index == todayDow - 1
            val dayShape = if (isSoraCloud) soraCloudCardShape(7.dp) else CircleShape
            val inactiveDayBackground = if (isSoraCloud) {
                when (index % 3) {
                    0 -> SoraVermilion.copy(alpha = if (HitaTheme.isDark) 0.22f else 0.16f)
                    1 -> SoraIndigo.copy(alpha = if (HitaTheme.isDark) 0.30f else 0.14f)
                    else -> SoraOchre.copy(alpha = if (HitaTheme.isDark) 0.20f else 0.16f)
                }
            } else if (isAppleGlass && !HitaTheme.isDark) {
                Color(0xFFE4EEF8).copy(alpha = 0.78f)
            } else {
                MaterialTheme.colorScheme.surface
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .then(
                            if (isSoraCloud) Modifier.size(width = 30.dp, height = 24.dp)
                            else Modifier.size(26.dp)
                        )
                        .clip(dayShape)
                        .background(
                            if (isToday) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            } else {
                                inactiveDayBackground
                            }
                        )
                        .then(
                            if (isToday || isSoraCloud || (isAppleGlass && !HitaTheme.isDark)) {
                                Modifier.border(
                                    width = if (isSoraCloud) 0.9.dp else 0.5.dp,
                                    color = if (isToday) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                                    } else if (isSoraCloud) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                                    } else {
                                        Color(0xFF7891AA).copy(alpha = 0.24f)
                                    },
                                    shape = dayShape
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = dows[index],
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f)
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = HitaTheme.fonts.display,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = "${day[Calendar.DATE]}",
                    color = if (isToday) MaterialTheme.colorScheme.primary else monthColor,
                    fontSize = 10.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = HitaTheme.fonts.display,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TimetableLeftLabels(
    startHour: Int,
    endHour: Int,
    scheduleStructure: List<TimePeriodInDay>,
    labelColor: Color,
    style: TimetableStyleSheet,
    dpPerMinute: Dp,
    modifier: Modifier = Modifier,
    compressed: Boolean = false,
    labelStepHours: Int = 1,
) {
    val textColor = labelColor.copy(alpha = 0.91f)
    val baseMinutes = startHour * 60
    val labelFontSize = if (compressed) 9.sp else 10.sp

    Box(modifier = modifier) {
        if (style.usePeriodLabel) {
            scheduleStructure.forEachIndexed { index, period ->
                val fromMinutes = period.from.hour * 60 + period.from.minute
                val toMinutes = period.to.hour * 60 + period.to.minute
                val periodStartMinutes = (fromMinutes - baseMinutes).coerceAtLeast(0)
                val periodDuration = (toMinutes - fromMinutes).coerceAtLeast(15)

                Box(
                    modifier = Modifier
                        .offset(y = periodStartMinutes.toFloat() * dpPerMinute)
                        .fillMaxWidth()
                        .height(periodDuration.toFloat() * dpPerMinute),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "第${index + 1}节",
                        color = textColor,
                        fontSize = if (compressed) 8.sp else 12.sp,
                        lineHeight = if (compressed) 9.sp else 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            val labelTimes = uniformLabelTimes(startHour, endHour, labelStepHours)
            labelTimes.forEach { labelTime ->
                val labelMinutesFromBase = (labelTime.hour * 60 + labelTime.minute) - baseMinutes
                Text(
                    text = labelTime.toString(),
                    color = textColor,
                    fontSize = labelFontSize,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(y = labelMinutesFromBase.toFloat() * dpPerMinute - (labelFontSize.value / 2).dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TimetableGrid(
    startDate: Long,
    startHour: Int,
    endHour: Int,
    style: TimetableStyleSheet,
    dpPerMinute: Dp,
    showTodayHighlight: Boolean = false,
    displayTimeZone: ZoneId? = null,
) {
    val density = LocalDensity.current
    val currentDow = TimetableDisplayTime.dayOfWeek(System.currentTimeMillis(), displayTimeZone)
    val isAppleGlass = hitaIsAppleGlassSurface()
    val lineColor = if (isAppleGlass && !HitaTheme.isDark) {
        Color(0xFF71869C).copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    val halfLineColor = if (isAppleGlass && !HitaTheme.isDark) {
        Color(0xFF8EA1B5).copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    }
    val isCurrentWeek = remember(startDate, displayTimeZone) {
        TimetableDisplayTime.isInWeek(startDate, System.currentTimeMillis(), displayTimeZone)
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sectionWidth = size.width / 7f
        if (showTodayHighlight && isCurrentWeek) {
            drawRect(
                color = Color(style.todayBGColor),
                topLeft = Offset(sectionWidth * (currentDow - 1), 0f),
                size = androidx.compose.ui.geometry.Size(sectionWidth, size.height)
            )
        }
        if (style.drawBGLine) {
            val dpPerMinutePx = with(density) { dpPerMinute.toPx() }
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)

            // Half-hour marks (faint solid lines)
            for (h in startHour until endHour) {
                val y = 30f * dpPerMinutePx + (h - startHour) * 60 * dpPerMinutePx
                drawLine(
                    color = halfLineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            // Hour marks (dashed lines)
            for (h in startHour..endHour) {
                val y = (h - startHour) * 60f * dpPerMinutePx
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableEventLayer(
    events: List<EventItem>,
    startDate: Long,
    startHour: Int,
    style: TimetableStyleSheet,
    dpPerMinute: Dp,
    onEventClick: (EventItem) -> Unit,
    onEventLongClick: (EventItem, IntOffset) -> Unit,
    compressed: Boolean = false,
    displayTimeZone: ZoneId? = null,
    preserveDuration: Boolean = false,
    comparisonSession: ComparisonUiState.Ready? = null,
) {
    val distinctEvents = remember(events) { events.distinctBy { it.id } }
    val arranged = remember(distinctEvents, displayTimeZone) { TimetableOverlapLayout.arrange(distinctEvents) { TimetableDisplayTime.dayOfWeek(it.from.time, displayTimeZone) } }

    val renderList = remember(arranged, displayTimeZone) { TimetableOverlapLayout.conflictCards(arranged) { TimetableDisplayTime.dayOfWeek(it.from.time, displayTimeZone) } }

    // A sheet belongs to this data/week/session; invalidate synchronously before rendering.
    var conflictCluster by remember(events, startDate, displayTimeZone, preserveDuration, comparisonSession) {
        mutableStateOf<List<EventItem>?>(null)
    }
    val baseMinutes = startHour * 60
    BoxWithConstraintsCompat {
        val sectionWidth = maxWidth / 7f
        val cardPlacements = renderList.map { (positioned, clusterEvents) ->
            val event = positioned.event
            val dayLeft = sectionWidth * (TimetableDisplayTime.dayOfWeek(event.from.time, displayTimeZone) - 1)
            if (clusterEvents != null) {
                // 合并卡片：覆盖冲突簇的时间并集，宽度与普通卡片一致
                val clusterMinFrom = clusterEvents.minOf { it.from.time }
                val clusterMaxTo = clusterEvents.maxOf { it.to.time }
                val clusterTop =
                    (eventMinutes(clusterMinFrom, displayTimeZone) - baseMinutes).coerceAtLeast(0).toFloat() * dpPerMinute
                val clusterDuration = if (preserveDuration) (clusterMaxTo - clusterMinFrom) / 60000f
                    else ((clusterMaxTo - clusterMinFrom) / 60000L).toInt().coerceAtLeast(15).toFloat()
                TimetableCardPlacement(
                    positioned = positioned,
                    clusterEvents = clusterEvents,
                    xOffset = dayLeft + 2.dp,
                    yOffset = clusterTop,
                    width = (sectionWidth - 4.dp).coerceAtLeast(0.dp),
                    height = clusterDuration.toFloat() * dpPerMinute,
                )
            } else {
                val minutesFromBase = (eventMinutes(event.from.time, displayTimeZone) - baseMinutes).coerceAtLeast(0)
                val duration = if (preserveDuration) event.getDurationInMills() / 60000f
                    else event.getDurationInMinutes().coerceAtLeast(15).toFloat()
                TimetableCardPlacement(
                    positioned = positioned,
                    clusterEvents = null,
                    xOffset = dayLeft + 2.dp,
                    yOffset = minutesFromBase.toFloat() * dpPerMinute,
                    width = (sectionWidth - 4.dp).coerceAtLeast(0.dp),
                    height = duration.toFloat() * dpPerMinute,
                )
            }
        }

        cardPlacements.forEach { placement ->
            val event = placement.positioned.event
            val clusterEvents = placement.clusterEvents
            if (clusterEvents != null) {
                key("conflict_" + clusterEvents.joinToString("_") { it.id }) {
                    TimetableConflictCard(
                        count = clusterEvents.size,
                        style = style,
                        modifier = Modifier
                            .offset(x = placement.xOffset, y = placement.yOffset)
                            .width(placement.width)
                            .height(placement.height),
                        cardHeight = placement.height,
                        onShowConflicts = { conflictCluster = clusterEvents },
                        compressed = compressed,
                    )
                }
            } else {
                key(event.id) {
                    TimetableEventCard(
                        event = event,
                        style = style,
                        modifier = Modifier
                            .offset(x = placement.xOffset, y = placement.yOffset)
                            .width(placement.width)
                            .height(placement.height),
                        cardHeight = placement.height,
                        onClick = { onEventClick(event) },
                        onLongClick = { position -> onEventLongClick(event, position) },
                        compressed = compressed,
                        cardWidth = placement.width,
                    )
                }
            }
        }
    }

    conflictCluster?.let { cluster ->
        val title = stringResource(
            R.string.timetable_conflict_sheet_title,
            TimetableDisplayTime.printTime(cluster.minOf { it.from.time }, displayTimeZone),
            TimetableDisplayTime.printTime(cluster.maxOf { it.to.time }, displayTimeZone)
        )
        ModalBottomSheet(onDismissRequest = { conflictCluster = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                cluster.forEach { event ->
                    ConflictEventRow(
                        event = event,
                        style = style,
                        onClick = {
                            conflictCluster = null
                            onEventClick(event)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** Extract minutes past midnight from an epoch-millis timestamp */
private fun eventMinutes(epochMillis: Long, displayTimeZone: ZoneId? = null): Int =
    TimetableDisplayTime.minutes(epochMillis, displayTimeZone)

@Composable
private fun ConflictEventRow(
    event: EventItem,
    style: TimetableStyleSheet,
    onClick: () -> Unit,
) {
    val courseColor = hitaCourseColor(
        courseKey = event.subjectId.ifBlank { event.name },
        storedColor = event.color,
    )
    val bg = if (style.isColorEnabled) {
        courseColor.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val place = event.place
            if (!place.isNullOrBlank()) {
                Text(
                    text = place,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Text(
            text = ">",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        )
    }
}

/**
 * 冲突合并卡片：同一时段多门课冲突时，课表上只渲染这一张卡片，
 * 配色跟随主题（tint 取 colorScheme.primary，等同关闭课程颜色时的卡片观感），
 * 单击/长按都弹出冲突课程列表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimetableConflictCard(
    count: Int,
    style: TimetableStyleSheet,
    modifier: Modifier = Modifier,
    cardHeight: Dp,
    onShowConflicts: () -> Unit,
    compressed: Boolean = false,
) {
    val view = LocalView.current
    val isAppleGlass = hitaIsAppleGlassSurface()
    val isCyber = hitaIsCyber()
    val isPersona = hitaIsPersona()
    val isSoraCloud = hitaIsSoraCloud()
    val isDeepSpace = hitaIsDeepSpace()
    val isSumi = hitaIsSumi()
    val usesIllustratedFill = isAppleGlass || isSoraCloud
    val courseTint = MaterialTheme.colorScheme.primary
    val baseAlpha = (if (isSumi) 20 else style.cardOpacity).coerceIn(20, 100) / 100f
    val bubbleStyle = style.courseBubbleStyle
    val backgroundAlpha = if (isAppleGlass) {
        when (bubbleStyle) {
            CourseBubbleStyle.SOLID -> (baseAlpha * 0.34f).coerceIn(0.10f, 0.18f)
            CourseBubbleStyle.TONAL -> (baseAlpha * 0.22f).coerceIn(0.08f, 0.14f)
            CourseBubbleStyle.OUTLINE -> (baseAlpha * 0.10f).coerceIn(0.04f, 0.08f)
        }
    } else if (isPersona) {
        // P5 统一配置：实色面板，不跟随气泡质感选项
        1f
    } else {
        val styleMultiplier = when (bubbleStyle) {
            CourseBubbleStyle.SOLID -> 1f
            CourseBubbleStyle.TONAL -> 0.32f
            CourseBubbleStyle.OUTLINE -> 0.12f
        }
        baseAlpha * styleMultiplier
    }
    val background = courseTint.copy(alpha = backgroundAlpha)
    val backgroundBrush = if (isPersona) {
        SolidColor(background)
    } else if (style.isFadeEnabled) {
        Brush.linearGradient(
            colors = listOf(
                background.copy(alpha = background.alpha * 0.72f),
                background,
            )
        )
    } else {
        SolidColor(background)
    }
    val borderColor = when {
        isPersona -> Color.Transparent
        bubbleStyle == CourseBubbleStyle.OUTLINE -> courseTint.copy(alpha = 0.88f)
        isAppleGlass || isCyber || isSoraCloud || isDeepSpace || isSumi -> Color.Transparent
        else -> courseTint.copy(alpha = 0.28f)
    }
    val borderWidth = if (bubbleStyle == CourseBubbleStyle.OUTLINE && !isPersona) 1.25.dp else 0.5.dp
    val cardShape = hitaStyleCardShape(HitaTheme.tokens.radius.md, 10.dp)
    // 文字对比度基于可见合成色计算，半透明卡片在深色/壁纸上也能读清
    val effectiveBg = background.compositeOver(MaterialTheme.colorScheme.surface).toArgb()
    val titleColor = if (isSoraCloud) {
        if (bubbleStyle == CourseBubbleStyle.SOLID) {
            Color(ColorContrast.contrastText(courseTint.toArgb()))
        } else {
            soraCloudCourseContentColor(HitaTheme.isDark)
        }
    } else if (isAppleGlass || (bubbleStyle != CourseBubbleStyle.SOLID && !isPersona)) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    } else {
        Color(ColorContrast.contrastText(effectiveBg))
    }
    val subtitleColor = if (isSoraCloud) {
        if (bubbleStyle == CourseBubbleStyle.SOLID) {
            Color(ColorContrast.contrastText(courseTint.toArgb())).copy(alpha = 0.76f)
        } else {
            soraCloudCourseContentColor(HitaTheme.isDark).copy(alpha = 0.72f)
        }
    } else if (isAppleGlass || (bubbleStyle != CourseBubbleStyle.SOLID && !isPersona)) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    } else {
        Color(ColorContrast.contrastText(effectiveBg)).copy(alpha = 0.78f)
    }
    Box(
        modifier = modifier
            .then(
                if (isAppleGlass || isSoraCloud) {
                    Modifier
                } else {
                    Modifier.hitaGlassCardModifier(cardShape, elevation = 8.dp, edgeTint = courseTint)
                }
            )
            .then(
                if (usesIllustratedFill && bubbleStyle != CourseBubbleStyle.OUTLINE) {
                    Modifier
                } else {
                    Modifier.border(borderWidth, borderColor, cardShape)
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onShowConflicts()
                    },
                    onTap = { onShowConflicts() }
                )
            }
            .then(if (isSoraCloud) Modifier else Modifier.clip(cardShape))
            .then(
                if (usesIllustratedFill) {
                    Modifier.background(Color.Transparent)
                } else {
                    Modifier.background(backgroundBrush)
                }
            )
    ) {
        if (usesIllustratedFill) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .hitaCourseCrystalGlassModifier(
                        shape = cardShape,
                        tint = courseTint,
                        isMuted = false,
                        gradientEnabled = style.isFadeEnabled,
                        opacity = when (bubbleStyle) {
                            CourseBubbleStyle.SOLID -> baseAlpha
                            CourseBubbleStyle.TONAL -> baseAlpha * 0.68f
                            CourseBubbleStyle.OUTLINE -> baseAlpha * 0.34f
                        },
                        treatment = when (bubbleStyle) {
                            CourseBubbleStyle.SOLID -> HitaCourseBubbleTreatment.SOLID
                            CourseBubbleStyle.TONAL -> HitaCourseBubbleTreatment.TONAL
                            CourseBubbleStyle.OUTLINE -> HitaCourseBubbleTreatment.OUTLINE
                        },
                    )
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.timetable_conflict_card_title),
                color = titleColor,
                fontSize = if (compressed) 8.sp else 12.sp,
                lineHeight = if (compressed) 10.sp else 15.sp,
                fontWeight = if (style.isBoldText) FontWeight.Bold else FontWeight.Normal,
                fontFamily = HitaTheme.fonts.display,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (if (compressed) cardHeight >= 22.dp else cardHeight >= 36.dp) {
                Text(
                    text = stringResource(R.string.timetable_conflict_card_subtitle, count),
                    color = subtitleColor,
                    fontSize = if (compressed) 6.5.sp else 9.sp,
                    lineHeight = if (compressed) 8.sp else 12.sp,
                    fontWeight = if (style.isBoldText) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


@Composable
private fun BoxWithConstraintsCompat(content: @Composable androidx.compose.foundation.layout.BoxWithConstraintsScope.() -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize(), content = content)
}

private data class TimetableCardPlacement(
    val positioned: PositionedEvent,
    val clusterEvents: List<EventItem>?,
    val xOffset: Dp,
    val yOffset: Dp,
    val width: Dp,
    val height: Dp,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimetableEventCard(
    event: EventItem,
    style: TimetableStyleSheet,
    modifier: Modifier = Modifier,
    cardHeight: Dp,
    onClick: () -> Unit,
    onLongClick: (IntOffset) -> Unit,
    compressed: Boolean = false,
    cardWidth: Dp = 0.dp,
) {
    val view = LocalView.current
    var cardPositionInWindow by remember { mutableStateOf(IntOffset.Zero) }
    val isAppleGlass = hitaIsAppleGlassSurface()
    val isCyber = hitaIsCyber()
    val isPersona = hitaIsPersona()
    val isSoraCloud = hitaIsSoraCloud()
    val isDeepSpace = hitaIsDeepSpace()
    val isSumi = hitaIsSumi()
    val usesIllustratedFill = isAppleGlass || isSoraCloud
    val themedCourseColor = hitaCourseColor(
        courseKey = event.subjectId.ifBlank { event.name },
        storedColor = event.color,
    )
    val courseTint = if (style.isColorEnabled) {
        themedCourseColor
    } else {
        MaterialTheme.colorScheme.primary
    }
    // Sumi cards stay at their lightest ink wash; denser fills break the paper treatment.
    val baseAlpha = (if (isSumi) 20 else style.cardOpacity).coerceIn(20, 100) / 100f
    val bubbleStyle = style.courseBubbleStyle
    val backgroundAlpha = if (isAppleGlass) {
        when (bubbleStyle) {
            CourseBubbleStyle.SOLID -> (baseAlpha * 0.34f).coerceIn(0.10f, 0.18f)
            CourseBubbleStyle.TONAL -> (baseAlpha * 0.22f).coerceIn(0.08f, 0.14f)
            CourseBubbleStyle.OUTLINE -> (baseAlpha * 0.10f).coerceIn(0.04f, 0.08f)
        }
    } else if (isPersona) {
        // P5 统一配置：实色面板，不跟随气泡质感选项
        1f
    } else {
        val styleMultiplier = when (bubbleStyle) {
            CourseBubbleStyle.SOLID -> 1f
            CourseBubbleStyle.TONAL -> 0.32f
            CourseBubbleStyle.OUTLINE -> 0.12f
        }
        baseAlpha * styleMultiplier
    }
    val background = courseTint.copy(alpha = backgroundAlpha)
    val backgroundBrush = if (isPersona) {
        // P5 统一配置：纯色块，不用渐变
        SolidColor(background)
    } else if (style.isFadeEnabled) {
        Brush.linearGradient(
            colors = listOf(
                background.copy(alpha = background.alpha * 0.72f),
                background,
            )
        )
    } else {
        SolidColor(background)
    }
    val borderColor = when {
        isPersona -> Color.Transparent
        bubbleStyle == CourseBubbleStyle.OUTLINE -> courseTint.copy(alpha = 0.88f)
        isAppleGlass || isCyber || isSoraCloud || isDeepSpace || isSumi -> Color.Transparent
        else -> courseTint.copy(alpha = 0.28f)
    }
    val borderWidth = if (bubbleStyle == CourseBubbleStyle.OUTLINE && !isPersona) 1.25.dp else 0.5.dp
    val cardShape = hitaStyleCardShape(HitaTheme.tokens.radius.md, 10.dp)
    // Text contrast must use the visible composite, especially for translucent cyber cards.
    val effectiveBg = background.compositeOver(MaterialTheme.colorScheme.surface).toArgb()
    val titleColor = if (isSoraCloud) {
        if (bubbleStyle == CourseBubbleStyle.SOLID) {
            Color(ColorContrast.contrastText(courseTint.toArgb()))
        } else {
            soraCloudCourseContentColor(HitaTheme.isDark)
        }
    } else if (isAppleGlass || (bubbleStyle != CourseBubbleStyle.SOLID && !isPersona)) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    } else {
        resolveCardTextColor(style.cardTitleColor, style.isColorEnabled, courseTint.toArgb(), effectiveBg)
    }
    val subtitleColor = if (isSoraCloud) {
        if (bubbleStyle == CourseBubbleStyle.SOLID) {
            Color(ColorContrast.contrastText(courseTint.toArgb())).copy(alpha = 0.76f)
        } else {
            soraCloudCourseContentColor(HitaTheme.isDark).copy(alpha = 0.72f)
        }
    } else if (isAppleGlass || (bubbleStyle != CourseBubbleStyle.SOLID && !isPersona)) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    } else {
        resolveCardTextColor(style.subTitleColor, style.isColorEnabled, courseTint.toArgb(), effectiveBg)
    }
    val textScale = TimetableCardTextScale.forColumnCount(1)
    val marginScale = TimetableCardTextScale.marginScaleForColumnCount(1)
    // 压缩模式收紧内边距与字号：卡片更小，留白必须让位给文字
    val horizontalPadding = ((if (compressed) 3f else 5f) * marginScale).dp
    val verticalPadding = ((if (compressed) 1f else 3f) * marginScale).dp
    val minTitleSize = if (compressed) 4.5f else 6f
    val hasPlace = !event.place.isNullOrBlank()
    // 压缩模式下过矮的卡片（<20dp）不渲染地点行，避免文字被裁切
    val showPlace = hasPlace && (!compressed || cardHeight >= 20.dp)
    val nameLength = event.name.length
    val placeLength = event.place?.length ?: 0
    val lengthScale = when {
        nameLength <= 4 -> 1.15f
        nameLength <= 6 -> 0.92f
        nameLength <= 8 -> 0.75f
        else -> 0.62f
    }
    // 压缩模式按列宽反推字号（中文约 1em/字）：先保不省略号，再保层级
    val titleWidthCap = if (compressed && cardWidth.value > 0f) {
        (cardWidth.value / nameLength.coerceAtLeast(3)).coerceIn(minTitleSize, 10f)
    } else {
        null
    }
    val titleTarget = if (titleWidthCap != null) {
        minOf(13f * textScale * lengthScale, titleWidthCap)
    } else {
        13f * textScale * lengthScale
    }
    val placeReserveDp = if (showPlace) {
        if (compressed) 6.5f else 14f * textScale
    } else {
        0f
    }
    val titleAvailableDp = cardHeight.value
        .minus(verticalPadding.value * 2f)
        .minus(placeReserveDp)
        .minus(if (style.cardIconEnabled) 10f * textScale else 0f)
    val maxTitleFromSpace = (titleAvailableDp / 1.2f).coerceAtMost(16f)
    val titleFontSize = titleTarget
        .coerceIn(minTitleSize, maxTitleFromSpace.coerceAtLeast(minTitleSize))
        .sp
    val subtitleFontSize = if (compressed && cardWidth.value > 0f && placeLength > 0) {
        minOf(10f * textScale, (cardWidth.value / placeLength.coerceAtLeast(4)).coerceIn(4.5f, 8f)).sp
    } else {
        (10f * textScale).sp
    }
    val maxTitleLines = when {
        compressed -> if (cardHeight < 26.dp) 1 else 2
        cardHeight < 40.dp -> 1
        cardHeight < 60.dp -> 2
        else -> 3
    }
    Box(
        modifier = modifier
            .then(
                if (isAppleGlass || isSoraCloud) {
                    Modifier
                } else {
                    Modifier.hitaGlassCardModifier(cardShape, elevation = 8.dp, edgeTint = courseTint)
                }
            )
            .onGloballyPositioned { coordinates ->
                val windowPosition = coordinates.localToWindow(Offset.Zero)
                cardPositionInWindow = IntOffset(
                    windowPosition.x.roundToInt(),
                    windowPosition.y.roundToInt()
                )
            }
            .then(
                if (usesIllustratedFill && bubbleStyle != CourseBubbleStyle.OUTLINE) {
                    Modifier
                } else {
                    Modifier.border(borderWidth, borderColor, cardShape)
                }
            )
            .pointerInput(event.id) {
                detectTapGestures(
                    onLongPress = { localOffset ->
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onLongClick(
                            IntOffset(
                                cardPositionInWindow.x + localOffset.x.roundToInt(),
                                cardPositionInWindow.y + localOffset.y.roundToInt()
                            )
                        )
                    },
                    onTap = { onClick() }
                )
            }
            .then(if (isSoraCloud) Modifier else Modifier.clip(cardShape))
            .then(
                if (usesIllustratedFill) {
                    Modifier.background(Color.Transparent)
                } else {
                    Modifier.background(backgroundBrush)
                }
            )
    ) {
        if (usesIllustratedFill) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .hitaCourseCrystalGlassModifier(
                        shape = cardShape,
                        tint = courseTint,
                        isMuted = false,
                        gradientEnabled = style.isFadeEnabled,
                        opacity = when (bubbleStyle) {
                            CourseBubbleStyle.SOLID -> baseAlpha
                            CourseBubbleStyle.TONAL -> baseAlpha * 0.68f
                            CourseBubbleStyle.OUTLINE -> baseAlpha * 0.34f
                        },
                        treatment = when (bubbleStyle) {
                            CourseBubbleStyle.SOLID -> HitaCourseBubbleTreatment.SOLID
                            CourseBubbleStyle.TONAL -> HitaCourseBubbleTreatment.TONAL
                            CourseBubbleStyle.OUTLINE -> HitaCourseBubbleTreatment.OUTLINE
                        },
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
            if (showPlace) {
                Text(
                    text = event.place ?: "",
                    color = subtitleColor,
                    fontSize = subtitleFontSize,
                    lineHeight = (subtitleFontSize.value * 1.2f).sp,
                    fontWeight = if (style.isBoldText) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE)
                        .alpha(if (isAppleGlass) 0.78f else style.subtitleAlpha / 100f)
                )
            }
            if (style.cardIconEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                        .size((8 * textScale).dp)
                        .clip(CircleShape)
                        .background(titleColor)
                )
            }
            val titleBottomPad = if (showPlace) {
                (subtitleFontSize.value * 1.2f).dp
            } else {
                0.dp
            }
            val titleTopPad = if (style.cardIconEnabled) (10f * textScale).dp else 0.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = titleBottomPad, top = titleTopPad),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = event.name,
                    color = titleColor,
                    fontSize = titleFontSize,
                    lineHeight = (titleFontSize.value * 1.2f).sp,
                    fontWeight = if (style.isBoldText) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = HitaTheme.fonts.display,
                    textAlign = textAlignFromGravity(style.titleGravity),
                    maxLines = maxTitleLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (isAppleGlass) 1f else style.titleAlpha / 100f)
                )
            }
        }
    }
}

@Composable
private fun resolveCardTextColor(
    mode: String,
    colorEnabled: Boolean,
    eventColor: Int,
    effectiveBg: Int,
): Color {
    return when (mode) {
        "auto" -> Color(ColorContrast.contrastText(effectiveBg))
        "subject" -> if (colorEnabled) Color(eventColor) else MaterialTheme.colorScheme.primary
        "white" -> Color.White
        "black" -> Color.Black
        "primary" -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }
}

private fun textAlignFromGravity(gravity: Int): TextAlign {
    return when (gravity) {
        Gravity.START, Gravity.LEFT -> TextAlign.Start
        Gravity.END, Gravity.RIGHT -> TextAlign.End
        else -> TextAlign.Center
    }
}

/** 均匀每小时时间标签（仅整点）；[stepHours] > 1 时隔小时显示（压缩模式抽稀）。 */
private fun uniformLabelTimes(startHour: Int, endHour: Int, stepHours: Int = 1): List<TimeInDay> {
    val times = mutableListOf<TimeInDay>()
    val step = stepHours.coerceAtLeast(1)
    var h = startHour
    while (h <= endHour) {
        times.add(TimeInDay(h, 0))
        h += step
    }
    return times
}

private fun pickPeriodFromOffsetDp(
    y: Float,
    startHour: Int,
    endHour: Int,
    style: TimetableStyleSheet,
    scheduleStructure: List<TimePeriodInDay>,
    density: Density,
    dpPerMinute: Dp,
): TimePeriodInDay? {
    val dpPerMinutePx = with(density) { dpPerMinute.toPx() }
    if (dpPerMinutePx <= 0f) return null
    val minutesFromBase = (y / dpPerMinutePx).toInt()
    val absoluteMinutes = startHour * 60 + minutesFromBase
    val absoluteTime = TimeInDay(absoluteMinutes / 60, absoluteMinutes % 60)

    val structure = scheduleStructure.ifEmpty { return null }
    for (i in structure.indices) {
        val period = structure[i]
        if (i == 0 && period.after(absoluteTime)) {
            return TimePeriodInDay(TimeInDay(startHour, 0), period.from)
        }
        if (period.contains(absoluteTime)) {
            return period.clone()
        }
        if (i + 1 < structure.size && structure[i + 1].after(absoluteTime)) {
            return TimePeriodInDay(period.to, structure[i + 1].from)
        }
        if (i == structure.size - 1 && period.before(absoluteTime)) {
            return TimePeriodInDay(period.to, TimeInDay(endHour, 0))
        }
    }
    return null
}

private fun mondayOf(ts: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = ts
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.SECOND, 0)
    }.timeInMillis
}
