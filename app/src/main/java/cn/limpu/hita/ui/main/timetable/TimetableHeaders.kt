package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import cn.limpu.hita.ui.main.timetable.compare.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.map
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface
import cn.limpu.hita.ui.design.hitaIsSoraCloud
import cn.limpu.hita.ui.design.soraCloudCardShape
import cn.limpu.hita.ui.design.SoraIndigo
import cn.limpu.hita.ui.design.SoraOchre
import cn.limpu.hita.ui.design.SoraVermilion
import java.time.ZoneId
import java.util.Calendar

@Composable
internal fun TimetableDowHeader(startDate: Long, monthColor: Color, displayTimeZone: ZoneId? = null) {
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
internal fun TimetableLeftLabels(
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
    val labelFontSize = ((if (compressed) 9f else 10f) * style.fontScale).sp

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
                        fontSize = ((if (compressed) 8f else 12f) * style.fontScale).sp,
                        lineHeight = ((if (compressed) 9f else 12f) * style.fontScale).sp,
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
internal fun TimetableGrid(
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
