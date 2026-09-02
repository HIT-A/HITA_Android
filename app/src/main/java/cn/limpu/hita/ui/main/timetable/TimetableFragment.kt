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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.fragment.app.viewModels
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
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
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.EventsUtils
import cn.limpu.hita.utils.TimeTools
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class TimetableFragment : HiltBaseFragment<ComposeViewBinding>() {

    protected val viewModel: TimetableViewModel by viewModels()
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
                TimetableScreen(
                    viewModel = viewModel,
                    onTitleState = ::applyTitleState,
                    onEventClick = { EventsUtils.showEventItem(requireActivity(), it) },
                    onEventLongClick = { event, position -> showEventMenu(event, position) },
                    onAddClick = { dow, period -> showAddEvent(dow, period) },
                )
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
                    R.id.menu_edit_event -> showEditEventDialog(eventItem)
                    R.id.menu_delete_event -> confirmDeleteEvents(listOf(eventItem))
                }
                true
            }
            pm.setOnDismissListener {
                (anchor.parent as? ViewGroup)?.removeView(anchor)
            }
            pm.show()
        }
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
    onTitleState: (TimetableTitleState) -> Unit,
    onEventClick: (EventItem) -> Unit,
    onEventLongClick: (EventItem, IntOffset) -> Unit,
    onAddClick: (Int, TimePeriodInDay) -> Unit,
) {
    val context = LocalContext.current
    val currentPageStart by viewModel.currentPageStartDate.observeAsState(
        initial = viewModel.currentPageStartDate.value ?: mondayOf(System.currentTimeMillis())
    )
    val timetables by viewModel.timetableLiveData.observeAsState(emptyList())
    val startTime by viewModel.startTimeLiveData.observeAsState(830)
    val periodLabel by viewModel.periodLabelLiveData.observeAsState(false)
    val wallpaperPath by viewModel.wallpaperPathLiveData.observeAsState("")
    val dateColorInt by viewModel.wallpaperDateColorLiveData.observeAsState(AndroidColor.WHITE)
    val labelColorInt by viewModel.wallpaperLabelColorLiveData.observeAsState(AndroidColor.WHITE)
    val windowEvents by viewModel.windowEventsData[viewModel.startIndex].observeAsState()

    LaunchedEffect(currentPageStart) {
        viewModel.windowStartData[viewModel.startIndex].value = currentPageStart
    }
    LaunchedEffect(currentPageStart, timetables) {
        onTitleState(buildTitleState(context, currentPageStart, timetables))
    }

    val style = remember(windowEvents, startTime, periodLabel) {
        (windowEvents?.style ?: TimetableStyleSheet()).copy(
            startTime = startTime,
            usePeriodLabel = periodLabel,
        )
    }
    val events = windowEvents?.events.orEmpty()

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
    val showTodayFab = currentPageStart > System.currentTimeMillis() ||
            System.currentTimeMillis() >= currentPageStart + TimetableFragment.WEEK_MILLS

    Box(modifier = Modifier.fillMaxSize()) {
        TimetableWeekContent(
            startDate = currentPageStart,
            events = events,
            style = style,
            scheduleStructure = currentScheduleStructure,
            dateColor = if (wallpaperPath.isBlank()) MaterialTheme.colorScheme.onSurface else Color(dateColorInt),
            labelColor = if (wallpaperPath.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color(labelColorInt),
            onPrevWeek = {
                viewModel.currentPageStartDate.value = currentPageStart - TimetableFragment.WEEK_MILLS
            },
            onNextWeek = {
                viewModel.currentPageStartDate.value = currentPageStart + TimetableFragment.WEEK_MILLS
            },
            onEventClick = onEventClick,
            onEventLongClick = onEventLongClick,
            onAddClick = onAddClick,
        )

        if (showTodayFab) {
            FloatingActionButton(
                onClick = { viewModel.currentPageStartDate.value = mondayOf(System.currentTimeMillis()) },
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
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val startHour = style.startHour
    val endHour = style.endHour
    val cardHeightDp = with(density) { style.cardHeight.toDp() }
    val dpPerMinute = cardHeightDp / 60f
    val totalMinutes = (endHour - startHour) * 60
    val tableHeight = totalMinutes.toFloat() * dpPerMinute
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
        TimetableDowHeader(startDate = startDate, monthColor = dateColor)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = 24.dp, bottom = 120.dp)
        ) {
            TimetableLeftLabels(
                startHour = startHour,
                endHour = endHour,
                scheduleStructure = scheduleStructure,
                labelColor = labelColor,
                style = style,
                dpPerMinute = dpPerMinute,
                modifier = Modifier
                    .width(48.dp)
                    .height(tableHeight)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(tableHeight)
                    .onSizeChanged { tableWidthPx = it.width }
                    .pointerInput(startDate, style, tableWidthPx, startHour, endHour, scheduleStructure) {
                        detectTapGestures(
                            onTap = { offset ->
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
                    )
                }
                TimetableEventLayer(
                    events = events,
                    startDate = startDate,
                    startHour = startHour,
                    style = style,
                    dpPerMinute = dpPerMinute,
                    onEventClick = onEventClick,
                    onEventLongClick = onEventLongClick,
                )
            }
        }
    }
}

@Composable
internal fun ReadOnlyTimetableWeek(
    startDate: Long,
    events: List<EventItem>,
    scheduleStructure: List<TimePeriodInDay>,
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    onEventClick: (EventItem) -> Unit = {}
) {
    val firstPeriod = scheduleStructure.firstOrNull()
    val lastPeriod = scheduleStructure.lastOrNull()
    val startTime = firstPeriod?.from?.let { it.hour * 100 + it.minute } ?: 800
    val endHour = lastPeriod?.to?.let {
        it.hour + if (it.minute > 0) 1 else 0
    }?.coerceAtLeast(startTime / 100 + 1) ?: 22
    TimetableWeekContent(
        startDate = startDate,
        events = events,
        style = TimetableStyleSheet(
            usePeriodLabel = true,
            startTime = startTime,
            endHour = endHour,
            drawNowLine = false
        ),
        scheduleStructure = scheduleStructure,
        dateColor = MaterialTheme.colorScheme.onSurface,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onPrevWeek = onPreviousWeek,
        onNextWeek = onNextWeek,
        onEventClick = onEventClick,
        onEventLongClick = { _, _ -> },
        onAddClick = { _, _ -> }
    )
}

@Composable
private fun TimetableDowHeader(startDate: Long, monthColor: Color) {
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
    val days = remember(startDate) {
        (0..6).map { offset ->
            Calendar.getInstance().apply {
                timeInMillis = startDate
                add(Calendar.DATE, offset)
            }
        }
    }
    val isAppleGlass = hitaIsAppleGlassSurface()
    val isSoraCloud = hitaIsSoraCloud()
    val todayDow = TimeTools.currentDOW()
    val isCurrentWeek = remember(startDate) {
        val start = Calendar.getInstance().apply { timeInMillis = startDate }
        TimeTools.isSameWeekWithStartDate(start, System.currentTimeMillis())
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
) {
    val textColor = labelColor.copy(alpha = 0.91f)
    val baseMinutes = startHour * 60

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
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            val labelTimes = uniformLabelTimes(startHour, endHour)
            labelTimes.forEach { labelTime ->
                val labelMinutesFromBase = (labelTime.hour * 60 + labelTime.minute) - baseMinutes
                Text(
                    text = labelTime.toString(),
                    color = textColor,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(y = labelMinutesFromBase.toFloat() * dpPerMinute - 10.dp)
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
) {
    val density = LocalDensity.current
    val currentDow = TimeTools.currentDOW()
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
    val isCurrentWeek = remember(startDate) {
        val start = Calendar.getInstance().apply { timeInMillis = startDate }
        TimeTools.isSameWeekWithStartDate(start, System.currentTimeMillis())
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
) {
    val distinctEvents = remember(events) { events.distinctBy { it.id } }
    val arranged = remember(distinctEvents) { TimetableOverlapLayout.arrange(distinctEvents) }

    val renderList = remember(arranged) {
        val result = mutableListOf<Pair<PositionedEvent, List<EventItem>?>>()
        var i = 0
        while (i < arranged.size) {
            val pe = arranged[i]
            if (pe.overlapCount > 1) {
                val cluster = mutableListOf<PositionedEvent>()
                var clusterEnd = Long.MIN_VALUE
                while (i < arranged.size) {
                    val next = arranged[i]
                    if (next.overlapCount <= 1) break
                    if (cluster.isNotEmpty() && next.event.from.time >= clusterEnd) break
                    cluster.add(next)
                    clusterEnd = maxOf(clusterEnd, next.event.to.time)
                    i++
                }
                cluster.sortBy { it.columnIndex }
                val clusterEvents = cluster.map { it.event }
                cluster.forEach { rp -> result.add(rp to clusterEvents) }
            } else {
                result.add(pe to null)
                i++
            }
        }
        result
    }

    var conflictCluster by remember { mutableStateOf<List<EventItem>?>(null) }
    val baseMinutes = startHour * 60
    BoxWithConstraintsCompat {
        val sectionWidth = maxWidth / 7f
        val cascadeOffset = 6.dp
        val cardPlacements = renderList.map { (positioned, clusterEvents) ->
            val event = positioned.event
            val overlapCount = positioned.overlapCount
            val eventMinutesPastMidnight = eventMinutes(event.from.time)
            val minutesFromBase = (eventMinutesPastMidnight - baseMinutes).coerceAtLeast(0)
            val duration = event.getDurationInMinutes().coerceAtLeast(15)
            val top = minutesFromBase.toFloat() * dpPerMinute
            val rawHeight = duration.toFloat() * dpPerMinute
            val dayLeft = sectionWidth * (event.getDow() - 1)
            val isCascade = clusterEvents != null
            if (isCascade) {
                val offsetTotal = overlapCount - 1
                val cardWidth = (sectionWidth - cascadeOffset * offsetTotal).coerceAtLeast(0.dp)
                val cardHeight = (rawHeight - cascadeOffset * offsetTotal).coerceAtLeast(0.dp)
                val xOffset = dayLeft + cascadeOffset * positioned.columnIndex
                val yOffset = top + cascadeOffset * positioned.columnIndex
                val elevation = 2.dp + 2.dp * positioned.columnIndex
                TimetableCardPlacement(
                    positioned = positioned,
                    clusterEvents = clusterEvents,
                    xOffset = xOffset,
                    yOffset = yOffset,
                    width = cardWidth,
                    height = cardHeight,
                    columnCount = overlapCount,
                    cardElevation = elevation,
                    isBottomCascadeCard = positioned.columnIndex < overlapCount - 1
                )
            } else {
                TimetableCardPlacement(
                    positioned = positioned,
                    clusterEvents = null,
                    xOffset = dayLeft + 2.dp,
                    yOffset = top,
                    width = (sectionWidth - 4.dp).coerceAtLeast(0.dp),
                    height = rawHeight,
                    columnCount = 1,
                    cardElevation = 0.dp,
                    isBottomCascadeCard = false
                )
            }
        }

        cardPlacements.forEach { placement ->
            val event = placement.positioned.event
            val clusterEvents = placement.clusterEvents
            key(event.id) {
                if (clusterEvents != null) {
                    TimetableEventCard(
                        event = event,
                        style = style,
                        modifier = Modifier
                            .offset(x = placement.xOffset, y = placement.yOffset)
                            .width(placement.width)
                            .height(placement.height),
                        cardHeight = placement.height,
                        columnCount = placement.columnCount,
                        cardElevation = placement.cardElevation,
                        isBottomCascadeCard = placement.isBottomCascadeCard,
                        onClick = { conflictCluster = clusterEvents },
                        onLongClick = { position -> onEventLongClick(event, position) }
                    )
                } else {
                    TimetableEventCard(
                        event = event,
                        style = style,
                        modifier = Modifier
                            .offset(x = placement.xOffset, y = placement.yOffset)
                            .width(placement.width)
                            .height(placement.height),
                        cardHeight = placement.height,
                        columnCount = placement.columnCount,
                        onClick = { onEventClick(event) },
                        onLongClick = { position -> onEventLongClick(event, position) }
                    )
                }
            }
        }
    }

    conflictCluster?.let { cluster ->
        val title = "${TimeTools.printTime(cluster.minOf { it.from.time })} - ${TimeTools.printTime(cluster.maxOf { it.to.time })} 课程冲突"
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
private fun eventMinutes(epochMillis: Long): Int {
    return TimeTools.getHour(epochMillis) * 60 + TimeTools.getMinute(epochMillis)
}

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
    val columnCount: Int,
    val cardElevation: Dp,
    val isBottomCascadeCard: Boolean,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimetableEventCard(
    event: EventItem,
    style: TimetableStyleSheet,
    modifier: Modifier = Modifier,
    cardHeight: Dp,
    columnCount: Int,
    onClick: () -> Unit,
    onLongClick: (IntOffset) -> Unit,
    cardElevation: Dp = 0.dp,
    isBottomCascadeCard: Boolean = false,
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
        if (isBottomCascadeCard) {
            0.04f
        } else {
            when (bubbleStyle) {
                CourseBubbleStyle.SOLID -> (baseAlpha * 0.34f).coerceIn(0.10f, 0.18f)
                CourseBubbleStyle.TONAL -> (baseAlpha * 0.22f).coerceIn(0.08f, 0.14f)
                CourseBubbleStyle.OUTLINE -> (baseAlpha * 0.10f).coerceIn(0.04f, 0.08f)
            }
        }
    } else if (isPersona) {
        // P5 统一配置：实色面板，不跟随气泡质感选项
        if (isBottomCascadeCard) 0.52f else 1f
    } else {
        val styleMultiplier = when (bubbleStyle) {
            CourseBubbleStyle.SOLID -> 1f
            CourseBubbleStyle.TONAL -> 0.32f
            CourseBubbleStyle.OUTLINE -> 0.12f
        }
        baseAlpha * styleMultiplier * if (isBottomCascadeCard) 0.5f else 1f
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
    val textScale = TimetableCardTextScale.forColumnCount(columnCount)
    val marginScale = TimetableCardTextScale.marginScaleForColumnCount(columnCount)
    val horizontalPadding = (5 * marginScale).dp
    val verticalPadding = (3 * marginScale).dp
    val minTitleSize = if (columnCount > 1) 5f else 6f
    val hasPlace = !event.place.isNullOrBlank()
    val nameLength = event.name.length
    val lengthScale = when {
        nameLength <= 4 -> 1.15f
        nameLength <= 6 -> 0.92f
        nameLength <= 8 -> 0.75f
        else -> 0.62f
    }
    val titleAvailableDp = cardHeight.value
        .minus(verticalPadding.value * 2f)
        .minus(if (hasPlace) 14f * textScale else 0f)
        .minus(if (style.cardIconEnabled) 10f * textScale else 0f)
    val maxTitleFromSpace = (titleAvailableDp / 1.2f).coerceAtMost(16f)
    val titleFontSize = (13f * textScale * lengthScale)
        .coerceIn(minTitleSize, maxTitleFromSpace.coerceAtLeast(minTitleSize))
        .sp
    val subtitleFontSize = (10f * textScale).sp
    val maxTitleLines = when {
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
                        isMuted = isBottomCascadeCard,
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
            if (hasPlace && !isBottomCascadeCard) {
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
            if (style.cardIconEnabled && !isBottomCascadeCard) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                        .size((8 * textScale).dp)
                        .clip(CircleShape)
                        .background(titleColor)
                )
            }
            if (!isBottomCascadeCard) {
                val titleBottomPad = if (hasPlace) (14f * textScale).dp else 0.dp
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

/** 均匀每小时时间标签（仅整点） */
private fun uniformLabelTimes(startHour: Int, endHour: Int): List<TimeInDay> {
    val times = mutableListOf<TimeInDay>()
    for (h in startHour..endHour) times.add(TimeInDay(h, 0))
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
