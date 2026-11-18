package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.ui.design.HitaTheme

@Composable
internal fun TodayHeader(state: TodayUiState, fixed: Boolean = false, compact: Boolean = false) {
    val header = state.header
    val preparing = state.agenda?.tomorrow != null && state.agenda.allToday.isNotEmpty()
    val title = when {
        preparing -> R.string.today_greeting_finished
        header.focus == TodayFocus.ACTIVE -> R.string.today_greeting_active
        header.count > 1 -> R.string.today_greeting_conflict
        else -> when (header.greeting) {
            TodayGreeting.EARLY -> R.string.today_greeting_early
            TodayGreeting.MORNING -> R.string.today_greeting_morning
            TodayGreeting.AFTERNOON -> R.string.today_greeting_afternoon
            TodayGreeting.EVENING -> R.string.today_greeting_evening
            TodayGreeting.LUNCH -> R.string.today_greeting_lunch
            TodayGreeting.DINNER -> R.string.today_greeting_dinner
        }
    }
    val minutes = header.remainingMinutes
    val duration = minutes?.let { remainingDuration(it) }
    val subtitle = when {
        state.status == TodayPageStatus.LOADING -> stringResource(R.string.today_loading)
        state.status == TodayPageStatus.NO_TARGET -> stringResource(R.string.today_no_target)
        state.status == TodayPageStatus.FAILED && state.agenda == null -> stringResource(R.string.today_read_failed)
        header.count > 1 -> stringResource(R.string.today_focus_group, header.count)
        header.focus == TodayFocus.ACTIVE && duration != null -> stringResource(R.string.today_active_duration, duration)
        header.focus == TodayFocus.UPCOMING && duration != null -> stringResource(when (header.type) {
            EventItem.TYPE.CLASS -> R.string.today_next_course_duration
            EventItem.TYPE.EXAM -> R.string.today_next_exam_duration
            else -> R.string.today_next_event_duration
        }, duration)
        header.focus == TodayFocus.FINISHED -> stringResource(R.string.today_finished)
        header.focus == TodayFocus.EMPTY -> stringResource(R.string.today_empty_subtitle)
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 12.dp else 24.dp, bottom = if (compact) 12.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        Text(stringResource(title), fontSize = if (compact) 28.sp else 34.sp, lineHeight = if (compact) 34.sp else 42.sp, fontWeight = FontWeight.Bold,
            fontFamily = HitaTheme.fonts.display, textAlign = TextAlign.Center, maxLines = 1)
        subtitle?.let { Text(it, fontSize = if (compact) 13.sp else 14.sp, lineHeight = if (compact) 18.sp else 20.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        val exams = state.exams?.remaining?.size ?: 0
        val homework = state.homework?.let(::todayHomeworkCard)
        val urgent = when {
            state.agenda != null && exams > 0 -> stringResource(R.string.today_exam_urgent, exams)
            homework?.count != null && homework.count > 0 -> stringResource(R.string.today_homework_urgent, homework.count)
            else -> null
        }
        urgent?.takeUnless { fixed }?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
    }
}

@Composable
private fun remainingDuration(minutes: Long): String = when {
    minutes < 60 -> stringResource(R.string.today_duration_minutes, minutes)
    minutes % 60 == 0L -> stringResource(R.string.today_duration_hours, minutes / 60)
    else -> stringResource(R.string.today_duration_hours_minutes, minutes / 60, minutes % 60)
}
