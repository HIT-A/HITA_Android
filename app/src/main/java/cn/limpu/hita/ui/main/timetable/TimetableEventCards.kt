package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.CourseBubbleStyle
import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import cn.limpu.hita.ui.main.timetable.compare.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.map
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
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
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.design.soraCloudCourseContentColor
import cn.limpu.hita.ui.main.timetable.views.TimetableCardTextScale
import cn.limpu.hita.ui.main.timetable.views.TimetableOverlapLayout
import cn.limpu.hita.ui.main.timetable.views.TimetableOverlapLayout.PositionedEvent
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableEventLayer(
    events: List<EventItem>,
    startDate: Long,
    startHour: Int,
    style: TimetableStyleSheet,
    dpPerMinute: Dp,
    onEventClick: (EventItem) -> Unit,
    onEventLongClick: ((EventItem, IntOffset) -> Unit)?,
    compressed: Boolean = false,
    displayTimeZone: ZoneId? = null,
) {
    val distinctEvents = remember(events) { events.distinctBy { it.id } }
    val arranged = remember(distinctEvents, displayTimeZone) { TimetableOverlapLayout.arrange(distinctEvents) { TimetableDisplayTime.dayOfWeek(it.from.time, displayTimeZone) } }

    val renderList = remember(arranged, displayTimeZone) { TimetableOverlapLayout.conflictCards(arranged) { TimetableDisplayTime.dayOfWeek(it.from.time, displayTimeZone) } }

    // A sheet belongs to this data/week; invalidate synchronously before rendering.
    var conflictCluster by remember(events, startDate, displayTimeZone) {
        mutableStateOf<List<EventItem>?>(null)
    }
    val baseMinutes = startHour * 60
    BoxWithConstraintsCompat {
        val sectionWidth = maxWidth / 7f
        val cardPlacements = remember(renderList, displayTimeZone, sectionWidth, baseMinutes, dpPerMinute) {
            renderList.map { (positioned, clusterEvents) ->
                val event = positioned.event
                val first = clusterEvents?.minOf { it.from.time } ?: event.from.time
                val last = clusterEvents?.maxOf { it.to.time } ?: event.to.time
                val coordinates = TimetableGridGeometry.card(
                    TimetableDisplayTime.dayOfWeek(event.from.time, displayTimeZone),
                    eventMinutes(first, displayTimeZone), ((last - first) / 60000L).toInt(),
                    startHour, sectionWidth.value, dpPerMinute.value,
                )
                TimetableCardPlacement(
                    positioned, clusterEvents, coordinates.x.dp, coordinates.y.dp,
                    coordinates.width.dp, coordinates.height.dp,
                )
            }
        }

        cardPlacements.forEach { placement ->
            val event = placement.positioned.event
            val clusterEvents = placement.clusterEvents
            if (clusterEvents != null) {
                key("conflict_" + clusterEvents.joinToString("_") { it.id }) {
                    ConflictCascadeStack(
                        clusterEvents = clusterEvents,
                        dayLeft = placement.xOffset - 2.dp,
                        sectionWidth = sectionWidth,
                        clusterTop = placement.yOffset,
                        clusterHeight = placement.height,
                        style = style,
                        baseMinutes = baseMinutes,
                        dpPerMinute = dpPerMinute,
                        displayTimeZone = displayTimeZone,
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
                        onLongClick = onEventLongClick?.let { callback -> { position -> callback(event, position) } },
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
internal fun eventMinutes(epochMillis: Long, displayTimeZone: ZoneId? = null): Int =
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
 * 冲突叠层：有多少门课就叠多少层，每层使用该课的分配色并保持各自真实的时段与高度，
 * 逐级向右下偏移 6dp（恢复级联堆叠的旧观感）；最上层卡片覆盖冲突时间并集并显示
 * 「课程冲突/共N门」文字。小课叠在大课下面：层序按时长升序，顶层为时间最长的一门。
 * 任意层单击/长按都弹出冲突课程列表。
 */
@Composable
private fun ConflictCascadeStack(
    clusterEvents: List<EventItem>,
    dayLeft: Dp,
    sectionWidth: Dp,
    clusterTop: Dp,
    clusterHeight: Dp,
    style: TimetableStyleSheet,
    baseMinutes: Int,
    dpPerMinute: Dp,
    displayTimeZone: ZoneId?,
    onShowConflicts: () -> Unit,
    compressed: Boolean,
) {
    // 时长升序：小课先画（底层），大课后画（顶层）；同级按开始时间、课名稳定排序。
    val layers = remember(clusterEvents) {
        clusterEvents.sortedWith(compareBy({ it.to.time - it.from.time }, { it.from.time }, { it.name }))
    }
    val cascade = 6.dp
    val offsetTotal = layers.size - 1
    val width = (sectionWidth - 4.dp - cascade * offsetTotal).coerceAtLeast(0.dp)
    layers.forEachIndexed { index, event ->
        val isTop = index == layers.lastIndex
        val tint = if (style.isColorEnabled) {
            hitaCourseColor(
                courseKey = event.subjectId.ifBlank { event.name },
                storedColor = event.color,
            )
        } else {
            MaterialTheme.colorScheme.primary
        }
        val top = if (isTop) {
            clusterTop + cascade * index
        } else {
            val topMinutes = (eventMinutes(event.from.time, displayTimeZone) - baseMinutes).coerceAtLeast(0)
            topMinutes.toFloat() * dpPerMinute + cascade * index
        }
        val rawHeight = if (isTop) clusterHeight else event.getDurationInMinutes().coerceAtLeast(15).toFloat() * dpPerMinute
        val height = (rawHeight - cascade * offsetTotal).coerceAtLeast(8.dp)
        TimetableConflictCard(
            count = layers.size,
            style = style,
            tint = tint,
            showText = isTop,
            modifier = Modifier
                .offset(x = dayLeft + 2.dp + cascade * index, y = top)
                .width(width)
                .height(height),
            cardHeight = height,
            onShowConflicts = onShowConflicts,
            compressed = compressed,
        )
    }
}

/**
 * 冲突卡片：默认主题色（colorScheme.primary）的合并卡；叠层模式下每层传入对应课程的
 * 分配色，仅最上层显示「课程冲突/共N门」文字，下层为纯色卡身。单击/长按都弹出冲突课程列表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimetableConflictCard(
    count: Int,
    style: TimetableStyleSheet,
    modifier: Modifier = Modifier,
    cardHeight: Dp,
    tint: Color? = null,
    showText: Boolean = true,
    onShowConflicts: () -> Unit,
    compressed: Boolean = false,
) {
    val view = LocalView.current
    val showConflicts by rememberUpdatedState(onShowConflicts)
    val isAppleGlass = hitaIsAppleGlassSurface()
    val isCyber = hitaIsCyber()
    val isPersona = hitaIsPersona()
    val isSoraCloud = hitaIsSoraCloud()
    val isDeepSpace = hitaIsDeepSpace()
    val isSumi = hitaIsSumi()
    val usesIllustratedFill = isAppleGlass || isSoraCloud
    val courseTint = tint ?: MaterialTheme.colorScheme.primary
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
                        showConflicts()
                    },
                    onTap = { showConflicts() }
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
        if (showText) {
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
                fontSize = ((if (compressed) 8f else 12f) * style.fontScale).sp,
                lineHeight = ((if (compressed) 10f else 15f) * style.fontScale).sp,
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
                    fontSize = ((if (compressed) 6.5f else 9f) * style.fontScale).sp,
                    lineHeight = ((if (compressed) 8f else 12f) * style.fontScale).sp,
                    fontWeight = if (style.isBoldText) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    onLongClick: ((IntOffset) -> Unit)?,
    compressed: Boolean = false,
    cardWidth: Dp = 0.dp,
) {
    val view = LocalView.current
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
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
    // 字号下限随用户字体档位缩放,但保留 4.5sp 绝对可读底线。
    val minTitleSize = ((if (compressed) 4.5f else 6f) * style.fontScale).coerceAtLeast(4.5f)
    val hasPlace = !event.place.isNullOrBlank()
    // 压缩模式下过矮的卡片（<20dp）不渲染地点行，避免文字被裁切
    val showPlace = hasPlace && (!compressed || cardHeight >= 20.dp)
    val nameLength = event.name.length
    val placeLength = event.place?.length ?: 0
    val fontScale = style.fontScale
    val lengthScale = when {
        nameLength <= 4 -> 1.15f
        nameLength <= 6 -> 0.92f
        nameLength <= 8 -> 0.75f
        else -> 0.62f
    }
    // 压缩模式按列宽反推字号（中文约 1em/字）：先保不省略号，再保层级
    val titleWidthCap = if (compressed && cardWidth.value > 0f) {
        (cardWidth.value / nameLength.coerceAtLeast(3)).coerceIn(minTitleSize, 10f * fontScale)
    } else {
        null
    }
    val titleTarget = if (titleWidthCap != null) {
        minOf(13f * textScale * lengthScale * fontScale, titleWidthCap)
    } else {
        13f * textScale * lengthScale * fontScale
    }
    val placeReserveDp = if (showPlace) {
        if (compressed) 6.5f else 14f * textScale * fontScale
    } else {
        0f
    }
    val titleAvailableDp = cardHeight.value
        .minus(verticalPadding.value * 2f)
        .minus(placeReserveDp)
        .minus(if (style.cardIconEnabled) 10f * textScale else 0f)
    val maxTitleFromSpace = (titleAvailableDp / 1.2f).coerceAtMost(16f * fontScale)
    val titleFontSize = titleTarget
        .coerceIn(minTitleSize, maxTitleFromSpace.coerceAtLeast(minTitleSize))
        .sp
    val subtitleFontSize = if (compressed && cardWidth.value > 0f && placeLength > 0) {
        minOf(10f * textScale * fontScale, (cardWidth.value / placeLength.coerceAtLeast(4)).coerceIn(4.5f, 8f * fontScale)).sp
    } else {
        (10f * textScale * fontScale).sp
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
            .pointerInput(event.id, onLongClick != null) {
                detectTapGestures(
                    onLongPress = if (onLongClick == null) null else { localOffset ->
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        longClick?.invoke(
                            IntOffset(
                                cardPositionInWindow.x + localOffset.x.roundToInt(),
                                cardPositionInWindow.y + localOffset.y.roundToInt()
                            )
                        )
                    },
                    onTap = { click() }
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
