package cn.limpu.hita.ui.main.timeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_DATE_CHANGED
import android.content.Intent.ACTION_TIME_TICK
import android.content.IntentFilter
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseFragmentWithReceiver
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaUsesMainBackdrop
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface
import cn.limpu.hita.ui.design.hitaIsPersona
import cn.limpu.hita.ui.design.hitaPersonaCardModifier
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.design.scrimForWhiteText
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.utils.EventsUtils
import cn.limpu.hita.utils.HintUtils
import cn.limpu.hita.utils.SpecialEventReminderUtils
import cn.limpu.hita.utils.TimeTools
import cn.limpu.hita.utils.TimeTools.TTY_REPLACE
import cn.limpu.hita.utils.TimeTools.TTY_WK_FOLLOWING
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import java.util.Collections

/** banner 白字的目标对比度；比正文 4.5 略高，留给副标题的 0.8 alpha。 */
private const val BANNER_CONTRAST_TARGET = 6.5f

@AndroidEntryPoint
class FragmentTimeLine : HiltBaseFragmentWithReceiver<ComposeViewBinding>() {

    protected val viewModel: FragmentTimelineViewModel by viewModels()
    private var mainPageController: MainPageController? = null

    override var receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action == ACTION_TIME_TICK ||
                intent?.action == ACTION_DATE_CHANGED ||
                intent?.action == Intent.ACTION_TIME_CHANGED
            ) {
                viewModel.startRefresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        retainInstance = false
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
        mainPageController?.setTimelineTitleText(
            TimeTools.getDateString(
                requireContext(),
                Calendar.getInstance(),
                true,
                TTY_WK_FOLLOWING
            )
        )
    }

    fun onTabActivated() {
        viewModel.triggerEnterAnimation()
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(requireContext()))
    }

    override fun initViews(view: View) {
        (binding?.root as? ComposeView)?.setContent {
            HitaComposeTheme() {
                TimelineScreen(
                    viewModel = viewModel,
                    onTitleChanged = { mainPageController?.setTimelineTitleText(it) },
                    onEventClick = { context?.let { ctx -> EventsUtils.showEventItem(ctx, it) } },
                    onHintConfirmed = { context?.let { ctx -> HintUtils.clickHint(ctx, it) }; viewModel.startRefresh() },
                    onRefreshWidget = { activity?.let(WidgetUtils::sendRefreshToAll) },
                )
            }
        }
    }

    interface MainPageController {
        fun setTimelineTitleText(string: String)
    }

    override fun getIntentFilter(): IntentFilter {
        val inf = IntentFilter()
        inf.addAction(ACTION_DATE_CHANGED)
        inf.addAction(ACTION_TIME_TICK)
        inf.addAction(Intent.ACTION_TIME_CHANGED)
        return inf
    }
}

