package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R

@Composable
internal fun TodayCompletionCard(state: TodayUiState, expanded: Boolean = false, onExpand: (() -> Unit)? = null) {
    val agenda = state.agenda ?: return
    TodayCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
            if (agenda.allToday.isNotEmpty() && onExpand != null) Modifier.clickable(onClick = onExpand) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_circle_done), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (agenda.allToday.isEmpty()) stringResource(R.string.today_empty)
                else stringResource(R.string.today_finished_count, agenda.completedCount), modifier = Modifier.weight(1f),
                style = todayMeta(), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (agenda.allToday.isNotEmpty() && onExpand != null) {
                Text(stringResource(if (expanded) R.string.today_collapse else R.string.expand), style = todayMeta(),
                    color = MaterialTheme.colorScheme.primary)
                Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), null,
                    Modifier.size(16.dp).rotate(if (expanded) -90f else 90f))
            }
        }
    }
}
