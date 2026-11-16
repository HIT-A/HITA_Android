package cn.limpu.hita.ui.subject

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.design.HitaTheme
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal enum class SubjectBatchEditScope { SELECTED, ALL, THIS, SLOT }

internal enum class SubjectBatchDeleteScope { SELECTED, ALL, THIS, SLOT }

internal fun EventItem.applyPeriodTime(
    timetable: Timetable,
    dow: Int,
    startPeriod: Int,
    endPeriod: Int,
): Boolean {
    if (from.time < timetable.startTime.time || from.time >= timetable.endTime.time) {
        return false
    }
    if (dow !in 1..7 || startPeriod < 1 || endPeriod < startPeriod) {
        return false
    }
    if (endPeriod > timetable.scheduleStructure.size) {
        return false
    }
    // Keep the occurrence's actual campus week; term validity need not start on Monday.
    val monday = Instant.ofEpochMilli(from.time).atZone(COURSE_CAMPUS_ZONE).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val midnight = monday.plusDays((dow - 1).toLong()).atStartOfDay(COURSE_CAMPUS_ZONE).toInstant().toEpochMilli()
    val times = listOf(midnight + timetable.scheduleStructure[startPeriod - 1].from.toMills(),
        midnight + timetable.scheduleStructure[endPeriod - 1].to.toMills())
    if (times[1] <= times[0]) {
        return false
    }
    from = Timestamp(times[0])
    to = Timestamp(times[1])
    fromNumber = startPeriod
    lastNumber = endPeriod - startPeriod + 1
    return true
}

private val COURSE_CAMPUS_ZONE = ZoneId.of("Asia/Shanghai")

