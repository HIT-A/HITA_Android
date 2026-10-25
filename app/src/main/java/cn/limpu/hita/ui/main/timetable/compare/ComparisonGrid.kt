package cn.limpu.hita.ui.main.timetable.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.feature.timetablecompare.ComparisonDay
import cn.limpu.hita.feature.timetablecompare.PeriodStatus
import cn.limpu.hita.feature.timetablecompare.TimeSpan
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaCourseColor
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface
import cn.limpu.hita.ui.design.hitaIsCyber
import cn.limpu.hita.ui.design.hitaIsDeepSpace
import cn.limpu.hita.ui.design.hitaIsPersona
import cn.limpu.hita.ui.design.hitaIsSoraCloud
import cn.limpu.hita.ui.design.hitaIsSumi
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.main.timetable.ColorContrast
import cn.limpu.hita.data.model.timetable.CourseBubbleStyle
import cn.limpu.hita.ui.main.timetable.TimetableDisplayTime
import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import java.time.Instant
import java.time.format.DateTimeFormatter

private val blockTimeFormat = DateTimeFormatter.ofPattern("H:mm")
private val dayDateFormat = DateTimeFormatter.ofPattern("M/d")
private const val GRID_DP_PER_MINUTE = 0.72f

/** 一个待渲染块：合并后的时间段 + 是否忙碌（false = 空闲）。 */
internal data class ComparisonBlock(val span: TimeSpan, val busy: Boolean)

/**
 * 一天只有两门「课」：忙碌（任一参与者该节有课）与空闲（全部无课）。
 * 连续同状态的节次像课表连排课一样合并成一个大框；课间（≤20 分钟）不打断，
 * 午休/晚休等大间隔（三校区均 ≥50 分钟，如深圳 12:15–14:00、17:45–18:45）断开，
 * 不覆盖无课的饭点时段。
 */
internal fun mergedBlocks(day: ComparisonDay, showFree: Boolean, showBusy: Boolean): List<ComparisonBlock> {
    val merged = mutableListOf<ComparisonBlock>()
    var previous: ComparisonBlock? = null
    day.slots.sortedBy { it.span.startMillis }.forEach { slot ->
        val block = when {
            slot.status == PeriodStatus.UNKNOWN -> null
            slot.status == PeriodStatus.FREE -> if (showFree) ComparisonBlock(slot.span, false) else null
            else -> if (showBusy) ComparisonBlock(slot.span, true) else null
        }
        val last = previous
        if (block != null && last != null && last.busy == block.busy &&
            block.span.startMillis - last.span.endMillis <= CLASS_BREAK_THRESHOLD_MS) {
            val joined = ComparisonBlock(
                TimeSpan(last.span.startMillis, maxOf(last.span.endMillis, block.span.endMillis)), last.busy)
            merged[merged.lastIndex] = joined
            previous = joined
        } else {
            if (block != null) merged += block
            previous = block
        }
    }
    return merged
}

/** Only short class breaks (at most twenty minutes) may be included in a block. */
private const val CLASS_BREAK_THRESHOLD_MS = 20 * 60 * 1000L

internal fun comparisonViewport(config: ComparisonConfig, periods: List<cn.limpu.hita.feature.timetablecompare.ComparisonPeriod>): Pair<Int, Int> {
    val first = periods.first().startMinute
    val last = periods.last().endMinute
    if (!config.rangeCustomized) return first to last
    val start = config.startMinute.coerceIn(first, last - 1)
    return start to config.endMinute.coerceIn(start + 1, last)
}

private fun blockTime(millis: Long): String =
    blockTimeFormat.format(Instant.ofEpochMilli(millis).atZone(CAMPUS_ZONE))

/**
 * 对比周视图网格：布局完全镜像主课表——日头固定在滚动区外；滚动区内左侧时间轴
 * 与右侧七天共用同一个高度容器；时间标签、整点线、半刻线、日分隔线与块都按
 * 「分钟 × dpPerMinute」的同一坐标系绝对定位，严格对齐。
 */