@Composable
private fun TimelineScreen(
    viewModel: FragmentTimelineViewModel,
    onTitleChanged: (String) -> Unit,
    onEventClick: (EventItem) -> Unit,
    onHintConfirmed: (EventItem) -> Unit,
    onRefreshWidget: () -> Unit,
) {
    val context = LocalContext.current
    val todayEventsRaw by viewModel.todayEventsLiveData.observeAsState(emptyList())
    val weekEventsRaw by viewModel.weekEventsLiveData.observeAsState(emptyList())
    val upcomingExamsRaw by viewModel.upcomingExamLiveData.observeAsState(emptyList())
    var expanded by remember { mutableStateOf(false) }
    var ticker by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            ticker = System.currentTimeMillis()
        }
    }

    val todayEvents = remember(todayEventsRaw) {
        todayEventsRaw.sortedBy { it.from.time }
    }
    val weekEvents = remember(weekEventsRaw) {
        weekEventsRaw.sortedBy { it.from.time }
    }
    val upcomingExams = remember(upcomingExamsRaw) {
        upcomingExamsRaw.sortedBy { it.from.time }
    }
    val hintEvents = remember(todayEventsRaw) { HintUtils.getHints(context) }
    val displayEvents = remember(hintEvents, todayEvents) {
        hintEvents + todayEvents
    }
    val headerState = remember(todayEvents, upcomingExams, ticker) {
        buildHeaderState(context, todayEvents, upcomingExams)
    }

    val enterTick by viewModel.enterTick.observeAsState(0L)

    LaunchedEffect(todayEventsRaw) {
        onRefreshWidget()
    }
    LaunchedEffect(expanded) {
        onTitleChanged(
            if (expanded) {
                context.getString(R.string.events_incoming)
            } else {
                TimeTools.getDateString(context, Calendar.getInstance(), true, TTY_WK_FOLLOWING)
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (hitaUsesMainBackdrop()) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.background
                }
            ),
        contentPadding = PaddingValues(bottom = HitaTheme.tokens.spacing.xxl)
    ) {
        item(key = "timeline_header") {
            TimelineHeaderCard(
                state = headerState,
                expanded = expanded,
                weekEvents = weekEvents,
                onToggleExpanded = { expanded = !expanded },
                onEventClick = onEventClick,
            )
        }

        itemsIndexed(displayEvents, key = { _, event -> "timeline_${event.id}_${event.from.time}" }) { index, event ->
            if (event.type == EventItem.TYPE.TAG) {
                TimelineHintCard(event = event, onConfirmed = onHintConfirmed)
            } else {
                val isFirstTimeline = remember(displayEvents) {
                    index == displayEvents.indexOfFirst { it.type != EventItem.TYPE.TAG }
                }
                val isLastTimeline = remember(displayEvents) {
                    index == displayEvents.indexOfLast { it.type != EventItem.TYPE.TAG }
                }
                val isPassed = TimeTools.passed(event.to)
                val spec = tween<Float>(durationMillis = 450, easing = FastOutSlowInEasing)
                val alpha = remember { Animatable(if (isPassed) 1f else 0f) }
                val translationY = remember { Animatable(if (isPassed) 0f else 120f) }
                val scale = remember { Animatable(if (isPassed) 1f else 0.94f) }

                LaunchedEffect(isPassed, enterTick) {
                    if (!isPassed) {
                        alpha.snapTo(0f)
                        translationY.snapTo(120f)
                        scale.snapTo(0.94f)
                        delay(index * 50L)
                        coroutineScope {
                            launch { alpha.animateTo(1f, spec) }
                            launch { translationY.animateTo(0f, spec) }
                            launch { scale.animateTo(1f, spec) }
                        }
                    } else {
                        alpha.snapTo(1f)
                        translationY.snapTo(0f)
                        scale.snapTo(1f)
                    }
                }
                Box(modifier = Modifier.graphicsLayer {
                    this.alpha = alpha.value
                    this.translationY = translationY.value
                    this.scaleX = scale.value
                    this.scaleY = scale.value
                }) {
                    TimelineEventRow(
                        event = event,
                        isPassed = isPassed,
                        isUpcomingExam = SpecialEventReminderUtils.isExamEvent(event) &&
                                event.from.time > System.currentTimeMillis(),
                        isNow = event.containsTimeStamp(System.currentTimeMillis()),
                        isFirstTimeline = isFirstTimeline,
                        isLastTimeline = isLastTimeline,
                        onClick = { onEventClick(event) },
                    )
                }
            }
        }

        if (todayEvents.isEmpty()) {
            item(key = "timeline_empty") {
                TimelineEmpty()
            }
        }

        item(key = "timeline_footer") {
            Spacer(modifier = Modifier.height(HitaTheme.tokens.spacing.xxl))
        }
    }
}

private data class TimelineHeaderState(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val nowProgress: Float?,
    val goNowPlace: String?,
    val nextEventName: String,
    val nextEventTime: String,
    val nextIconRes: Int,
    val examCountdown: String?,
    val examName: String?,
)

