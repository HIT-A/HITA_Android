package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetable.display.EventTimePolicy

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

@Composable
internal fun TodayEventCard(event: EventItem, state: TodayUiState, conflict: Boolean, flat: Boolean = false, onClick: () -> Unit) {
    val phase = EventTimePolicy.phase(event.from.time, event.to.time, state.now)
    val ended = phase == EventTimePolicy.Phase.ENDED
    val accent = todayCourseColor(event)
    val container = todayTonalContainer(accent, muted = ended)
    val strip = if (ended) accent.copy(alpha = 0.8f) else accent
    val body: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().drawBehind {
            drawRoundRect(strip, Offset(0f, 6.dp.toPx()), Size(4.dp.toPx(), (size.height - 12.dp.toPx()).coerceAtLeast(0f)), CornerRadius(4.dp.toPx()))
        }.padding(start = 16.dp)) {
            val stacked = maxWidth < 240.dp || LocalDensity.current.fontScale > 1.25f
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EventBody(event, state, phase, conflict, accent)
                TodayLocation(event.place, accent, Modifier.align(Alignment.End))
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { EventBody(event, state, phase, conflict, accent) }
                TodayLocation(event.place, accent, Modifier.widthIn(min = 72.dp, max = 104.dp))
            }
        }
    }
    if (flat) Box(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 6.dp)) { body() }
    else TodayCard(container = container, padding = PaddingValues(horizontal = 16.dp, vertical = if (ended) 12.dp else 18.dp),
        onClick = onClick, edgeTint = accent) { body() }
}

@Composable
private fun EventBody(event: EventItem, state: TodayUiState, phase: EventTimePolicy.Phase, conflict: Boolean, accent: Color) {
    val ended = phase == EventTimePolicy.Phase.ENDED
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(event.name, fontSize = if (ended) 16.sp else 19.sp, lineHeight = if (ended) 22.sp else 26.sp, fontWeight = FontWeight.Bold,
            color = if (ended) secondary else MaterialTheme.colorScheme.onSurface)
        val time = todayEventTime(event, state.window)
        val periods = if (event.type == EventItem.TYPE.CLASS && event.fromNumber > 0 && event.lastNumber >= event.fromNumber)
            stringResource(R.string.today_periods, event.fromNumber, event.lastNumber) else null
        Text(if (periods != null) stringResource(R.string.today_pair, time, periods) else time,
            style = todayMeta(), fontWeight = FontWeight.Medium, color = secondary)
        if (phase == EventTimePolicy.Phase.INVALID) Text(stringResource(R.string.today_invalid), style = todayLabel(), color = MaterialTheme.colorScheme.error)
        else if (ended) Text(stringResource(R.string.today_ended), style = todayLabel(), color = secondary)
        else event.teacher?.takeIf { it.isNotBlank() }?.let { Text(it, style = todayLabel(), color = secondary) }
        if (conflict) Text(stringResource(R.string.today_conflict), style = todayLabel(), color = MaterialTheme.colorScheme.error)
        if (phase == EventTimePolicy.Phase.ACTIVE) {
            LinearProgressIndicator(progress = { EventTimePolicy.progress(event.from.time, event.to.time, state.now) },
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp),
                color = accent, trackColor = accent.copy(alpha = 0.12f),
                gapSize = 0.dp, drawStopIndicator = {})
        }
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