@Composable
fun ComparisonGrid(
    ready: ComparisonUiState.Ready,
    monday: Long,
    onSlotDetails: (TimeSpan) -> Unit,
) {
    val weekdayNames = stringArrayResource(R.array.dow2)
    // 视口：用户在面板确认过就按确认范围；否则覆盖课表作息的首节至末节
    // （三校区作息不同，本部/威海 08:00 起，固定默认值会裁掉首节）。
    val periods = ready.source.own.periods
    val (startMinute, endMinute) = comparisonViewport(ready.config, periods)
    val windowMinutes = (endMinute - startMinute).coerceAtLeast(1)
    val gridHeight = (windowMinutes * GRID_DP_PER_MINUTE).dp
    val firstHour = (startMinute + 59) / 60
    val lastHour = endMinute / 60
    val labelFontSize = 10.sp

    Column(Modifier.fillMaxWidth()) {
        // 日头：固定在滚动区之外，与下方日列同为 weight(1f) 七等分，逐列对齐。
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(40.dp))
            ready.result.days.forEachIndexed { dayIndex, day ->
                Column(
                    Modifier.weight(1f).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(weekdayNames[dayIndex], style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(dayDateFormat.format(day.date), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // 时间轴：与主课表 TimetableLeftLabels 一致，Box 内绝对定位，标签中心对齐整点。
            Box(Modifier.width(40.dp).height(gridHeight)) {
                for (hour in firstHour..lastHour) {
                    Text(
                        "$hour:00",
                        fontSize = labelFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .offset(y = ((hour * 60 - startMinute) * GRID_DP_PER_MINUTE).dp - (labelFontSize.value / 2).dp)
                            .fillMaxWidth(),
                    )
                }
            }
            // 七天共用一个大容器：背景网格线与课程块在同一坐标系绝对定位（同主课表）。
            Box(Modifier.weight(1f).height(gridHeight)) {
                ComparisonGridBackground(startMinute = startMinute, endMinute = endMinute, monday = monday)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sectionWidth = maxWidth / 7f
                    ready.result.days.forEachIndexed { dayIndex, day ->
                        val midnight = day.date.atStartOfDay(CAMPUS_ZONE).toInstant().toEpochMilli()
                        mergedBlocks(day, ready.config.showFree, ready.config.showConflicts).forEach { block ->
                            val topMinutes = (block.span.startMillis - midnight) / 60_000f - startMinute
                            val bottomMinutes = (block.span.endMillis - midnight) / 60_000f - startMinute
                            if (bottomMinutes <= 0f || topMinutes >= windowMinutes) {
                                return@forEach
                            }
                            val clampedTop = topMinutes.coerceAtLeast(0f)
                            val clampedBottom = bottomMinutes.coerceAtMost(windowMinutes.toFloat())
                            val blockHeight = (clampedBottom - clampedTop) * GRID_DP_PER_MINUTE
                            ComparisonBlockCard(
                                block = block,
                                height = blockHeight.dp,
                                modifier = Modifier
                                    .offset(x = sectionWidth * dayIndex, y = (clampedTop * GRID_DP_PER_MINUTE).dp)
                                    .width(sectionWidth)
                                    .height(blockHeight.dp),
                                onClick = { onSlotDetails(block.span) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 背景网格：今日高亮 + 半小时细线 + 整点虚线 + 六条日分隔线，绘制方式同主课表 TimetableGrid。 */
@Composable
private fun ComparisonGridBackground(startMinute: Int, endMinute: Int, monday: Long) {
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val halfLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val separatorColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val todayColor = Color(TimetableStyleSheet().todayBGColor)
    val now = System.currentTimeMillis()
    val isCurrentWeek = TimetableDisplayTime.isInWeek(monday, now, CAMPUS_ZONE)
    val todayDow = TimetableDisplayTime.dayOfWeek(now, CAMPUS_ZONE)
    Canvas(Modifier.fillMaxSize()) {
        val sectionWidth = size.width / 7f
        if (isCurrentWeek) {
            drawRect(todayColor, Offset(sectionWidth * (todayDow - 1), 0f), Size(sectionWidth, size.height))
        }
        val pxPerMinute = GRID_DP_PER_MINUTE.dp.toPx()
        fun yOf(minute: Int) = (minute - startMinute) * pxPerMinute
        // 半刻度细线（整点除外）。
        var half = ((startMinute + 29) / 30) * 30
        while (half <= endMinute) {
            if (half % 60 != 0) {
                drawLine(halfLineColor, Offset(0f, yOf(half)), Offset(size.width, yOf(half)), strokeWidth = 1f)
            }
            half += 30
        }
        // 整点虚线。
        val dash = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)
        for (hour in ((startMinute + 59) / 60)..(endMinute / 60)) {
            drawLine(lineColor, Offset(0f, yOf(hour * 60)), Offset(size.width, yOf(hour * 60)),
                strokeWidth = 1f, pathEffect = dash)
        }
        // 日分隔线：Canvas 绘制不占布局宽度，日头与列严格等宽对齐。
        for (i in 1..6) {
            val x = sectionWidth * i
            drawLine(separatorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
    }
}

/**
 * 忙碌/空闲块：沿用主课表课程卡的配色与质感配方（课程色板、气泡风格、透明度、
 * 渐变、描边与文字对比度），深浅模式与全部视觉风格自适应。
 */
@Composable
private fun ComparisonBlockCard(
    block: ComparisonBlock,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val label = stringResource(if (block.busy) R.string.comparison_conflict_block else R.string.comparison_free_block)
    // 两个伪课程键走课程色板分配，保证两者不同色且随主题切换。
    val tint = hitaCourseColor(if (block.busy) "对比:忙碌" else "对比:空闲", 0)
    val isPersona = hitaIsPersona()
    val isAppleGlass = hitaIsAppleGlassSurface()
    val isSumi = hitaIsSumi()
    val isCyber = hitaIsCyber()
    val isSoraCloud = hitaIsSoraCloud()
    val isDeepSpace = hitaIsDeepSpace()
    val style = TimetableStyleSheet()
    val bubble = style.courseBubbleStyle
    // Sumi 卡片保持最浅水墨；其余配方与主课表课程卡一致。
    val baseAlpha = (if (isSumi) 20 else style.cardOpacity).coerceIn(20, 100) / 100f
    val backgroundAlpha = when {
        isAppleGlass -> (baseAlpha * 0.34f).coerceIn(0.10f, 0.18f)
        isPersona -> 1f
        else -> baseAlpha * when (bubble) {
            CourseBubbleStyle.SOLID -> 1f
            CourseBubbleStyle.TONAL -> 0.32f
            CourseBubbleStyle.OUTLINE -> 0.12f
        }
    }
    val background = tint.copy(alpha = backgroundAlpha)
    val backgroundBrush = if (!isPersona && style.isFadeEnabled) {
        Brush.linearGradient(listOf(background.copy(alpha = background.alpha * 0.72f), background))
    } else {
        SolidColor(background)
    }
    val borderColor = when {
        isPersona -> Color.Transparent
        bubble == CourseBubbleStyle.OUTLINE -> tint.copy(alpha = 0.88f)
        isAppleGlass || isCyber || isSoraCloud || isDeepSpace || isSumi -> Color.Transparent
        else -> tint.copy(alpha = 0.28f)
    }
    val borderWidth = if (bubble == CourseBubbleStyle.OUTLINE && !isPersona) 1.25.dp else 0.5.dp
    val shape = hitaStyleCardShape(HitaTheme.tokens.radius.md, 10.dp)
    // 文字对比度按可见合成底色计算，与课程卡一致。
    val effectiveBg = background.compositeOver(MaterialTheme.colorScheme.surface).toArgb()
    val solidText = bubble == CourseBubbleStyle.SOLID || isPersona
    val titleColor = if (solidText) Color(ColorContrast.contrastText(effectiveBg))
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    val timeColor = if (solidText) Color(ColorContrast.contrastText(effectiveBg)).copy(alpha = 0.76f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

    Box(
        modifier = modifier.padding(1.dp)
            .then(if (isAppleGlass || isSoraCloud) Modifier else Modifier.hitaGlassCardModifier(shape, 8.dp, tint))
            .then(if (isSoraCloud) Modifier else Modifier.clip(shape))
            .background(backgroundBrush)
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp)) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (height >= 40.dp) {
                Text(
                    blockTime(block.span.startMillis),
                    fontSize = 9.sp,
                    color = timeColor,
                    maxLines = 1,
                )
                Text(
                    blockTime(block.span.endMillis),
                    fontSize = 9.sp,
                    color = timeColor,
                    maxLines = 1,
                )
            }
        }
    }
}