private fun buildHeaderState(
    context: Context,
    todayEvents: List<EventItem>,
    upcomingExams: List<EventItem>,
): TimelineHeaderState {
    val now = System.currentTimeMillis()
    val currentTime = Calendar.getInstance()
    val courseNum = todayEvents.count { it.type == EventItem.TYPE.CLASS }
    val nowEvent = todayEvents
        .asReversed()
        .firstOrNull { !SpecialEventReminderUtils.isExamEvent(it) && it.containsTimeStamp(now) }
    val nextEvent = todayEvents
        .firstOrNull { !SpecialEventReminderUtils.isExamEvent(it) && it.happensAfterTimeStamp(now) }

    val title: String
    val subtitle: String
    val iconRes: Int
    val progress: Float?
    val goNowPlace: String?

    when {
        todayEvents.isEmpty() -> {
            title = context.getString(R.string.timeline_head_free_title)
            subtitle = context.getString(R.string.timeline_head_free_subtitle)
            iconRes = R.drawable.ic_timeline_head_free
            progress = null
            goNowPlace = null
        }
        nowEvent != null -> {
            title = nowEvent.name
            subtitle = context.getString(R.string.timeline_head_ongoing_subtitle)
            iconRes = R.drawable.ic_timelapse
            progress = nowEvent.getProgress(now)
            goNowPlace = null
        }
        isBetween(currentTime, TimeInDay(0, 0), TimeInDay(5, 0)) -> {
            title = context.getString(R.string.timeline_head_goodnight_title)
            subtitle = context.getString(R.string.timeline_head_goodnight_subtitle)
            iconRes = R.drawable.ic_moon
            progress = null
            goNowPlace = null
        }
        isBetween(currentTime, TimeInDay(5, 0), TimeInDay(8, 15)) -> {
            title = context.getString(R.string.timeline_head_goodmorning_title)
            subtitle = context.getString(R.string.timelinr_goodmorning_subtitle, courseNum)
            iconRes = R.drawable.ic_sunny
            progress = null
            goNowPlace = null
        }
        isBetween(currentTime, TimeInDay(12, 15), TimeInDay(13, 0)) -> {
            title = context.getString(R.string.timeline_head_lunch_title)
            subtitle = context.getString(R.string.timeline_head_lunch_subtitle)
            iconRes = R.drawable.ic_lunch
            progress = null
            goNowPlace = null
        }
        isBetween(currentTime, TimeInDay(17, 10), TimeInDay(18, 10)) -> {
            title = context.getString(R.string.timeline_head_dinner_title)
            subtitle = context.getString(R.string.timeline_head_dinner_subtitle)
            iconRes = R.drawable.ic_lunch
            progress = null
            goNowPlace = null
        }
        nextEvent != null && nextEvent.getFromTimeDistance() <= 15 &&
                (nextEvent.type == EventItem.TYPE.CLASS || nextEvent.type == EventItem.TYPE.OTHER) -> {
            title = nextEvent.name
            subtitle = context.getString(R.string.timeline_gonow_subtitle, nextEvent.getFromTimeDistance())
            iconRes = R.drawable.ic_run
            progress = null
            goNowPlace = nextEvent.place?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.unknown_location)
        }
        nextEvent != null -> {
            title = context.getString(R.string.timeline_head_normal_title)
            subtitle = context.getString(R.string.timeline_head_normal_subtitle)
            iconRes = R.drawable.ic_sunglasses
            progress = null
            goNowPlace = null
        }
        isBetween(currentTime, TimeInDay(23, 0), TimeInDay(23, 59)) ||
                isBetween(currentTime, TimeInDay(0, 0), TimeInDay(5, 0)) -> {
            title = context.getString(R.string.timeline_head_goodnight_title)
            subtitle = context.getString(R.string.timeline_head_goodnight_subtitle)
            iconRes = R.drawable.ic_moon
            progress = null
            goNowPlace = null
        }
        else -> {
            title = context.getString(R.string.timeline_head_finish_title)
            subtitle = context.getString(R.string.timeline_head_finish_subtitle)
            iconRes = R.drawable.ic_finish
            progress = null
            goNowPlace = null
        }
    }

    val nextEventTime = nextEvent?.let {
        val distance = it.getFromTimeDistance()
        val distanceText = if (distance >= 60) {
            context.getString(R.string.time_format_1, distance / 60, distance % 60)
        } else {
            context.getString(R.string.time_format_2, distance)
        }
        distanceText + context.getString(R.string.timeline_counting_middle)
    } ?: context.getString(R.string.timeline_counting_free)

    val exam = upcomingExams.firstOrNull()
    return TimelineHeaderState(
        title = title,
        subtitle = subtitle,
        iconRes = iconRes,
        nowProgress = progress,
        goNowPlace = goNowPlace,
        nextEventName = nextEvent?.name ?: "see you",
        nextEventTime = nextEventTime,
        nextIconRes = if (nextEvent != null) R.drawable.ic_baseline_access_alarm_24 else R.drawable.ic_empty,
        examCountdown = exam?.let(SpecialEventReminderUtils::formatExamCountdown),
        examName = if (exam != null) formatExamReminderName(upcomingExams) else null,
    )
}

