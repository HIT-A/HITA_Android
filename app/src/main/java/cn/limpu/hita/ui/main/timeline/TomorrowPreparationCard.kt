package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R

@Composable
internal fun TodayCompletionCard(state: TodayUiState, action: (TodayAction) -> Unit, expanded: Boolean = false,
    onExpand: (() -> Unit)? = null, showCompletion: Boolean = true) {
    val agenda = state.agenda ?: return
    TodayCard {
        if (showCompletion) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
            if (agenda.allToday.isNotEmpty() && onExpand != null) Modifier.clickable(onClick = onExpand) else Modifier), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_circle_done), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (agenda.allToday.isEmpty()) stringResource(R.string.today_empty)
                else if (expanded) stringResource(R.string.today_collapse) else stringResource(R.string.today_finished_count, agenda.completedCount),
                style = todayMeta(), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (agenda.allToday.isNotEmpty() && onExpand != null) Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), null,
                Modifier.size(16.dp).rotate(if (expanded) -90f else 90f))
        }
        agenda.tomorrow?.let { tomorrow ->
            if (showCompletion) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Text(stringResource(R.string.today_tomorrow, tomorrow.date.toString()), style = todayLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tomorrow.firstGroup.isEmpty()) Text(stringResource(if (tomorrow.invalidEvents.isEmpty())
                R.string.today_tomorrow_empty else R.string.today_tomorrow_unknown), style = todayBody())
            tomorrow.firstGroup.forEach { event ->
                TodayEventCard(event, state, tomorrow.firstGroup.size > 1, flat = true) { action(TodayAction.OpenEvent(event.id)) }
            }
            tomorrow.invalidEvents.forEach { event ->
                TodayEventCard(event, state, false, flat = true) { action(TodayAction.OpenEvent(event.id)) }
            }
        }
    }
}
