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
import cn.limpu.hita.ui.design.hitaCourseColor

@Composable
internal fun TodayAgendaHeading(state: TodayUiState, expanded: Boolean, onExpand: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (expanded) R.string.today_agenda else if (state.agenda?.active?.isNotEmpty() == true)
                R.string.today_active else R.string.today_upcoming), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (state.agenda?.allToday?.isNotEmpty() == true) {
                TextButton(onClick = onExpand, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (expanded) stringResource(R.string.today_collapse) else stringResource(R.string.today_expand, state.agenda.allToday.size),
                        fontSize = 12.sp)
                    Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), null, Modifier.padding(start = 4.dp).size(14.dp).rotate(if (expanded) -90f else 90f))
                }
            }
        }
        state.scopeName?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically)) }
    }
}

@Composable
internal fun TodayEventCard(event: EventItem, state: TodayUiState, conflict: Boolean, flat: Boolean = false, onClick: () -> Unit) {
    val phase = EventTimePolicy.phase(event.from.time, event.to.time, state.now)
    val ended = phase == EventTimePolicy.Phase.ENDED
    val accent = hitaCourseColor(event.subjectId.ifBlank { event.name }, event.color)
    val container = if (ended) MaterialTheme.colorScheme.surface else todayTonalContainer(accent)
    val strip = if (ended) MaterialTheme.colorScheme.outlineVariant else accent
    val body: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().drawBehind {
            drawRoundRect(if (flat) Color.Transparent else strip, Offset(0f, 6.dp.toPx()), Size(4.dp.toPx(), (size.height - 12.dp.toPx()).coerceAtLeast(0f)), CornerRadius(4.dp.toPx()))
        }.padding(start = if (flat) 0.dp else 16.dp)) {
            val stacked = maxWidth < 240.dp || LocalDensity.current.fontScale > 1.25f
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EventBody(event, state, phase, conflict)
                TodayLocation(event.place, Modifier.align(Alignment.End))
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { EventBody(event, state, phase, conflict) }
                TodayLocation(event.place, Modifier.widthIn(min = 72.dp, max = 104.dp))
            }
        }
    }
    if (flat) Box(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 6.dp)) { body() }
    else TodayCard(container = container, padding = PaddingValues(horizontal = 16.dp, vertical = if (ended) 12.dp else 18.dp),
        onClick = onClick) { body() }
}

@Composable
private fun EventBody(event: EventItem, state: TodayUiState, phase: EventTimePolicy.Phase, conflict: Boolean) {
    val ended = phase == EventTimePolicy.Phase.ENDED
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(event.name, fontSize = if (ended) 16.sp else 19.sp, lineHeight = if (ended) 22.sp else 26.sp, fontWeight = FontWeight.Bold,
            color = if (ended) secondary else MaterialTheme.colorScheme.onSurface)
        val time = todayEventTime(event, state.window)
        val periods = if (event.type == EventItem.TYPE.CLASS && event.fromNumber > 0 && event.lastNumber >= event.fromNumber)
            stringResource(R.string.today_periods, event.fromNumber, event.lastNumber) else null
        Text(if (periods != null) stringResource(R.string.today_pair, time, periods) else time,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = secondary)
        if (phase == EventTimePolicy.Phase.INVALID) Text(stringResource(R.string.today_invalid), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        else if (ended) Text(stringResource(R.string.today_ended), fontSize = 12.sp, color = secondary)
        else event.teacher?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = secondary) }
        if (conflict) Text(stringResource(R.string.today_conflict), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        if (phase == EventTimePolicy.Phase.ACTIVE) {
            LinearProgressIndicator(progress = { EventTimePolicy.progress(event.from.time, event.to.time, state.now) },
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun TodayLocation(place: String?, modifier: Modifier = Modifier) {
    Row(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), RoundedCornerShape(50))
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(R.drawable.ic_baseline_location_24), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(place?.takeIf { it.isNotBlank() } ?: stringResource(R.string.today_place_pending), fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