private fun isBetween(calendar: Calendar, from: TimeInDay, to: TimeInDay): Boolean {
    val now = TimeInDay(calendar)
    return now >= from && now < to
}

private fun formatExamReminderName(exams: List<EventItem>): String {
    val exam = exams.firstOrNull() ?: return ""
    return buildString {
        append(SpecialEventReminderUtils.formatExamName(exam))
        if (exams.size > 1) {
            append(" 等 ")
            append(exams.size)
            append(" 场考试")
        }
    }
}

@Composable
private fun TimelineHeaderCard(
    state: TimelineHeaderState,
    expanded: Boolean,
    weekEvents: List<EventItem>,
    onToggleExpanded: () -> Unit,
    onEventClick: (EventItem) -> Unit,
) {
    val view = LocalView.current
    val isGlass = hitaIsAppleGlassSurface()
    val cardShape = hitaStyleCardShape(20.dp, 16.dp)
    // banner 上是 24sp/16sp 白字：深色主题下 primary/secondary 常是浅色，
    // 白字只有 1.7–3.2:1。按亮度把渐变压暗到 6.5:1，副标题再叠 0.8 alpha 也还有 ~4.7:1。
    val bannerPrimary = MaterialTheme.colorScheme.primary
    // 波普涂鸦深色模式下 secondary 接近纯白，直接参与渐变会把整块压成灰色，先向 primary 收一点。
    val bannerSecondary = if (hitaIsPersona()) {
        lerp(bannerPrimary, MaterialTheme.colorScheme.secondary, 0.45f)
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val bannerScrim = maxOf(
        scrimForWhiteText(bannerPrimary, BANNER_CONTRAST_TARGET),
        scrimForWhiteText(bannerSecondary, BANNER_CONTRAST_TARGET)
    )
    val bannerTop = lerp(bannerPrimary, Color.Black, bannerScrim)
    val bannerBottom = lerp(bannerSecondary, Color.Black, bannerScrim)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HitaTheme.tokens.spacing.lg,
                top = HitaTheme.tokens.spacing.lg,
                end = HitaTheme.tokens.spacing.lg,
                bottom = HitaTheme.tokens.spacing.xl
            )
            .hitaGlassCardModifier(cardShape, elevation = 18.dp)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onToggleExpanded()
            },
        shape = cardShape,
        colors = if (isGlass) {
            hitaGlassCardColors(MaterialTheme.colorScheme.primary, glassAlpha = 0.54f)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isGlass) 0.dp else 14.dp),
        border = hitaGlassCardBorder(alpha = 0.34f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = if (isGlass) {
                            listOf(
                                bannerTop.copy(alpha = 0.48f),
                                bannerBottom.copy(alpha = 0.32f)
                            )
                        } else {
                            listOf(bannerTop, bannerBottom)
                        }
                    )
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(HitaTheme.tokens.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = state.subtitle,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(0.8f)
                    )
                }

                HeaderVisual(state)

                Icon(
                    painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = HitaTheme.tokens.spacing.lg)
                        .size(44.dp)
                        .rotate(if (expanded) 180f else 0f)
                )

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = HitaTheme.tokens.spacing.lg,
                                end = HitaTheme.tokens.spacing.lg,
                                bottom = HitaTheme.tokens.spacing.lg
                            )
                    ) {
                        HeaderInfoPill(
                            title = state.nextEventTime,
                            subtitle = state.nextEventName,
                            icon = state.nextIconRes
                        )
                        state.examCountdown?.let { countdown ->
                            Spacer(modifier = Modifier.height(HitaTheme.tokens.spacing.sm))
                            HeaderInfoPill(
                                title = countdown,
                                subtitle = state.examName.orEmpty(),
                                icon = R.drawable.ic_baseline_error_24
                            )
                        }
                        Spacer(modifier = Modifier.height(HitaTheme.tokens.spacing.sm))
                        if (weekEvents.isEmpty()) {
                            HeaderWeekEmpty()
                        } else {
                            weekEvents.forEach { event ->
                                TimelineTopEventRow(event = event, onClick = { onEventClick(event) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderVisual(state: TimelineHeaderState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
        contentAlignment = Alignment.Center
    ) {
        if (state.nowProgress != null) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(HitaTheme.tokens.spacing.lg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${(state.nowProgress * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(if (state.goNowPlace == null) 110.dp else 80.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(if (state.goNowPlace == null) HitaTheme.tokens.spacing.xl else HitaTheme.tokens.spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(state.iconRes),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                state.goNowPlace?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = HitaTheme.tokens.spacing.xs)
                            .alpha(0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderInfoPill(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(HitaTheme.tokens.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = HitaTheme.tokens.spacing.xs)
                    .alpha(0.8f)
            )
        }
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
private fun HeaderWeekEmpty() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_empty),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.size(80.dp)
        )
    }
}

@Composable
private fun TimelineTopEventRow(event: EventItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val timeText = if (SpecialEventReminderUtils.isToday(event.from.time)) {
        val minutes = event.getFromTimeDistance()
        if (minutes > 60) {
            context.getString(R.string.timeline_countdown_template_hour, minutes.toFloat() / 60.0f)
        } else {
            context.getString(R.string.timeline_countdown_template_minute, minutes)
        }
    } else {
        TimeTools.getDateString(context, event.from.time, true, TTY_REPLACE)
    }
    val rowShape = hitaStyleCardShape(24.dp, 14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = HitaTheme.tokens.spacing.sm)
            .then(
                if (hitaIsPersona()) {
                    Modifier.hitaPersonaCardModifier(rowShape)
                } else {
                    Modifier
                }
            )
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(
                horizontal = HitaTheme.tokens.spacing.md,
                vertical = HitaTheme.tokens.spacing.sm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(
            text = event.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = HitaTheme.tokens.spacing.sm)
        )
        Icon(
            painter = painterResource(R.drawable.ic_baseline_access_time_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = timeText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = HitaTheme.tokens.spacing.xs)
        )
    }
}

@Composable
private fun TimelineHintCard(event: EventItem, onConfirmed: (EventItem) -> Unit) {
    val view = LocalView.current
    val isGlass = hitaIsAppleGlassSurface()
    val cardShape = hitaStyleCardShape(16.dp, 12.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(HitaTheme.tokens.spacing.lg)
            .hitaGlassCardModifier(cardShape, elevation = 12.dp),
        shape = cardShape,
        colors = if (isGlass) {
            hitaGlassCardColors(MaterialTheme.colorScheme.primary, glassAlpha = 0.38f)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isGlass) 0.dp else 8.dp),
        border = hitaGlassCardBorder(alpha = 0.32f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(HitaTheme.tokens.spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hint),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = event.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = HitaTheme.tokens.spacing.sm)
                )
            }
            Text(
                text = stringResource(R.string.navi_hint_button),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onConfirmed(event)
                    }
                    .padding(HitaTheme.tokens.spacing.lg),
            )
        }
    }
}

