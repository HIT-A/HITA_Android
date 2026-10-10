package cn.limpu.hita.ui.main.timetable.compare

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.feature.timetablecompare.*
import java.time.Instant
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val actualFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private fun range(span: TimeSpan, actual: Boolean = false): String {
    val format = if (actual) actualFormat else clockFormat
    return format.format(Instant.ofEpochMilli(span.startMillis).atZone(CAMPUS_ZONE)) + "–" +
        format.format(Instant.ofEpochMilli(span.endMillis).atZone(CAMPUS_ZONE))
}
@Composable
fun ComparisonStatusBar(state: ComparisonUiState, outside: Boolean, onAdjust: () -> Unit,
    onExit: () -> Unit, onDetails: () -> Unit) {
    Surface(tonalElevation=2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)) {
            when(state) {
                is ComparisonUiState.Ready -> {
                    Text(stringResource(R.string.comparison_active_title,state.source.ownTimetable.name.orEmpty(),
                        state.source.friendRow.remark?.takeIf { it.isNotBlank() } ?: state.source.friendRow.nickname),style=MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.comparison_scope_hint),style=MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.comparison_legend),style=MaterialTheme.typography.labelSmall)
                    if (outside) Text(stringResource(R.string.comparison_outside_structure),style=MaterialTheme.typography.labelSmall)
                }
                is ComparisonUiState.Error -> Text(stringResource(state.messageResource))
                ComparisonUiState.Loading -> Text(stringResource(R.string.comparison_loading))
                ComparisonUiState.Off -> Unit
            }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick=onAdjust) { Text(stringResource(R.string.comparison_adjust)) }
                if (state is ComparisonUiState.Ready) TextButton(onClick=onDetails) { Text(stringResource(R.string.comparison_daily_details)) }
                TextButton(onClick=onExit) { Text(stringResource(R.string.comparison_exit)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonDetails(ready: ComparisonUiState.Ready, selected: TimeSpan?, onDismiss: () -> Unit) {
    var expanded by remember(ready,selected) { mutableStateOf(selected) }
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.comparison_daily_details),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.comparison_same_slot_explanation))
            Text(stringResource(R.string.comparison_common_no_class))
            // An overlay tap must reveal its chosen slot without scrolling past earlier days.
            selected?.let { span ->
                val day = ready.result.days.firstOrNull { it.slots.any { slot -> slot.span == span } }
                val slot = day?.slots?.firstOrNull { it.span == span }
                if (day != null && slot != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(day.date.toString(), style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.comparison_selected_slot,slot.index+1,range(slot.span)))
                    ComparisonSlotCourseDetails(ready,slot)
                    HorizontalDivider(Modifier.padding(vertical=12.dp))
                }
            }
            ready.result.days.forEach { day ->
                Spacer(Modifier.height(12.dp))
                Text(day.date.toString(),style=MaterialTheme.typography.titleMedium)
                val free=day.slots.filter { it.status==PeriodStatus.FREE }
                val conflicts=day.slots.filter { it.status==PeriodStatus.CONFLICT }
                Text(stringResource(R.string.comparison_day_totals, free.size,
                    free.sumOf { (it.span.endMillis-it.span.startMillis)/60000 }.toInt(), conflicts.size,
                    conflicts.sumOf { (it.span.endMillis-it.span.startMillis)/60000 }.toInt()))
                day.slots.forEach { slot ->
                    val status=stringResource(when(slot.status) {
                        PeriodStatus.FREE -> R.string.comparison_free_short
                        PeriodStatus.CONFLICT -> R.string.comparison_conflict_short
                        PeriodStatus.OWN_ONLY -> R.string.comparison_own_only
                        PeriodStatus.FRIEND_ONLY -> R.string.comparison_friend_only
                        PeriodStatus.UNKNOWN -> R.string.comparison_unknown
                    })
                    TextButton(onClick={expanded=if(expanded==slot.span) null else slot.span}) {
                        Text(stringResource(R.string.comparison_slot_row,slot.index+1,range(slot.span),status,
                            ((slot.span.endMillis-slot.span.startMillis)/60000).toInt()))
                    }
                    if (expanded==slot.span && slot.status!=PeriodStatus.UNKNOWN) {
                        ComparisonSlotCourseDetails(ready,slot)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Original record, campus times, and no edit/delete entry points. */
@Composable
fun ComparisonOwnCourseDetails(event: cn.limpu.hita.data.model.timetable.EventItem, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest=onDismiss, title={ Text(event.name) }, text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(range(TimeSpan(event.from.time,event.to.time),true))
            event.place?.takeIf { it.isNotBlank() }?.let { Text(it) }
            event.teacher?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text(stringResource(R.string.comparison_readonly_course))
        }
    }, confirmButton={ TextButton(onClick=onDismiss) { Text(stringResource(R.string.comparison_close_details)) } })
}

@Composable
private fun ComparisonSlotCourseDetails(ready: ComparisonUiState.Ready, slot: ComparedPeriod) {
    if (slot.status == PeriodStatus.UNKNOWN) {
        Text(stringResource(R.string.comparison_unknown))
        return
    }
    val details=comparisonSlotDetails(slot.span,ready.source.ownCourses,ready.source.friendSnapshot)
    Text(stringResource(R.string.comparison_own_details),style=MaterialTheme.typography.labelLarge)
    if(details.own.isEmpty()) Text(stringResource(R.string.comparison_no_class_in_slot))
    details.own.forEach { Text(stringResource(R.string.comparison_actual_course,it.name.orEmpty(),range(it.span,true),it.place.orEmpty())) }
    Text(stringResource(R.string.comparison_friend_details),style=MaterialTheme.typography.labelLarge)
    if(details.friend.isEmpty()) Text(stringResource(R.string.comparison_no_class_in_slot))
    details.friend.forEach {
        if(it.name==null) Text(stringResource(R.string.comparison_busy_label))
        else Text(stringResource(R.string.comparison_actual_course,it.name,range(it.span,true),it.place.orEmpty()))
    }
}
