package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.*
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
internal fun TodayCompletionCard(state: TodayUiState, action: (TodayAction) -> Unit) {
    val agenda = state.agenda ?: return
    TodayCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_circle_done), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (agenda.allToday.isEmpty()) stringResource(R.string.today_empty)
                else stringResource(R.string.today_finished_count, agenda.completedCount),
                style = todayMeta(), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        agenda.tomorrow?.let { tomorrow ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
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