@Composable
private fun TimelineEventRow(
    event: EventItem,
    isPassed: Boolean,
    isUpcomingExam: Boolean,
    isNow: Boolean,
    isFirstTimeline: Boolean,
    isLastTimeline: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val primary = if (isUpcomingExam) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val secondary = if (isUpcomingExam) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HitaTheme.tokens.spacing.xl),
    ) {
        TimelineRail(isPassed = isPassed, isFirst = isFirstTimeline, isLast = isLastTimeline)
        val cardShape = hitaStyleCardShape(
            roundedRadius = if (isPassed) 24.dp else 16.dp,
            cyberCut = if (isPassed) 16.dp else 12.dp,
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = HitaTheme.tokens.spacing.lg,
                    top = if (isPassed) HitaTheme.tokens.spacing.sm else HitaTheme.tokens.spacing.lg,
                    end = HitaTheme.tokens.spacing.xl,
                    bottom = if (isPassed) HitaTheme.tokens.spacing.sm else HitaTheme.tokens.spacing.lg
                )
                .hitaGlassCardModifier(cardShape, elevation = if (isPassed) 6.dp else 12.dp)
                .clip(cardShape)
                .clickable(onClick = onClick),
            shape = cardShape,
            colors = hitaGlassCardColors(glassAlpha = if (isPassed) 0.32f else 0.48f),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (hitaIsAppleGlassSurface() || isPassed) 0.dp else 8.dp
            ),
            border = hitaGlassCardBorder(alpha = if (isPassed) 0.22f else 0.30f)
        ) {
            if (isPassed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = HitaTheme.tokens.spacing.lg,
                            vertical = HitaTheme.tokens.spacing.sm
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = event.name,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .alpha(0.8f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(HitaTheme.tokens.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = event.name,
                            color = primary,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isUpcomingExam) {
                            Text(
                                text = stringResource(R.string.timeline_exam_reminder_tag),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .padding(top = HitaTheme.tokens.spacing.sm)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(
                                        horizontal = HitaTheme.tokens.spacing.sm,
                                        vertical = HitaTheme.tokens.spacing.xs
                                    )
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = HitaTheme.tokens.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_access_time_24),
                                contentDescription = null,
                                tint = secondary.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isUpcomingExam) {
                                    SpecialEventReminderUtils.formatExamDateTime(event)
                                } else {
                                    "${TimeTools.printTime(event.from.time)}-${TimeTools.printTime(event.to.time)}"
                                },
                                color = secondary.copy(alpha = 0.8f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = HitaTheme.tokens.spacing.sm)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(HitaTheme.tokens.spacing.md))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(HitaTheme.tokens.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_location_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                            val placeText = event.place?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.unknown_location)
                            val placeFontSize = when {
                                placeText.length <= 3 -> 14.sp
                                placeText.length <= 5 -> 12.sp
                                placeText.length <= 8 -> 10.sp
                                else -> 9.sp
                            }
                            Text(
                                text = placeText,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = placeFontSize,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .widthIn(max = 72.dp)
                                    .padding(start = HitaTheme.tokens.spacing.xs)
                            )
                        }
                        if (isNow) {
                            LinearProgressIndicator(
                                progress = { event.getProgress(System.currentTimeMillis()) },
                                modifier = Modifier
                                    .padding(top = HitaTheme.tokens.spacing.sm)
                                    .width(72.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRail(isPassed: Boolean, isFirst: Boolean, isLast: Boolean) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .width(24.dp)
            .height(if (isPassed) 56.dp else 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(HitaTheme.tokens.componentSize.timelineWidth)
                .weight(1f)
                .background(
                    if (isFirst) {
                        Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0f), lineColor)
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(lineColor, lineColor)
                        )
                    }
                )
        )
        Box(
            modifier = Modifier
                .size(if (isPassed) 18.dp else 10.dp)
                .clip(CircleShape)
                .background(
                    if (isPassed) lineColor
                    else MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isPassed) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_check_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .width(HitaTheme.tokens.componentSize.timelineWidth)
                .weight(1f)
                .background(
                    if (isLast) {
                        Brush.verticalGradient(
                            colors = listOf(lineColor, lineColor.copy(alpha = 0f))
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(lineColor, lineColor)
                        )
                    }
                )
        )
    }
}

@Composable
private fun TimelineEmpty() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .padding(HitaTheme.tokens.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_timeline),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(HitaTheme.tokens.spacing.md))
        Text(
            text = stringResource(R.string.timeline_empty_events),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
