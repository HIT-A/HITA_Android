package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R

/**
 * 滚动版式下列表之前的紧急提醒行（今天有考试 / 有待截止作业）。
 * 问候块已移除，固定版式不再占用首屏高度；没有提醒时整行不出现。
 */
@Composable
internal fun TodayHeader(state: TodayUiState) {
    val exams = state.exams?.remaining?.count {
        it.start != null && it.start < state.window.tomorrowStart && it.end != null && it.end > state.window.start
    } ?: 0
    val homework = state.homework?.let(::todayHomeworkCard)
    val urgent = when {
        state.agenda != null && exams > 0 -> stringResource(R.string.today_exam_urgent, exams)
        homework?.count != null && homework.count > 0 -> stringResource(R.string.today_homework_urgent, homework.count)
        else -> null
    } ?: return
    Text(urgent, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
        modifier = Modifier.fillMaxWidth())
}
