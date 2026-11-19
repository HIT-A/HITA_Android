package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
        val title = examSubject(entry.title).ifBlank { stringResource(R.string.today_exam_untitled) }
        listOfNotNull(time, title, entry.place?.takeIf { it.isNotBlank() }).joinToString(" ")
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

/** 课表里的考试事件常带「[考试]」前缀，卡片只显示科目名。 */
private val examTitlePrefix = Regex("""^\s*[\[【]考试[\]】]\s*""")

internal fun examSubject(raw: String): String = raw.replace(examTitlePrefix, "").trim()

@Composable
private fun homeworkCard(state: TodayUiState): SummaryCardModel {
    val model = state.homework?.let(::todayHomeworkCard)
    val label = if (model?.known?.size == 1) sourceName(model.singleSource ?: TodayHomeworkSource.LOCAL)
        else stringResource(R.string.today_homework_card)
    val action = TodayAction.OpenHomework(model?.singleSource ?: TodayHomeworkSource.LOCAL)
    val base = SummaryCardModel(label, R.drawable.ic_baseline_check_24, action, emptyList(),
        stringResource(R.string.today_homework_none))
    if (model == null) return base.copy(lines = listOf(stringResource(
        if (state.status == TodayPageStatus.FAILED) R.string.today_read_failed else R.string.today_loading)))
    if (model.sources.any { it.status == TodaySourceStatus.FAILED }) {
        return base.copy(lines = listOf(stringResource(R.string.today_read_failed)))
    }
    // 与待办页的「Grader 待完成」保持一致：只看未过期的作业，按截止时间从近到远，最多三条。
    val items = model.items.filter { it.dueAt >= state.now }.take(3)
    return base.copy(lines = items.mapNotNull { item ->
        val title = item.title.ifBlank { item.course }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // 标题后面补科目，两个来源的字段含义一致。
        listOfNotNull(title, item.course.takeIf { it.isNotBlank() && it != title }).joinToString(" ")
    })
}

@Composable
private fun SummaryCard(model: SummaryCardModel, modifier: Modifier, compact: Boolean, action: (TodayAction) -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // 标题压深一档、正文加粗一级：白卡上用灰色正文，靠字重而不是颜色区分主次。
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    val lineColor = if (model.warning) MaterialTheme.colorScheme.error else secondary
    // 做实的白卡 + 细描边 + 轻阴影：两张摘要卡要像独立可点的卡片，不像分组的列表行。
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    val onClick: () -> Unit = { action(model.action) }
    if (compact) {
        TodayCard(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 4.dp,
            onClick = onClick, elevation = 10.dp, opaque = true, border = cardBorder) {
            SummaryCardTitle(model, secondary, titleColor)
            Text(model.lines.firstOrNull() ?: model.empty, style = todayLabel(), fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (model.lines.isEmpty()) secondary else lineColor)
        }
        return
    }
    TodayCard(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), spacing = 4.dp,
        onClick = onClick, elevation = 10.dp, opaque = true, border = cardBorder) {
        SummaryCardTitle(model, secondary, titleColor)
        if (model.lines.isEmpty()) {
            Text(model.empty, style = todayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = secondary)
        } else {
            model.lines.forEach { line ->
                Text(line, style = todayLabel(), fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = lineColor)
            }
        }
    }
}

/** 标题行：图标 + 标题 + 右端箭头，箭头暗示整张卡可以点进去。 */
@Composable
private fun SummaryCardTitle(model: SummaryCardModel, iconTint: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(model.icon), null, Modifier.size(15.dp), tint = iconTint)
        Text(model.label, style = todayLabel(), fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = textColor, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), null, Modifier.size(16.dp),
            tint = iconTint.copy(alpha = 0.72f))
    }
}

@Composable
private fun sourceName(source: TodayHomeworkSource): String = stringResource(
    if (source == TodayHomeworkSource.LOCAL) R.string.today_homework_local_short else R.string.today_homework_grader_short)
