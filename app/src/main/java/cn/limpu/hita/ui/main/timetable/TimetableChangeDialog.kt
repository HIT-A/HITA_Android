package cn.limpu.hita.ui.main.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.isOccurrenceChange
import cn.limpu.hita.data.repository.CourseChange
import cn.limpu.hita.data.repository.CourseVetoReason
import cn.limpu.hita.data.repository.KeptCourse
import cn.limpu.hita.data.repository.LessonSlotChange
import cn.limpu.hita.data.repository.PendingChangeKind
import cn.limpu.hita.data.repository.PendingChangeRow
import cn.limpu.hita.data.repository.SlotChangeKind
import cn.limpu.hita.data.repository.TimetableChangeState
import cn.limpu.hita.data.repository.TimetableDecisionItem
import cn.limpu.hita.data.repository.TimetableHeldBatch
import cn.limpu.hita.ui.design.HitaTheme

/**
 * 课表变更详情弹窗。
 *
 * 分区展示一次刷新带来的全部变化，并承载需要用户决策的事项：
 * - 待采纳：时间/地点/教师调整与新增课，多选勾选后一次性采纳，忽略项可记住指纹；
 * - 已保留：源端未返回或课次减少、被本地缓存保住的课；
 * - 待确认：持续缺失（3 次且 48 小时）的课，逐门"采用课表源 / 继续保留"；
 * - 课表源数据异常：源端与本地匹配率过低时整批挂起。
 */
@Composable
internal fun TimetableChangeDialog(
    state: TimetableChangeState,
    onDismiss: () -> Unit,
    onAdoptCourse: (TimetableDecisionItem) -> Unit,
    onDismissCourse: (TimetableDecisionItem) -> Unit,
    onAdoptBatch: (TimetableHeldBatch) -> Unit,
    onDismissBatch: (TimetableHeldBatch) -> Unit,
    onConfirmPending: (adopted: Set<String>, remember: Set<String>) -> Unit = { _, _ -> },
) {
    if (state.pendingUpdates.any { update -> update.rows.any { it.isOccurrenceChange() } }) {
        SourceOccurrenceChangesDialog(state.pendingUpdates, onDismiss, onConfirmPending)
        return
    }
    val pendingRows = remember(state.pendingUpdates) { state.pendingUpdates.flatMap { it.rows } }
    val adopted = remember(pendingRows) {
        mutableStateMapOf<String, Boolean>().apply {
            pendingRows.forEach { put(it.fingerprint, true) }
        }
    }
    val rememberIgnore = remember(pendingRows) {
        mutableStateMapOf<String, Boolean>().apply {
            pendingRows.forEach { put(it.fingerprint, false) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.timetable_change_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.md)
            ) {
                if (pendingRows.isNotEmpty()) {
                    SectionHeader(
                        stringResource(R.string.timetable_change_section_confirm, pendingRows.size)
                    )
                    state.pendingUpdates.forEach { update ->
                        CourseNameText(update.name)
                        update.rows.forEach { row ->
                            PendingChangeRowItem(
                                row = row,
                                adopted = adopted[row.fingerprint] == true,
                                rememberIgnore = rememberIgnore[row.fingerprint] == true,
                                onAdoptChange = { adopted[row.fingerprint] = it },
                                onRememberChange = { rememberIgnore[row.fingerprint] = it },
                            )
                        }
                    }
                }
                val info = state.info
                if (info != null && info.keptCourses.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.timetable_change_section_kept, info.keptCourses.size))
                    info.keptCourses.forEach { kept -> KeptCourseRow(kept) }
                }
                if (state.decisions.isNotEmpty()) {
                    SectionHeader(
                        stringResource(R.string.timetable_change_section_decisions, state.decisions.size)
                    )
                    state.decisions.forEach { item ->
                        DecisionCourseRow(
                            item = item,
                            onAdopt = { onAdoptCourse(item) },
                            onKeep = { onDismissCourse(item) },
                        )
                    }
                }
                val batch = state.heldBatch
                if (batch != null) {
                    HeldBatchRow(
                        batch = batch,
                        onAdopt = { onAdoptBatch(batch) },
                        onKeep = { onDismissBatch(batch) },
                    )
                }
                if (pendingRows.isEmpty() &&
                    state.info == null &&
                    state.decisions.isEmpty() &&
                    state.heldBatch == null
                ) {
                    Text(
                        text = stringResource(R.string.timetable_change_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (pendingRows.isNotEmpty()) {
                TextButton(
                    onClick = {
                        val adoptedIds = pendingRows.mapNotNull { row ->
                            row.fingerprint.takeIf { adopted[it] == true }
                        }.toSet()
                        val rememberIds = pendingRows.mapNotNull { row ->
                            row.fingerprint.takeIf {
                                adopted[it] != true && rememberIgnore[it] == true
                            }
                        }.toSet()
                        onConfirmPending(adoptedIds, rememberIds)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.timetable_change_apply_selected))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.timetable_change_close))
                }
            }
        },
        dismissButton = if (pendingRows.isNotEmpty()) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.timetable_change_close))
                }
            }
        } else {
            null
        }
    )
}

