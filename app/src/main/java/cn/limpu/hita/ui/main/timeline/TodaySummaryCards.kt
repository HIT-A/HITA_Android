package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun TodaySummaryCards(state: TodayUiState, action: (TodayAction) -> Unit) {
    if (state.homework == null && state.exams == null) return
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.25f) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.exams != null && state.agenda != null) ExamSummary(state, Modifier, action)
                state.homework?.let { HomeworkSummary(it, Modifier, action) }
            }
        } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.exams != null && state.agenda != null) ExamSummary(state, Modifier.weight(1f).fillMaxHeight(), action)
            state.homework?.let { HomeworkSummary(it, Modifier.weight(1f).fillMaxHeight(), action) }
        }
    }
}

@Composable
private fun ExamSummary(state: TodayUiState, modifier: Modifier, action: (TodayAction) -> Unit) {
    val exams = state.exams ?: return
    val nearest = exams.remaining.firstOrNull()
    TodayCard(modifier, padding = PaddingValues(16.dp), onClick = { action(TodayAction.OpenExams) }) {
        val remote = exams.remaining.any { it.source == TodayExamSource.EAS_QUERY } || exams.timeToConfirm.isNotEmpty()
        SummaryLabel(R.drawable.ic_baseline_edit_24, stringResource(if (remote) R.string.today_exam_card_mixed else R.string.today_exam_card))
        SummaryValue(exams.remaining.size.toString(), stringResource(R.string.today_exam_unit))
        val detail = when {
            exams.timeToConfirm.isNotEmpty() -> stringResource(R.string.today_exam_time_pending, exams.timeToConfirm.size)
            nearest?.start != null -> {
                val time = Instant.ofEpochMilli(nearest.start).atZone(state.window.zoneId).format(DateTimeFormatter.ofPattern("HH:mm"))
                nearest.place?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.today_pair, time, it) } ?: time
            }
            exams.importedCount > 0 -> stringResource(R.string.today_exam_ended)
            else -> stringResource(R.string.today_exam_empty)
        }
        SummaryText(detail)
        val source = when {
            exams.coverage.any { it.status == TodaySourceStatus.FAILED } || exams.remaining.any { it.retained } -> stringResource(R.string.today_retained)
            exams.coverage.any { it.status == TodaySourceStatus.LOADING } -> stringResource(R.string.today_loading)
            exams.unbound.isNotEmpty() -> stringResource(R.string.today_exam_other, exams.unbound.size)
            exams.coverage.any { it.partial || it.truncated } -> stringResource(R.string.today_exam_check)
            exams.coverage.none { it.targetTimetableId in state.memberIds } -> stringResource(R.string.today_query_pending)
            else -> stringResource(if (remote) R.string.today_exam_eas else R.string.today_imported)
        }
        SummaryText(source, small = true)
    }
}

@Composable
private fun HomeworkSummary(summary: TodayHomeworkSummary, modifier: Modifier, action: (TodayAction) -> Unit) {
    val model = todayHomeworkCard(summary)
    val label = if (model.known.size == 1) sourceName(model.singleSource ?: TodayHomeworkSource.LOCAL)
        else stringResource(R.string.today_homework_card)
    val source = model.singleSource ?: TodayHomeworkSource.LOCAL
    TodayCard(modifier, padding = PaddingValues(16.dp), onClick = { action(TodayAction.OpenHomework(source)) }) {
        SummaryLabel(R.drawable.ic_baseline_check_24, label)
        SummaryValue(model.count?.toString() ?: stringResource(R.string.today_count_unknown), stringResource(R.string.today_homework_unit))
        when {
            model.overdue > 0 -> SummaryText(stringResource(R.string.today_overdue_count, model.overdue), warning = true)
            model.pendingDate > 0 -> SummaryText(stringResource(R.string.today_date_pending_count, model.pendingDate))
            model.count == 0 -> SummaryText(stringResource(R.string.today_homework_empty))
            model.count != null -> SummaryText(stringResource(R.string.today_homework_due))
            else -> SummaryText(sourceStatus(model.sources.firstOrNull()?.status ?: TodaySourceStatus.LOADING))
        }
        val sourceText = if (model.sources.any { it.dueToday == null || it.status != TodaySourceStatus.READY }) {
            model.sources.filter { it.dueToday == null || it.status != TodaySourceStatus.READY }.map { stringResource(R.string.today_source_status,
                sourceName(it.source), sourceStatus(it.status)) }.joinToString(" · ") +
                if (model.retained) " · " + stringResource(R.string.today_retained) else ""
        } else if (model.retained) stringResource(R.string.today_retained)
        else model.known.map { stringResource(R.string.today_source_count, sourceName(it.source), it.dueToday ?: 0) }.joinToString(" · ")
        SummaryText(sourceText, small = true)
        model.sources.filter { it.status == TodaySourceStatus.FAILED }.forEach { failed ->
            TextButton(onClick = { action(TodayAction.Retry(if (failed.source == TodayHomeworkSource.LOCAL)
                TodayRetrySource.HOMEWORK else TodayRetrySource.GRADER)) }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.today_retry), style = todayLabel())
            }
        }
    }
}

@Composable
private fun sourceName(source: TodayHomeworkSource): String = stringResource(
    if (source == TodayHomeworkSource.LOCAL) R.string.today_homework_local_short else R.string.today_homework_grader_short)

@Composable
private fun sourceStatus(status: TodaySourceStatus): String = stringResource(when (status) {
    TodaySourceStatus.LOADING -> R.string.today_loading
    TodaySourceStatus.FAILED -> R.string.today_read_failed
    TodaySourceStatus.DISCONNECTED -> R.string.today_disconnected
    else -> R.string.today_unknown
})

@Composable
private fun SummaryLabel(icon: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = todayLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryValue(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Text(unit, style = todayLabel(), modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun SummaryText(value: String, small: Boolean = false, warning: Boolean = false) {
    if (value.isNotBlank()) Text(value, style = if (small) todayCaption() else todayLabel(),
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
