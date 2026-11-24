package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayAgendaPlaceholder(state: TodayUiState, action: (TodayAction) -> Unit) {
    TodayCard {
        when (state.status) {
            TodayPageStatus.LOADING -> listOf(0.65f, 0.9f, 0.45f).forEach { width ->
                Spacer(Modifier.fillMaxWidth(width).height(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
            }
            TodayPageStatus.NO_TARGET -> {
                // 紧凑高度下三个入口会挤在一起，这里整体可滚，动作横排换行。
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.today_no_target))
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { action(TodayAction.AddTimetable) }) {
                            Text(stringResource(R.string.today_add_blank))
                        }
                        TextButton(onClick = { action(TodayAction.ImportTimetable) }) {
                            Text(stringResource(R.string.today_import))
                        }
                        TextButton(onClick = { action(TodayAction.SelectTimetable) }) {
                            Text(stringResource(R.string.today_choose))
                        }
                    }
                }
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
    action: (TodayAction) -> Unit) {
    if (state.status == TodayPageStatus.FAILED) item("failed") { TodayCard { TodayAgendaFailure(state, action) } }
    state.agenda?.let { agenda ->
        val completed = agenda.allFinished && agenda.invalidEvents.isEmpty()
        val tomorrow = state.window.date.plusDays(1)
        if (completed) {
            item("complete") { TodayCompletionCard(state, expanded, onExpand) }
            // 展开只翻看今天的课；收起时这块交给明天的安排。
            if (expanded) {
                items(agenda.ended, key = { "event:${it.id}" }) { event ->
                    TodayEventCard(event, state, false) { action(TodayAction.OpenEvent(event.id)) }
                }
            } else if (agenda.tomorrow.isEmpty()) {
                item("tomorrow-empty") { TodayTomorrowEmpty() }
            } else {
                item("tomorrow-header") { TodayTomorrowHeader(tomorrow) }
                items(agenda.tomorrow, key = { "event:${it.id}" }) { event ->
                    TodayEventCard(event, state, false, day = tomorrow) { action(TodayAction.OpenEvent(event.id)) }
                }
            }
        } else {
            if (agenda.ended.isNotEmpty()) item("ended-toggle") { TodayEndedToggle(agenda.ended.size, expanded, onExpand) }
            val shown = if (expanded) agenda.allToday else agenda.remaining
            items(shown, key = { "event:${it.id}" }) { event ->
                TodayEventCard(event, state, agenda.conflictGroups.any { group -> group.any { it.id == event.id } }) {
                    action(TodayAction.OpenEvent(event.id))
                }
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