internal fun EventItem.slotTriple(): Triple<Int, Int, Int> =
    Triple(Instant.ofEpochMilli(from.time).atZone(COURSE_CAMPUS_ZONE).dayOfWeek.value,
        fromNumber, fromNumber + lastNumber - 1)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubjectBatchEditDialog(
    scope: SubjectBatchEditScope,
    events: List<EventItem>,
    timetable: Timetable?,
    onDismiss: () -> Unit,
    onApply: (
        place: String?,
        teacher: String?,
        time: Triple<Int, Int, Int>?,
        slotTimes: Map<Triple<Int, Int, Int>, Triple<Int, Int, Int>>,
    ) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val sharedPlace = events.map { it.place.orEmpty() }.distinct().singleOrNull().orEmpty()
    val sharedTeacher = events.map { it.teacher.orEmpty() }.distinct().singleOrNull().orEmpty()
    var place by remember(scope, events.size) { mutableStateOf(sharedPlace) }
    var teacher by remember(scope, events.size) { mutableStateOf(sharedTeacher) }
    val allowTime = scope != SubjectBatchEditScope.ALL && timetable != null
    var changeTime by remember { mutableStateOf(false) }
    val sample = events.firstOrNull()
    val periodCount = timetable?.scheduleStructure?.size?.coerceAtLeast(1) ?: 1
    var dow by remember { mutableIntStateOf(sample?.slotTriple()?.first ?: 1) }
    var startPeriod by remember {
        mutableIntStateOf(sample?.fromNumber?.takeIf { it in 1..periodCount } ?: 1)
    }
    var endPeriod by remember {
        val inferred = sample?.let { it.fromNumber + it.lastNumber - 1 } ?: startPeriod
        mutableIntStateOf(inferred.coerceIn(1, periodCount))
    }
    val weekdays = stringArrayResource(R.array.dow2)

    // 整门课：一门课可能每周有多个不同时段，按「星期+节次跨度」分组，逐组决定是否改时间。
    val slotGroups = remember(events) {
        events.groupBy { it.slotTriple() }
            .toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))
    }
    var slotEditsEnabled by remember(events) { mutableStateOf(mapOf<Triple<Int, Int, Int>, Boolean>()) }
    var slotTargets by remember(events) { mutableStateOf(mapOf<Triple<Int, Int, Int>, Triple<Int, Int, Int>>()) }
    val allowSlotTimes = scope == SubjectBatchEditScope.ALL && timetable != null

    val timeInvalid = (changeTime && endPeriod < startPeriod) ||
        slotGroups.keys.any { key ->
            slotEditsEnabled[key] == true && (slotTargets[key] ?: key).let { it.third < it.second }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    when (scope) {
                        SubjectBatchEditScope.ALL -> R.string.subject_batch_edit_title_all
                        SubjectBatchEditScope.THIS -> R.string.subject_batch_edit_title_this
                        SubjectBatchEditScope.SLOT -> R.string.subject_batch_edit_title_slot
                        SubjectBatchEditScope.SELECTED -> R.string.subject_batch_edit_title_selected
                    }
                ),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.subject_batch_keep_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_classroom)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_teacher)) },
                    singleLine = true,
                )
                if (allowTime) {
                    FilterChip(
                        selected = changeTime,
                        onClick = { changeTime = !changeTime },
                        label = { Text(stringResource(R.string.subject_batch_change_time)) },
                    )
                    if (changeTime) {
                        Text(
                            text = stringResource(R.string.subject_batch_weekday),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        WeekdayChipRow(weekdays = weekdays, selected = dow, onSelect = { dow = it })
                        Text(
                            text = stringResource(R.string.subject_batch_period_start),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = tokens.spacing.xs),
                        )
                        PeriodChipRow(
                            count = periodCount,
                            selected = startPeriod,
                            onSelect = { startPeriod = it },
                        )
                        Text(
                            text = stringResource(R.string.subject_batch_period_end),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        PeriodChipRow(
                            count = periodCount,
                            selected = endPeriod,
                            onSelect = { endPeriod = it },
                        )
                        if (changeTime && endPeriod < startPeriod) {
                            Text(
                                text = stringResource(R.string.subject_batch_time_invalid),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                            )
                        }
                    }
                } else if (allowSlotTimes) {
                    // 整门课：逐时段组修改时间；未开启的组保持原时间。
                    slotGroups.forEach { (slot, groupEvents) ->
                        val enabled = slotEditsEnabled[slot] == true
                        val target = slotTargets[slot] ?: slot
                        Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = slotTimeLabel(slot, groupEvents.size, weekdays),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                FilterChip(
                                    selected = enabled,
                                    onClick = { slotEditsEnabled = slotEditsEnabled + (slot to !enabled) },
                                    label = { Text(stringResource(R.string.subject_batch_change_time)) },
                                )
                            }
                            if (enabled) {
                                WeekdayChipRow(
                                    weekdays = weekdays,
                                    selected = target.first,
                                    onSelect = { slotTargets = slotTargets + (slot to target.copy(first = it)) },
                                )
                                Text(
                                    text = stringResource(R.string.subject_batch_period_start),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                PeriodChipRow(
                                    count = periodCount,
                                    selected = target.second,
                                    onSelect = { slotTargets = slotTargets + (slot to target.copy(second = it)) },
                                )
                                Text(
                                    text = stringResource(R.string.subject_batch_period_end),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                PeriodChipRow(
                                    count = periodCount,
                                    selected = target.third,
                                    onSelect = { slotTargets = slotTargets + (slot to target.copy(third = it)) },
                                )
                                if (target.third < target.second) {
                                    Text(
                                        text = stringResource(R.string.subject_batch_time_invalid),
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !timeInvalid,
                onClick = {
                    val nextPlace = place.trim().takeIf { it.isNotEmpty() }
                    val nextTeacher = teacher.trim().takeIf { it.isNotEmpty() }
                    val time = if (allowTime && changeTime) Triple(dow, startPeriod, endPeriod) else null
                    // 整门课：只提交「已开启且确实有改动」的时段组，未开启的组保持原时间。
                    val slotTimes = if (allowSlotTimes) {
                        slotGroups.keys.mapNotNull { key ->
                            val target = slotTargets[key] ?: key
                            if (slotEditsEnabled[key] == true && target != key) key to target else null
                        }.toMap()
                    } else {
                        emptyMap()
                    }
                    onApply(nextPlace, nextTeacher, time, slotTimes)
                },
            ) {
                Text(stringResource(R.string.subject_batch_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChipRow(
    weekdays: Array<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        weekdays.forEachIndexed { index, label ->
            val value = index + 1
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** 时段组标签：「周三 · 第3-4节 · 12次」；单节时显示「第3节」。 */
@Composable
private fun slotTimeLabel(slot: Triple<Int, Int, Int>, count: Int, weekdays: Array<String>): String {
    val weekday = weekdays.getOrElse(slot.first - 1) { slot.first.toString() }
    return if (slot.second == slot.third) {
        stringResource(R.string.subject_batch_slot_label_single, weekday, slot.second, count)
    } else {
        stringResource(R.string.subject_batch_slot_label_range, weekday, slot.second, slot.third, count)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodChipRow(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (period in 1..count) {
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(stringResource(R.string.period, period)) },
            )
        }
    }
}

@Composable
internal fun SubjectBatchDeleteDialog(
    scope: SubjectBatchDeleteScope,
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    hiding: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (hiding) R.string.teaching_hide_confirm else R.string.dialog_title_sure_delete), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = if (hiding) stringResource(R.string.teaching_hide_hint) else stringResource(
                    when (scope) {
                        SubjectBatchDeleteScope.ALL -> R.string.subject_batch_delete_all_message
                        SubjectBatchDeleteScope.THIS -> R.string.subject_batch_delete_this_message
                        SubjectBatchDeleteScope.SLOT -> R.string.subject_batch_delete_slot_message
                        SubjectBatchDeleteScope.SELECTED -> R.string.subject_batch_delete_selected_message
                    },
                    count,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.button_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        },
    )
}

internal fun applySubjectBatchEdit(
    events: List<EventItem>,
    timetable: Timetable?,
    place: String?,
    teacher: String?,
    time: Triple<Int, Int, Int>?,
    slotTimes: Map<Triple<Int, Int, Int>, Triple<Int, Int, Int>> = emptyMap(),
): Pair<List<EventItem>, Boolean> {
    var skippedTime = false
    val edited = events.map { event ->
        event.apply {
            place?.let { this.place = it }
            teacher?.let { this.teacher = it }
            if (timetable != null) {
                // 整门课的分组时间优先；否则用统一时间（THIS/SLOT/SELECTED）。
                val target = slotTimes[event.slotTriple()] ?: time
                if (target != null && !applyPeriodTime(timetable, target.first, target.second, target.third)) {
                    skippedTime = true
                }
            }
        }
    }
    return edited to skippedTime
}