@Composable
private fun PendingChangeRowItem(
    row: PendingChangeRow,
    adopted: Boolean,
    rememberIgnore: Boolean,
    onAdoptChange: (Boolean) -> Unit,
    onRememberChange: (Boolean) -> Unit,
) {
    val weekdayNames = stringArrayResource(R.array.dow2)
    val noneValue = stringResource(R.string.timetable_change_value_none)
    val field = when (row.kind) {
        PendingChangeKind.LESSON_MOVED -> stringResource(R.string.timetable_diff_moved)
        PendingChangeKind.LESSON_ADDED -> stringResource(R.string.timetable_diff_added)
        PendingChangeKind.LESSON_REMOVED -> stringResource(R.string.timetable_diff_removed)
        PendingChangeKind.LESSON_DETAILS -> stringResource(R.string.timetable_diff_details)
        PendingChangeKind.PLACE -> stringResource(R.string.timetable_change_field_place)
        PendingChangeKind.TEACHER -> stringResource(R.string.timetable_change_field_teacher)
        PendingChangeKind.TIME -> stringResource(R.string.timetable_change_field_time)
        PendingChangeKind.WEEKS -> stringResource(R.string.timetable_change_field_weeks)
        PendingChangeKind.SLOT_ADDED -> stringResource(R.string.timetable_change_slot_added)
        PendingChangeKind.SLOT_REMOVED -> stringResource(R.string.timetable_change_slot_removed)
        PendingChangeKind.ADDED_COURSE -> stringResource(R.string.timetable_change_added_course)
        PendingChangeKind.RENAME -> stringResource(R.string.timetable_change_renamed_from, row.before)
    }
    val weekday = weekdayNames.getOrNull(row.dow - 1).orEmpty()
    val period = when {
        row.fromNumber <= 0 -> ""
        row.lastNumber <= 1 -> stringResource(R.string.timetable_change_period_single, row.fromNumber)
        else -> stringResource(
            R.string.timetable_change_period_range,
            row.fromNumber,
            row.fromNumber + row.lastNumber - 1,
        )
    }
    val slotLabel = listOf(weekday, period).filter { it.isNotEmpty() }.joinToString(" ")
    val changeText = when (row.kind) {
        PendingChangeKind.ADDED_COURSE, PendingChangeKind.SLOT_ADDED ->
            listOf(slotLabel, row.after).filter { it.isNotEmpty() }.joinToString(" · ")
        PendingChangeKind.SLOT_REMOVED ->
            listOf(slotLabel, row.before).filter { it.isNotEmpty() }.joinToString(" · ")
        PendingChangeKind.RENAME -> field
        else -> {
            val body = stringResource(
                R.string.timetable_change_field_change,
                field,
                row.before.ifEmpty { noneValue },
                row.after.ifEmpty { noneValue },
            )
            if (slotLabel.isEmpty()) body else "$slotLabel $body"
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = adopted, onCheckedChange = onAdoptChange)
            Text(
                text = changeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (!adopted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HitaTheme.tokens.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = rememberIgnore, onCheckedChange = onRememberChange)
                Text(
                    text = stringResource(R.string.timetable_change_remember_ignore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun CourseNameText(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun DetailText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun UpdatedCourseRow(change: CourseChange) {
    Column {
        CourseNameText(change.name)
        if (change.previousName != null) {
            DetailText(stringResource(R.string.timetable_change_renamed_from, change.previousName))
        }
        change.slotChanges.forEach { slot -> SlotChangeRow(slot) }
        if (change.lessonCountDelta != 0) {
            DetailText(stringResource(R.string.timetable_change_lessons_delta, change.lessonCountDelta))
        }
    }
}

/** 单个「周几 + 节次」槽位的完整变化：字段级 原值 → 新值，或整条课次的新增/减少。 */
@Composable
private fun SlotChangeRow(slot: LessonSlotChange) {
    val weekdayNames = stringArrayResource(R.array.dow2)
    val noneValue = stringResource(R.string.timetable_change_value_none)
    val periodLabel = slot.periodLabel()
    val slotLabel = remember(slot, weekdayNames, periodLabel) {
        val weekday = weekdayNames.getOrNull(slot.dow - 1) ?: ""
        listOf(weekday, periodLabel).filter { it.isNotEmpty() }.joinToString(" ")
    }
    when (slot.kind) {
        SlotChangeKind.ADDED -> {
            val suffix = listOf(slot.place, slot.teacher).filter { it.isNotEmpty() }
            DetailText(
                stringResource(R.string.timetable_change_slot_added) + " · " +
                    listOf(slotLabel, slot.clock, slot.weeksLabel(), *suffix.toTypedArray())
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
            )
        }
        SlotChangeKind.REMOVED -> {
            DetailText(
                stringResource(R.string.timetable_change_slot_removed) + " · " +
                    listOf(slotLabel, slot.clock, slot.weeksLabel())
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
            )
        }
        SlotChangeKind.ADJUSTED -> {
            val fieldTime = stringResource(R.string.timetable_change_field_time)
            val fieldWeeks = stringResource(R.string.timetable_change_field_weeks)
            val fieldPlace = stringResource(R.string.timetable_change_field_place)
            val fieldTeacher = stringResource(R.string.timetable_change_field_teacher)
            val fieldLessons = stringResource(R.string.timetable_change_field_lessons)
            val changes = listOfNotNull(
                slot.clockBefore?.let {
                    stringResource(R.string.timetable_change_field_change, fieldTime, it, slot.clock)
                },
                slot.weeksBefore?.let {
                    stringResource(
                        R.string.timetable_change_field_change,
                        fieldWeeks,
                        weeksLabel(it).ifEmpty { noneValue },
                        weeksLabel(slot.weeks).ifEmpty { noneValue }
                    )
                },
                slot.placeBefore?.let {
                    stringResource(
                        R.string.timetable_change_field_change,
                        fieldPlace,
                        it.ifEmpty { noneValue },
                        slot.place.ifEmpty { noneValue }
                    )
                },
                slot.teacherBefore?.let {
                    stringResource(
                        R.string.timetable_change_field_change,
                        fieldTeacher,
                        it.ifEmpty { noneValue },
                        slot.teacher.ifEmpty { noneValue }
                    )
                },
                slot.lessonCountBefore?.let {
                    stringResource(
                        R.string.timetable_change_field_change,
                        fieldLessons,
                        it.toString(),
                        slot.lessonCount.toString()
                    )
                }
            )
            changes.forEach { change -> DetailText("$slotLabel $change") }
        }
    }
}

@Composable
private fun LessonSlotChange.periodLabel(): String = when {
    fromNumber <= 0 -> ""
    fromNumber == lastNumber -> stringResource(R.string.timetable_change_period_single, fromNumber)
    else -> stringResource(R.string.timetable_change_period_range, fromNumber, lastNumber)
}

@Composable
private fun LessonSlotChange.weeksLabel(): String =
    if (weeks.isEmpty()) "" else stringResource(R.string.timetable_change_weeks_bracket, weeks)

@Composable
private fun weeksLabel(weeks: String): String =
    if (weeks.isEmpty()) "" else stringResource(R.string.timetable_change_weeks_plain, weeks)

@Composable
private fun KeptCourseRow(kept: KeptCourse) {
    Column {
        CourseNameText(kept.name)
        val detail = when (kept.reason) {
            CourseVetoReason.MISSING_FROM_SOURCE ->
                stringResource(R.string.timetable_change_kept_missing)
            CourseVetoReason.LESSON_COUNT_REDUCED ->
                stringResource(
                    R.string.timetable_change_kept_reduced_counts,
                    kept.localLessonCount,
                    kept.incomingLessonCount
                )
        }
        DetailText(detail)
    }
}

@Composable
private fun DecisionCourseRow(
    item: TimetableDecisionItem,
    onAdopt: () -> Unit,
    onKeep: () -> Unit,
) {
    val days = remember(item.firstSeenMillis) {
        ((System.currentTimeMillis() - item.firstSeenMillis) / (24L * 60 * 60 * 1000)).toInt()
    }
    val detail = when (item.reason) {
        CourseVetoReason.MISSING_FROM_SOURCE ->
            stringResource(R.string.timetable_change_decision_missing, days)
        CourseVetoReason.LESSON_COUNT_REDUCED ->
            stringResource(
                R.string.timetable_change_decision_reduced,
                item.localLessonCount,
                item.incoming?.lessonCount ?: 0
            )
    }
    Column {
        CourseNameText(item.name)
        DetailText(detail)
        DecisionButtons(onAdopt = onAdopt, onKeep = onKeep)
    }
}

@Composable
private fun HeldBatchRow(
    batch: TimetableHeldBatch,
    onAdopt: () -> Unit,
    onKeep: () -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.timetable_change_batch_title))
        DetailText(
            stringResource(
                R.string.timetable_change_batch_message,
                batch.localCount,
                batch.incomingCount,
                batch.matchedCount
            )
        )
        DecisionButtons(
            onAdopt = onAdopt,
            onKeep = onKeep,
            adoptLabel = stringResource(R.string.timetable_change_batch_adopt),
            keepLabel = stringResource(R.string.timetable_change_batch_keep)
        )
    }
}

@Composable
private fun DecisionButtons(
    onAdopt: () -> Unit,
    onKeep: () -> Unit,
    adoptLabel: String = stringResource(R.string.timetable_change_action_adopt),
    keepLabel: String = stringResource(R.string.timetable_change_action_keep),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.sm)
    ) {
        TextButton(onClick = onAdopt) { Text(adoptLabel) }
        TextButton(onClick = onKeep) { Text(keepLabel) }
    }
}
