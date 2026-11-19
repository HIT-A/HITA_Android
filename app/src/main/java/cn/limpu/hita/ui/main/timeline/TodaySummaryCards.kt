package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import java.time.Instant
import java.time.format.DateTimeFormatter

private data class SummaryCardModel(
    val label: String,
    val icon: Int,
    val action: TodayAction,
    val lines: List<String>,
    val empty: String,
    val warning: Boolean = false,
)

@Composable
internal fun TodaySummaryCards(state: TodayUiState, action: (TodayAction) -> Unit, fixed: Boolean = false, compact: Boolean = false) {
    val exam = if (state.status != TodayPageStatus.NO_TARGET) examCard(state) else null
    val homework = homeworkCard(state)
    val cards = listOfNotNull(exam, homework)
    val fontScale = LocalDensity.current.fontScale
    // 两张卡上下排列各占整行，考试的时间与科目才有完整宽度。
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.forEach { model ->
            val height = if (fixed) Modifier.fillMaxWidth().height(TodayLayoutPolicy.summaryCardHeight(compact, fontScale).dp)
            else Modifier.fillMaxWidth()
            SummaryCard(model, height, compact, action)
        }
    }
}

@Composable
private fun examCard(state: TodayUiState): SummaryCardModel {
    val placeholder = state.agenda == null || state.status == TodayPageStatus.LOADING || state.exams == null
    val failed = state.status == TodayPageStatus.FAILED
    if (placeholder) return SummaryCardModel(
        stringResource(R.string.today_exam_card), R.drawable.ic_baseline_edit_24, TodayAction.OpenExams,
        listOf(stringResource(if (failed) R.string.today_read_failed else R.string.today_loading)), "",
    )
    val summary = requireNotNull(state.exams)
    val remote = (summary.remaining + summary.ended).any { it.source == TodayExamSource.EAS_QUERY } || summary.timeToConfirm.isNotEmpty()
    val upcoming = summary.remaining
        .mapNotNull { entry -> entry.start?.let { start -> entry to start } }
        .sortedBy { it.second }
        .map { it.first }
    val lines = (upcoming + summary.timeToConfirm).take(3).map { entry ->
        val time = entry.start?.let { examTime(state, it) } ?: stringResource(R.string.today_exam_time_unknown)
        val title = entry.title.ifBlank { stringResource(R.string.today_exam_untitled) }
        "$time $title"
    }
    return SummaryCardModel(
        stringResource(if (remote) R.string.today_exam_card_mixed else R.string.today_exam_card),
        R.drawable.ic_baseline_edit_24, TodayAction.OpenExams, lines, stringResource(R.string.today_exam_none),
    )
}

@Composable
private fun examTime(state: TodayUiState, start: Long): String {
    val at = Instant.ofEpochMilli(start).atZone(state.window.zoneId)
    return at.format(DateTimeFormatter.ofPattern(if (at.toLocalDate() == state.window.date) "HH:mm" else "MM-dd HH:mm"))
}

@Composable
private fun homeworkCard(state: TodayUiState): SummaryCardModel {
    val model = state.homework?.let(::todayHomeworkCard)
    val label = if (model?.known?.size == 1) sourceName(model.singleSource ?: TodayHomeworkSource.LOCAL)
        else stringResource(R.string.today_homework_card)
    val action = TodayAction.OpenHomework(model?.singleSource ?: TodayHomeworkSource.LOCAL)
    val base = SummaryCardModel(label, R.drawable.ic_baseline_check_24, action, emptyList(),
        stringResource(R.string.today_homework_none), model != null && model.overdue > 0)
    if (model == null) return base.copy(lines = listOf(stringResource(
        if (state.status == TodayPageStatus.FAILED) R.string.today_read_failed else R.string.today_loading)))
    if (model.sources.any { it.status == TodaySourceStatus.FAILED }) {
        return base.copy(lines = listOf(stringResource(R.string.today_read_failed)))
    }
    val items = model.items.filter { it.dueAt < state.window.tomorrowStart }.take(3)
    return base.copy(lines = items.mapNotNull { item ->
        item.title.ifBlank { item.course }.takeIf { it.isNotBlank() }
    })
}

@Composable
private fun SummaryCard(model: SummaryCardModel, modifier: Modifier, compact: Boolean, action: (TodayAction) -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = if (model.warning) MaterialTheme.colorScheme.error else secondary
    if (compact) {
        TodayCard(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 4.dp,
            onClick = { action(model.action) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(model.icon), null, Modifier.size(15.dp), tint = secondary)
                Text(model.label, style = todayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), color = secondary)
            }
            Text(model.lines.firstOrNull() ?: model.empty, style = todayCaption(), maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = lineColor)
        }
        return
    }
    TodayCard(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), spacing = 4.dp,
        onClick = { action(model.action) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(model.icon), null, Modifier.size(15.dp), tint = secondary)
            Text(model.label, style = todayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = secondary)
        }
        if (model.lines.isEmpty()) {
            Text(model.empty, style = todayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = secondary)
        } else {
            model.lines.forEach { line ->
                Text(line, style = todayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = lineColor)
            }
        }
    }
}

@Composable
private fun sourceName(source: TodayHomeworkSource): String = stringResource(
    if (source == TodayHomeworkSource.LOCAL) R.string.today_homework_local_short else R.string.today_homework_grader_short)
