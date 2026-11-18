package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem

@Composable
internal fun TodayScreen(state: TodayUiState, hints: List<EventItem>, onHint: (EventItem) -> Unit,
    onAction: (TodayAction) -> Unit,
    bottomClearance: androidx.compose.ui.unit.Dp = cn.limpu.hita.ui.main.rememberMainBottomBarClearance()) {
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider("${state.scopeKey}:${state.window.date}") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val scroll = rememberLazyListState()
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp,
                bottom = bottomClearance + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("header") { TodayHeader(state) }
            when (state.status) {
                TodayPageStatus.LOADING -> item("loading") {
                    TodayCard {
                        listOf(0.65f, 0.9f, 0.45f).forEach { width ->
                            Spacer(Modifier.fillMaxWidth(width).height(12.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
                        }
                    }
                }
                TodayPageStatus.NO_TARGET -> item("no-target") {
                    TodayCard {
                        Text(stringResource(R.string.today_no_target))
                        TextButton(onClick = { onAction(TodayAction.SelectTimetable) }) { Text(stringResource(R.string.today_choose)) }
                        TextButton(onClick = { onAction(TodayAction.ImportTimetable) }) { Text(stringResource(R.string.today_import)) }
                    }
                }
                TodayPageStatus.FAILED -> item("failed") {
                    TodayCard {
                        Text(stringResource(R.string.today_read_failed))
                        if (state.agenda != null) Text(stringResource(R.string.today_retained))
                        TextButton(onClick = { onAction(TodayAction.Retry(TodayRetrySource.AGENDA)) }) { Text(stringResource(R.string.today_retry)) }
                    }
                }
                TodayPageStatus.READY -> Unit
            }
            state.agenda?.let { agenda ->
                item("agenda-heading") { TodayAgendaHeading(state, expanded) { expanded = !expanded } }
                val completed = agenda.allFinished && agenda.invalidEvents.isEmpty()
                if (completed) item("complete") { TodayCompletionCard(state, onAction) }
                val shown = if (expanded) agenda.allToday else if (completed) emptyList() else agenda.defaultVisible
                items(shown, key = { "event:${it.id}" }) { event ->
                    TodayEventCard(event, state, agenda.conflictGroups.any { group -> group.any { it.id == event.id } }) {
                        onAction(TodayAction.OpenEvent(event.id))
                    }
                }
                // Invalid entries never suppress tomorrow, but must remain visible themselves.
                if (!completed && agenda.tomorrow != null) item("tomorrow") { TodayCompletionCard(state, onAction) }
            }
            item("summaries") { TodaySummaryCards(state, onAction) }
            items(hints, key = { "hint:${it.id}" }) { hint ->
                TodayCard {
                    Text(hint.name)
                    TextButton(onClick = { onHint(hint) }) { Text(stringResource(R.string.today_hint_confirm)) }
                }
            }
        }
    }
}
