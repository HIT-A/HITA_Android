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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import java.time.Instant
import java.time.format.DateTimeFormatter

private data class SummaryPresentation(
    val label: String,
    val value: String,
    val unit: String,
    val detail: String,
    val icon: Int,
    val action: TodayAction,
    val warning: Boolean = false,
)

@Composable
internal fun TodaySummaryCards(state: TodayUiState, action: (TodayAction) -> Unit, fixed: Boolean = false, compact: Boolean = false) {
    val exam = if (state.status != TodayPageStatus.NO_TARGET) examPresentation(state) else null
    val homework = homeworkPresentation(state)
    val cards = listOfNotNull(exam, homework)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (!fixed && (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.25f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { SummaryCard(it, Modifier, false, false, action) }
            }
        } else Row(if (fixed) Modifier.height((if (compact) 28f + 40f * LocalDensity.current.fontScale else 56f + 82f * LocalDensity.current.fontScale).dp)
            else Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.forEach { SummaryCard(it, Modifier.weight(1f).fillMaxHeight(), fixed, compact, action) }
        }
    }
}

@Composable
private fun examPresentation(state: TodayUiState): SummaryPresentation {
    val exams = state.exams
    val placeholder = state.agenda == null || state.status == TodayPageStatus.LOADING || exams == null
    val failed = state.status == TodayPageStatus.FAILED
    if (placeholder) return SummaryPresentation(stringResource(R.string.today_exam_card), stringResource(R.string.today_count_unknown),
        stringResource(R.string.today_exam_unit), stringResource(if (failed) R.string.today_read_failed else R.string.today_loading),
        R.drawable.ic_baseline_edit_24, TodayAction.OpenExams)
    val summary = requireNotNull(exams)
    val nearest = summary.remaining.firstOrNull()
    val remote = (summary.remaining + summary.ended).any { it.source == TodayExamSource.EAS_QUERY } || summary.timeToConfirm.isNotEmpty()
    // 卡片只回答「哪门、什么时候、在哪」；场次计数与来源说明不再占第三行。
    val detail = when {
        summary.timeToConfirm.isNotEmpty() -> stringResource(R.string.today_exam_time_pending, summary.timeToConfirm.size)
        nearest != null -> listOfNotNull(
            nearest.title.takeIf { it.isNotBlank() },
            nearest.start?.let { examTime(state, it) } ?: stringResource(R.string.today_invalid),
            nearest.place?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        summary.ended.isNotEmpty() -> stringResource(R.string.today_exam_ended)
        else -> stringResource(R.string.today_exam_empty)
    }
    return SummaryPresentation(stringResource(if (remote) R.string.today_exam_card_mixed else R.string.today_exam_card),
        summary.remaining.size.toString(), stringResource(R.string.today_exam_unit), detail,
        R.drawable.ic_baseline_edit_24, TodayAction.OpenExams)
}

@Composable
private fun examTime(state: TodayUiState, start: Long): String {
    val at = Instant.ofEpochMilli(start).atZone(state.window.zoneId)
    return at.format(DateTimeFormatter.ofPattern(if (at.toLocalDate() == state.window.date) "HH:mm" else "MM-dd HH:mm"))
}

@Composable
private fun homeworkPresentation(state: TodayUiState): SummaryPresentation {
    val model = state.homework?.let(::todayHomeworkCard)
    val label = if (model?.known?.size == 1) sourceName(model.singleSource ?: TodayHomeworkSource.LOCAL)
        else stringResource(R.string.today_homework_card)
    val detail = when {
        model == null -> stringResource(if (state.status == TodayPageStatus.FAILED) R.string.today_read_failed else R.string.today_loading)
        model.sources.any { it.status == TodaySourceStatus.FAILED } -> stringResource(R.string.today_read_failed)
        model.overdue > 0 -> stringResource(R.string.today_overdue_count, model.overdue)
        model.pendingDate > 0 -> stringResource(R.string.today_date_pending_count, model.pendingDate)
        model.count == 0 -> stringResource(R.string.today_homework_empty)
        model.count != null -> stringResource(R.string.today_homework_due)
        else -> sourceStatus(model.sources.firstOrNull()?.status ?: TodaySourceStatus.LOADING)
    }
    return SummaryPresentation(label, model?.count?.toString() ?: stringResource(R.string.today_count_unknown),
        stringResource(R.string.today_homework_unit), detail, R.drawable.ic_baseline_check_24,
        TodayAction.OpenHomework(model?.singleSource ?: TodayHomeworkSource.LOCAL), model != null && model.overdue > 0)
}

@Composable
private fun SummaryCard(model: SummaryPresentation, modifier: Modifier, fixed: Boolean, compact: Boolean, action: (TodayAction) -> Unit) {
    if (compact) {
        TodayCard(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 4.dp,
            onClick = { action(model.action) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.label, style = todayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(model.value, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(model.detail, style = todayCaption(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val lines = if (fixed) 1 else Int.MAX_VALUE
    TodayCard(modifier, padding = PaddingValues(16.dp), onClick = { action(model.action) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(model.icon), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(model.label, style = todayLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.value, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(model.unit, style = todayLabel(), modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
        }
        // 科目+时间+地点一行放不下时允许折两行，摘要卡高度足够。
        Text(model.detail, style = todayLabel(), maxLines = if (fixed) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            color = if (model.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
