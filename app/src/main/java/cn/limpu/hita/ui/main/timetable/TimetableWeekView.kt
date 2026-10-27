package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import cn.limpu.hita.ui.main.timetable.compare.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberUpdatedState
import kotlin.math.abs

@Composable
internal fun TimetableWeekContent(
    startDate: Long,
    events: List<EventItem>,
    style: TimetableStyleSheet,
    scheduleStructure: List<TimePeriodInDay>,
    dateColor: Color,
    labelColor: Color,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onEventClick: (EventItem) -> Unit,
    onEventLongClick: ((EventItem, IntOffset) -> Unit)?,
    onAddClick: ((Int, TimePeriodInDay) -> Unit)?,
    eveningHintEnabled: Boolean = true,
    compressed: Boolean = false,
    displayTimeZone: ZoneId? = null,
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
            TimetableGridGeometry.compressedScale(usable.value, totalMinutes)?.dp
        }
    } else {
        null
    }
    val dpPerMinute = compressedDpPerMinute ?: expandedDpPerMinute
    val tableHeight = totalMinutes.toFloat() * dpPerMinute
    // 时间标签抽稀：压缩模式下相邻标签至少间隔 40dp，太密就隔小时显示
    val labelStepHours = if (compressed) {
        TimetableGridGeometry.labelStep(dpPerMinute.value)
    } else {
        1
    }
    var tableWidthPx by remember { mutableStateOf(0) }
    var contentWidthPx by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val currentStart by rememberUpdatedState(startDate)
    val previousWeek by rememberUpdatedState(onPrevWeek)
    val nextWeek by rememberUpdatedState(onNextWeek)
    var dragAccum by remember { mutableStateOf(0f) }
    val animOffset = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    val isAppleGlass = hitaIsAppleGlassSurface()

    // 晚间课程提示：当前周有 ≥18:30 开始的课程，且晚间课程尚未滚入视野时显示；
    // 滚到底部附近自动隐藏，避免遮挡正常课表内容；可在课表显示设置中关闭
    val eveningHintHideThresholdPx = with(density) { 64.dp.toPx() }
    val hasEveningEvents = remember(events, displayTimeZone) {
        events.any { eventMinutes(it.from.time, displayTimeZone) >= EVENING_HINT_START_MINUTES }
    }
    val showEveningHint by remember(hasEveningEvents, eveningHintEnabled, scrollState, eveningHintHideThresholdPx) {
        derivedStateOf {
            TimetableGridGeometry.eveningHint(eveningHintEnabled, hasEveningEvents,
                scrollState.value, scrollState.maxValue, eveningHintHideThresholdPx)
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
                    // Read animation state in the layer phase, without recomposing the grid each frame.
                    val offset = if (isAnimating) animOffset.value else dragAccum
                    translationX = offset
                    alpha = if (contentWidthPx > 0f) {
                        1f - (abs(offset) / contentWidthPx).coerceIn(0f, 1f)
                    } else 1f
                }
                .onSizeChanged { contentWidthPx = it.width.toFloat() }
                .pointerInput(startDate) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (isAnimating) return@detectHorizontalDragGestures
                            val cur = dragAccum
                            val width = contentWidthPx
                            if (cur == 0f || width <= 0f) {
                                dragAccum = 0f
                                return@detectHorizontalDragGestures
                            }
                            isAnimating = true
                            coroutineScope.launch {
                                try {
                                    animOffset.snapTo(cur)
                                    dragAccum = 0f
                                    when (TimetableGridGeometry.swipe(cur, width)) {
                                        -1 -> {
                                            animOffset.animateTo(width, tween(150))
                                            if (currentStart != startDate) return@launch
                                            previousWeek()
                                            animOffset.snapTo(-width)
                                            animOffset.animateTo(0f, tween(150))
                                        }
                                        1 -> {
                                            animOffset.animateTo(-width, tween(150))
                                            if (currentStart != startDate) return@launch
                                            nextWeek()
                                            animOffset.snapTo(width)
                                            animOffset.animateTo(0f, tween(150))
                                        }
                                        else -> animOffset.animateTo(0f, spring())
                                    }
                                } finally {
                                    withContext(NonCancellable) { animOffset.snapTo(0f) }
                                    dragAccum = 0f
                                    isAnimating = false
                                }
                            }
                        },
                        onDragCancel = { dragAccum = 0f },
                        onHorizontalDrag = { _, drag ->
                            if (!isAnimating) {
                                dragAccum = (dragAccum + drag).coerceIn(-contentWidthPx, contentWidthPx)
                            }
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
                        .then(if (onAddClick == null) Modifier else Modifier.pointerInput(startDate, style, tableWidthPx, startHour, endHour, scheduleStructure, dpPerMinute) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val add = onAddClick ?: return@detectTapGestures
                                    val dow = TimetableGridGeometry.dayAt(offset.x, tableWidthPx.toFloat()) ?: return@detectTapGestures
                                    val period = TimetableGridGeometry.periodAt(
                                        y = offset.y,
                                        startHour = startHour,
                                        endHour = endHour,
                                        schedule = scheduleStructure,
                                        unitsPerMinute = with(density) { dpPerMinute.toPx() },
                                    ) ?: return@detectTapGestures
                                    add(dow, period)
                                }
                            )
                        }
                        )
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
                    )
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
        onEventLongClick = null,
        onAddClick = null,
        displayTimeZone = displayTimeZone,
    )
}
