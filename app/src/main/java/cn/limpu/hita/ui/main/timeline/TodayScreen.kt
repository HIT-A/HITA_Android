package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetable.display.EventTimePolicy
import cn.limpu.hita.ui.design.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun TodayScreen(state: TodayUiState, hints: List<EventItem>, onHint: (EventItem) -> Unit,
    onAction: (TodayAction) -> Unit) {
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider("${state.scopeKey}:${state.window.date}") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val scroll = rememberLazyListState()
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
            // Main navigation and the mini course strip float over the fragment.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp,
                bottom = cn.limpu.hita.ui.main.dockBarChromePadding().calculateBottomPadding() + 128.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("header") { TodayHeader(state) }
            when (state.status) {
                TodayPageStatus.LOADING -> item("loading") { Text(stringResource(R.string.today_loading)) }
                TodayPageStatus.NO_TARGET -> item("no-target") {
                    TodayCard { Text(stringResource(R.string.today_no_target))
                        TextButton(onClick = { onAction(TodayAction.SelectTimetable) }) { Text(stringResource(R.string.today_choose)) }
                        TextButton(onClick = { onAction(TodayAction.ImportTimetable) }) { Text(stringResource(R.string.today_import)) }
                    }
                }
                TodayPageStatus.FAILED -> item("failed") {
                    TodayCard { Text(stringResource(if (state.agenda == null) R.string.today_read_failed else R.string.today_retained))
                        TextButton(onClick = { onAction(TodayAction.Retry(TodayRetrySource.AGENDA)) }) { Text(stringResource(R.string.today_retry)) }
                    }
                }
                TodayPageStatus.READY -> Unit
            }
            item("summaries") { TodaySummaries(state, onAction) }
            state.agenda?.let { agenda ->
                item("agenda-heading") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.today_agenda), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) stringResource(R.string.today_collapse)
                                else stringResource(R.string.today_expand, agenda.allToday.size))
                        }
                    }
                }
                val shown = if (expanded) agenda.allToday else agenda.defaultVisible
                if (shown.isEmpty()) item("empty") {
                    Text(stringResource(if (agenda.allToday.isEmpty()) R.string.today_empty else R.string.today_finished_count,
                        agenda.completedCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { "event:${it.id}" }) { event ->
                    TodayEventRow(event, state, agenda.conflictGroups.any { group -> group.any { it.id == event.id } },
                        onClick = { onAction(TodayAction.OpenEvent(event.id)) })
                }
                agenda.tomorrow?.let { tomorrow ->
                    item("tomorrow-heading") { Text(stringResource(R.string.today_tomorrow, tomorrow.date.toString()),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                    if (tomorrow.firstGroup.isEmpty()) item("tomorrow-empty") {
                        Text(stringResource(if (tomorrow.invalidEvents.isEmpty()) R.string.today_tomorrow_empty else R.string.today_tomorrow_unknown))
                    }
                    items(tomorrow.firstGroup, key = { "tomorrow:${it.id}" }) { event ->
                        TodayEventRow(event, state, tomorrow.firstGroup.size > 1,
                            onClick = { onAction(TodayAction.OpenEvent(event.id)) })
                    }
                    items(tomorrow.invalidEvents, key = { "tomorrow-invalid:${it.id}" }) { event ->
                        TodayEventRow(event, state, false, onClick = { onAction(TodayAction.OpenEvent(event.id)) })
                    }
                }
            }
            items(hints, key = { "hint:${it.id}" }) { hint ->
                TodayCard { Text(hint.name)
                    TextButton(onClick = { onHint(hint) }) { Text(stringResource(R.string.today_hint_confirm)) }
                }
            }
            item("bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TodayHeader(state: TodayUiState) {
    val header = state.header
    val greeting = when (header.greeting) {
        TodayGreeting.EARLY -> R.string.today_greeting_early
        TodayGreeting.MORNING -> R.string.today_greeting_morning
        TodayGreeting.AFTERNOON -> R.string.today_greeting_afternoon
        TodayGreeting.EVENING -> R.string.today_greeting_evening
        TodayGreeting.LUNCH -> R.string.today_greeting_lunch
        TodayGreeting.DINNER -> R.string.today_greeting_dinner
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(greeting), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        state.scopeName?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        when (header.focus) {
            TodayFocus.ACTIVE, TodayFocus.UPCOMING -> {
                Text(stringResource(if (header.focus == TodayFocus.ACTIVE) R.string.today_active else R.string.today_upcoming),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                if (header.count > 1) Text(stringResource(R.string.today_focus_group, header.count), style = MaterialTheme.typography.titleLarge)
                else header.title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                header.remainingMinutes?.let { Text(stringResource(
                    if (header.focus == TodayFocus.ACTIVE) R.string.today_until_end else R.string.today_until_start, it)) }
                header.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            }
            TodayFocus.FINISHED -> Text(stringResource(R.string.today_finished))
            TodayFocus.EMPTY -> Text(stringResource(R.string.today_empty))
            TodayFocus.NONE -> Unit
        }
    }
}

@Composable
private fun TodaySummaries(state: TodayUiState, action: (TodayAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.today_focus), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        state.homework?.let { summary ->
            HomeworkSummary(summary.local, state.window.zoneId, action)
            if (summary.grader.status != TodaySourceStatus.UNAVAILABLE) HomeworkSummary(summary.grader, state.window.zoneId, action)
        }
        state.exams?.takeIf { state.agenda != null }?.let { exams ->
            Column(Modifier.fillMaxWidth().clickable { action(TodayAction.OpenExams) }.heightIn(min = 48.dp).padding(vertical = 8.dp)) {
                Text(stringResource(R.string.today_exam_summary, exams.remaining.size, exams.timeToConfirm.size))
                if (exams.coverage.isEmpty()) Text(stringResource(R.string.today_exam_unknown), style = MaterialTheme.typography.bodySmall)
                if (exams.unbound.isNotEmpty()) Text(stringResource(R.string.today_exam_unbound, exams.unbound.size), style = MaterialTheme.typography.bodySmall)
                if (exams.coverage.any { it.partial || it.truncated || it.status != TodaySourceStatus.READY })
                    Text(stringResource(R.string.today_exam_partial), style = MaterialTheme.typography.bodySmall)
                exams.timeToConfirm.forEach { Text(stringResource(R.string.today_exam_pending, it.title)) }
            }
        }
    }
}

@Composable
private fun HomeworkSummary(summary: TodayHomeworkSourceSummary, zone: java.time.ZoneId, action: (TodayAction) -> Unit) {
    val source = stringResource(if (summary.source == TodayHomeworkSource.LOCAL) R.string.today_homework_local else R.string.today_homework_grader)
    Column(Modifier.fillMaxWidth().clickable { action(TodayAction.OpenHomework(summary.source)) }.heightIn(min = 48.dp).padding(vertical = 8.dp)) {
        Text(if (summary.dueToday != null) stringResource(R.string.today_homework_counts, source, summary.dueToday, summary.overdue ?: 0)
            else stringResource(R.string.today_source_status, source, stringResource(when (summary.status) {
                TodaySourceStatus.LOADING -> R.string.today_loading
                TodaySourceStatus.DISCONNECTED -> R.string.today_disconnected
                TodaySourceStatus.FAILED -> R.string.today_read_failed
                else -> R.string.today_unknown
            })))
        if ((summary.undated ?: 0) > 0 || (summary.invalidDeadline ?: 0) > 0)
            Text(stringResource(R.string.today_homework_undated, (summary.undated ?: 0) + (summary.invalidDeadline ?: 0)), style = MaterialTheme.typography.bodySmall)
        summary.earliestDeadline?.let { Text(stringResource(R.string.today_homework_next, it.title,
            Instant.ofEpochMilli(it.dueAt).atZone(if (summary.source == TodayHomeworkSource.GRADER) java.time.ZoneId.of("Asia/Shanghai") else zone)
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))), style = MaterialTheme.typography.bodySmall) }
        if (summary.status == TodaySourceStatus.FAILED) {
            if (summary.dueToday != null) Text(stringResource(R.string.today_retained), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { action(TodayAction.Retry(if (summary.source == TodayHomeworkSource.LOCAL) TodayRetrySource.HOMEWORK else TodayRetrySource.GRADER)) }) {
                Text(stringResource(R.string.today_retry))
            }
        }
    }
}

@Composable
private fun TodayEventRow(event: EventItem, state: TodayUiState, conflict: Boolean, onClick: () -> Unit) {
    val phase = EventTimePolicy.phase(event.from.time, event.to.time, state.now)
    val time = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    TodayCard(onClick) {
        Text(stringResource(when (phase) {
            EventTimePolicy.Phase.ACTIVE -> R.string.today_active
            EventTimePolicy.Phase.UPCOMING -> R.string.today_upcoming
            EventTimePolicy.Phase.ENDED -> R.string.today_ended
            EventTimePolicy.Phase.INVALID -> R.string.today_invalid
        }), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text(event.name, style = MaterialTheme.typography.titleMedium)
        Text("${event.from.toInstant().atZone(state.window.zoneId).format(time)} – ${event.to.toInstant().atZone(state.window.zoneId).format(time)}",
            style = MaterialTheme.typography.bodyMedium)
        event.place?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (conflict) Text(stringResource(R.string.today_conflict), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TodayCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = hitaStyleCardShape(20.dp, 8.dp)
    Card(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .hitaGlassCardModifier(shape), shape = shape, colors = hitaGlassCardColors(), border = hitaGlassCardBorder()) {
        Column(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
