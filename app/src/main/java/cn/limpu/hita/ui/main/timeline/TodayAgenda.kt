package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetable.display.EventTimePolicy
import cn.limpu.hita.ui.design.HitaTheme
import java.time.LocalDate

@Composable
internal fun TodayEndedToggle(count: Int, expanded: Boolean, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.today_ended_count, count), modifier = Modifier.weight(1f), style = todayMeta(),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (expanded) R.string.today_collapse else R.string.expand), style = todayMeta(),
            color = MaterialTheme.colorScheme.primary)
        Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), null,
            Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 今日课程结束后的明天区域标题：明天 · 11月20日 周五。 */
@Composable
internal fun TodayTomorrowHeader(day: LocalDate) {
    val weekdays = stringArrayResource(R.array.dow2)
    val date = stringResource(R.string.today_month_day, day.monthValue, day.dayOfMonth)
    val weekday = weekdays.getOrElse(day.dayOfWeek.value - 1) { "" }
    Text(stringResource(R.string.today_tomorrow_section, date, weekday), style = todayMeta(),
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
}

/** 明天没有任何安排时的收尾：一句轻提示，不再留出一片空白。 */
@Composable
internal fun TodayTomorrowEmpty() {
    TodayCard(spacing = 0.dp) {
        Text(stringResource(R.string.today_tomorrow_empty), style = todayMeta(),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TodayEventCard(event: EventItem, state: TodayUiState, conflict: Boolean,
    day: LocalDate = state.window.date, onClick: () -> Unit) {
    val phase = EventTimePolicy.phase(event.from.time, event.to.time, state.now)
    val ended = phase == EventTimePolicy.Phase.ENDED
    val active = phase == EventTimePolicy.Phase.ACTIVE
    val accent = todayCourseColor(event)
    val container = todayTonalContainer(accent, muted = ended)
    val strip = if (ended) accent.copy(alpha = 0.8f) else accent
    val body: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().drawBehind {
            drawRoundRect(strip, Offset(0f, 6.dp.toPx()), Size(4.dp.toPx(), (size.height - 12.dp.toPx()).coerceAtLeast(0f)), CornerRadius(4.dp.toPx()))
        }.padding(start = 16.dp)) {
            val stacked = maxWidth < 240.dp || LocalDensity.current.fontScale > 1.25f
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EventBody(event, state, phase, conflict, accent, day)
                TodayLocation(event.place, accent, Modifier.align(Alignment.End))
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { EventBody(event, state, phase, conflict, accent, day) }
                TodayLocation(event.place, accent, Modifier.widthIn(min = 72.dp, max = 104.dp))
            }
        }
    }
    // 正在上的课抬高一点阴影，卡片从同色列表里浮出来。
    TodayCard(container = container, padding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        onClick = onClick, edgeTint = accent, elevation = if (active) 10.dp else 0.dp) { body() }
}

/** 名称、时间、教师三行加两段行距：缺教师时也占住这段高度，列表里的卡片才能对齐。 */
@Composable
private fun todayEventBodyMinHeight(): Dp = with(LocalDensity.current) { (26 + 18 + 16).sp.toDp() } + 10.dp

@Composable
private fun EventBody(event: EventItem, state: TodayUiState, phase: EventTimePolicy.Phase, conflict: Boolean, accent: Color,
    day: LocalDate) {
    val ended = phase == EventTimePolicy.Phase.ENDED
    val active = phase == EventTimePolicy.Phase.ACTIVE
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.heightIn(min = todayEventBodyMinHeight()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // 标签放在标题上方而不是同行：课名较长时与标签争宽度会被挤成两行。
        if (active) TodayActiveBadge()
        Text(event.name, fontSize = if (ended) 16.sp else 19.sp, lineHeight = if (ended) 22.sp else 26.sp,
            fontWeight = FontWeight.Bold, color = if (ended) secondary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val time = todayEventTime(event, state.window, day)
        val periods = if (event.type == EventItem.TYPE.CLASS && event.fromNumber > 0 && event.lastNumber >= event.fromNumber)
            stringResource(R.string.today_periods, event.fromNumber, event.lastNumber) else null
        Text(if (periods != null) stringResource(R.string.today_pair, time, periods) else time,
            style = todayMeta(), fontWeight = FontWeight.Medium, color = secondary)
        if (phase == EventTimePolicy.Phase.INVALID) Text(stringResource(R.string.today_invalid), style = todayLabel(), color = MaterialTheme.colorScheme.error)
        else if (ended) Text(stringResource(R.string.today_ended), style = todayLabel(), color = secondary)
        else event.teacher?.takeIf { it.isNotBlank() }?.let { Text(it, style = todayLabel(), color = secondary) }
        if (conflict) Text(stringResource(R.string.today_conflict), style = todayLabel(), color = MaterialTheme.colorScheme.error)
        if (active) {
            LinearProgressIndicator(progress = { EventTimePolicy.progress(event.from.time, event.to.time, state.now) },
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp),
                color = accent, trackColor = accent.copy(alpha = 0.12f),
                gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

/** 「进行中」标签：白底深字，浅色卡片上才分得出来；深色主题换成浅一档的半透明底。 */
@Composable
private fun TodayActiveBadge() {
    Row(
        modifier = Modifier.background(
            if (HitaTheme.isDark) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            RoundedCornerShape(50),
        ).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Text(stringResource(R.string.today_active), fontSize = 10.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TodayLocation(place: String?, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.background(accent.copy(alpha = 0.12f), RoundedCornerShape(50))
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(R.drawable.ic_baseline_location_24), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(place?.takeIf { it.isNotBlank() } ?: stringResource(R.string.today_place_pending), style = todayMeta(),
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
