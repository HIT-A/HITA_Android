package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R

internal fun todayHasAgendaData(state: TodayUiState): Boolean = state.agenda != null && state.status != TodayPageStatus.LOADING

@Composable
internal fun TodayAgendaPlaceholder(state: TodayUiState, action: (TodayAction) -> Unit) {
    TodayCard {
        when (state.status) {
            TodayPageStatus.LOADING -> listOf(0.65f, 0.9f, 0.45f).forEach { width ->
                Spacer(Modifier.fillMaxWidth(width).height(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
            }
            TodayPageStatus.NO_TARGET -> {
                Text(stringResource(R.string.today_no_target))
                TextButton(onClick = { action(TodayAction.SelectTimetable) }) { Text(stringResource(R.string.today_choose)) }
                TextButton(onClick = { action(TodayAction.ImportTimetable) }) { Text(stringResource(R.string.today_import)) }
            }
            else -> TodayAgendaFailure(state, action)
        }
    }
}

@Composable
private fun TodayAgendaFailure(state: TodayUiState, action: (TodayAction) -> Unit) {
    Text(stringResource(R.string.today_read_failed))
    if (state.agenda != null) Text(stringResource(R.string.today_retained), style = todayLabel())
    TextButton(onClick = { action(TodayAction.Retry(TodayRetrySource.AGENDA)) }) { Text(stringResource(R.string.today_retry)) }
}

internal fun LazyListScope.todayAgendaItems(state: TodayUiState, expanded: Boolean, onExpand: () -> Unit,
    action: (TodayAction) -> Unit, dense: Boolean = false) {
    if (state.status == TodayPageStatus.FAILED) item("failed") { TodayCard { TodayAgendaFailure(state, action) } }
    state.agenda?.let { agenda ->
        val completed = agenda.allFinished && agenda.invalidEvents.isEmpty()
        if (completed) item("complete") { TodayCompletionCard(state, expanded, onExpand) }
        else if (agenda.ended.isNotEmpty()) item("ended-toggle") { TodayEndedToggle(agenda.ended.size, expanded, onExpand) }
        val shown = if (completed) { if (expanded) agenda.ended else emptyList() }
            else if (expanded) agenda.allToday else agenda.remaining
        items(shown, key = { "event:${it.id}" }) { event ->
            TodayEventCard(event, state, agenda.conflictGroups.any { group -> group.any { it.id == event.id } },
                dense = dense) {
                action(TodayAction.OpenEvent(event.id))
            }
        }
    }
}

/** Alpha mask keeps translucent and gradient theme backgrounds intact. */
internal fun Modifier.todayScrollFade(scroll: LazyListState): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    val edge = minOf(16.dp.toPx(), size.height / 2f)
    if (edge > 0f) {
        if (scroll.canScrollBackward) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, edge),
            blendMode = BlendMode.DstIn)
        if (scroll.canScrollForward) drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - edge, size.height),
            blendMode = BlendMode.DstIn)
    }
}
