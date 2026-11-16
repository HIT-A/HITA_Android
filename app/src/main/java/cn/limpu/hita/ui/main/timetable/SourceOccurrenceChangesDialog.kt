package cn.limpu.hita.ui.main.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SourceOccurrenceChangesDialog(
    updates: List<PendingCourseUpdate>, onDismiss: () -> Unit,
    onConfirm: (Set<String>, Set<String>) -> Unit,
) {
    val rows = updates.flatMap { it.rows }.filter { it.isOccurrenceChange() }
    val selected = remember(rows, updates) { mutableStateMapOf<String, Boolean>().apply {
        updates.forEach { update -> update.rows.forEach { put(it.fingerprint, !update.localChanged) } }
    } }
    val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    fun label(lesson: MergeLesson) = dates.format(Date(lesson.fromMillis)) + " – " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lesson.toMillis))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timetable_change_dialog_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (updates.any { it.localChanged }) item {
                    Text(stringResource(R.string.timetable_source_local_conflict), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                listOf(PendingChangeKind.LESSON_MOVED, PendingChangeKind.LESSON_REMOVED, PendingChangeKind.LESSON_ADDED,
                    PendingChangeKind.LESSON_DETAILS).forEach { kind ->
                    val group = rows.filter { it.kind == kind }
                    if (group.isNotEmpty()) {
                        item { Text(stringResource(when (kind) {
                            PendingChangeKind.LESSON_MOVED -> R.string.timetable_diff_moved_count
                            PendingChangeKind.LESSON_REMOVED -> R.string.timetable_diff_removed_count
                            PendingChangeKind.LESSON_ADDED -> R.string.timetable_diff_added_count
                            else -> R.string.timetable_diff_details_count
                        }, group.size), style = MaterialTheme.typography.titleSmall) }
                        items(group, key = { it.fingerprint }) { row ->
                            Row {
                                Checkbox(selected[row.fingerprint] == true, onCheckedChange = { selected[row.fingerprint] = it })
                                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                                    Text(when (kind) {
                                        PendingChangeKind.LESSON_MOVED -> "${row.beforeLesson?.let(::label).orEmpty()} → ${row.afterLesson?.let(::label).orEmpty()}"
                                        PendingChangeKind.LESSON_REMOVED -> stringResource(R.string.timetable_source_removed, row.beforeLesson?.let(::label).orEmpty())
                                        PendingChangeKind.LESSON_ADDED -> row.afterLesson?.let(::label).orEmpty()
                                        else -> "${row.beforeLesson?.place.orEmpty()} ${row.beforeLesson?.teacher.orEmpty()} → ${row.afterLesson?.place.orEmpty()} ${row.afterLesson?.teacher.orEmpty()}"
                                    }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.timetable_source_selection_hint), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val adopted = rows.filter { selected[it.fingerprint] == true }.map { it.fingerprint }.toSet()
            val ignored = rows.filter { selected[it.fingerprint] != true }.map { it.fingerprint }.toSet()
            onConfirm(adopted, ignored)
            onDismiss()
        }) { Text(stringResource(R.string.timetable_change_apply_selected)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timetable_history_close)) } },
    )
}

@Composable
internal fun sourceChangeSummary(rows: List<PendingChangeRow>): String = buildList {
    rows.count { it.kind == PendingChangeKind.LESSON_MOVED }.takeIf { it > 0 }?.let { add(stringResource(R.string.timetable_diff_moved_count, it)) }
    rows.count { it.kind == PendingChangeKind.LESSON_REMOVED }.takeIf { it > 0 }?.let { add(stringResource(R.string.timetable_diff_removed_count, it)) }
    rows.count { it.kind == PendingChangeKind.LESSON_ADDED }.takeIf { it > 0 }?.let { add(stringResource(R.string.timetable_diff_added_count, it)) }
}.joinToString(" · ")
