package cn.limpu.hita.ui.timetable.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.limpu.hita.R
import cn.limpu.hita.feature.timetable.version.*
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Date

@Composable
internal fun TimetableVersionsDialog(
    state: CourseVersionsUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCompare: (String) -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
    onSourceChanges: () -> Unit = {},
    onLegacy: () -> Unit = {},
) {
    var confirmRestore by remember(state.comparison?.version?.id) { mutableStateOf(false) }
    var showWeek by remember { mutableStateOf(false) }
    val comparison = state.comparison
    Dialog(onDismissRequest = { if (!state.restoring) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.timetable_versions_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    if (!state.restoring) TextButton(onClick = if (comparison == null) onDismiss else onBack) {
                        Text(stringResource(if (comparison == null) R.string.timetable_history_close else R.string.timetable_versions_back))
                    }
                }
                Text(stringResource(R.string.timetable_versions_scope), style = MaterialTheme.typography.bodySmall)
                if (state.failed) {
                    Text(stringResource(R.string.timetable_versions_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.timetable_history_retry)) }
                }
                if (state.restored) Text(stringResource(R.string.timetable_versions_restored), color = MaterialTheme.colorScheme.primary)
                if (state.loading || state.restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (comparison == null) {
                    Text(stringResource(R.string.timetable_versions_retention), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 12.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.versions, key = { it.id }) { version ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(stringResource(if (version.revision == state.versions.firstOrNull()?.revision)
                                        R.string.timetable_versions_current else R.string.timetable_versions_previous,
                                        version.revision), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(if (version.reason == "INITIAL") R.string.timetable_versions_recorded
                                        else R.string.timetable_versions_modified, formatTime(version.committedAt)))
                                    Text(stringResource(when (version.reason) {
                                        "RESTORE" -> R.string.timetable_versions_reason_restore
                                        "SOURCE" -> R.string.timetable_versions_reason_source
                                        "IMPORT" -> R.string.timetable_versions_reason_import
                                        "INITIAL" -> R.string.timetable_versions_reason_initial
                                        else -> R.string.timetable_versions_reason_manual
                                    }), style = MaterialTheme.typography.bodySmall)
                                    if (version.id != state.versions.firstOrNull()?.id) {
                                        TextButton(onClick = { onCompare(version.id) }, enabled = !state.loading && !state.restoring) {
                                            Text(stringResource(R.string.timetable_versions_compare))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = onSourceChanges) { Text(stringResource(R.string.timetable_versions_source_details)) }
                    if (state.hasLegacy) TextButton(onClick = onLegacy) { Text(stringResource(R.string.timetable_versions_legacy)) }
                } else {
                    Text(stringResource(R.string.timetable_versions_direction, comparison.version.revision,
                        comparison.currentRevision), modifier = Modifier.padding(top = 12.dp))
                    Text(versionDiffSummary(comparison.diff), style = MaterialTheme.typography.labelLarge)
                    if (comparison.configurationChanged) Text(stringResource(R.string.timetable_versions_configuration_changed),
                        style = MaterialTheme.typography.bodySmall)
                    if (comparison.metadataChanged) Text(stringResource(R.string.timetable_versions_metadata_changed),
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { showWeek = false }) { Text(stringResource(R.string.timetable_versions_list)) }
                        TextButton(onClick = { showWeek = true }) { Text(stringResource(R.string.timetable_versions_week)) }
                    }
                    if (showWeek) WeekDiffView(comparison.diff, Modifier.weight(1f))
                    else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(comparison.diff.changes) { change -> VersionChangeCard(change) }
                    }
                    Button(onClick = { confirmRestore = true }, enabled = !state.restoring && !state.loading &&
                        comparison.version.fingerprint != state.versions.firstOrNull()?.fingerprint,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.timetable_versions_restore)) }
                }
            }
        }
    }
    if (confirmRestore && comparison != null) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text(stringResource(R.string.timetable_versions_restore_confirm)) },
        text = { Text(stringResource(R.string.timetable_versions_restore_impact, versionDiffSummary(comparison.restoreDiff) +
            (if (comparison.configurationChanged) "；" + stringResource(R.string.timetable_versions_configuration_changed) else "") +
            (if (comparison.metadataChanged) "；" + stringResource(R.string.timetable_versions_metadata_changed) else ""))) },
        confirmButton = { TextButton(onClick = { confirmRestore = false; onRestore() }) {
            Text(stringResource(R.string.timetable_versions_restore)) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.button_cancel)) } },
    )
}

@Composable
internal fun versionDiffSummary(diff: VersionDiff): String {
    val parts = buildList {
        diff.count(VersionChangeKind.MOVED).takeIf { it > 0 }?.let { add(stringResource(R.string.timetable_diff_moved_count, it)) }
        diff.count(VersionChangeKind.REMOVED).takeIf { it > 0 }?.let { add(stringResource(R.string.timetable_diff_removed_count, it)) }
        diff.count(VersionChangeKind.ADDED).takeIf { it > 0 }?.let { add(stringResource(R.string.timetable_diff_added_count, it)) }
        diff.count(VersionChangeKind.DETAILS).takeIf { it > 0 }?.let { add(stringResource(R.string.timetable_diff_details_count, it)) }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.timetable_diff_empty) }
}

@Composable
internal fun VersionChangeCard(change: VersionChange) {
    val lesson = checkNotNull(change.after ?: change.before)
    val label = when (change.kind) {
        VersionChangeKind.MOVED -> stringResource(R.string.timetable_diff_moved)
        VersionChangeKind.ADDED -> stringResource(R.string.timetable_diff_added)
        VersionChangeKind.REMOVED -> stringResource(R.string.timetable_diff_removed)
        VersionChangeKind.DETAILS -> stringResource(R.string.timetable_diff_details)
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$label · ${lesson.name}", style = MaterialTheme.typography.titleSmall)
            change.before?.let { Text("− ${formatTime(it.start)} – ${formatClock(it.end)} ${it.place}", style = MaterialTheme.typography.bodySmall) }
            change.after?.let { Text("＋ ${formatTime(it.start)} – ${formatClock(it.end)} ${it.place}", style = MaterialTheme.typography.bodySmall) }
            if (change.before?.teacher != change.after?.teacher && change.kind == VersionChangeKind.DETAILS) {
                Text("${change.before?.teacher.orEmpty()} → ${change.after?.teacher.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatTime(millis: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
private fun formatClock(millis: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Read-only diff grid. Both ends of a move carry the same number across weeks. */
@Composable
private fun WeekDiffView(diff: VersionDiff, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    fun monday(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = diff.changes.flatMap { listOfNotNull(it.before, it.after) }.map { monday(it.start) }.distinct().sorted()
    var index by remember(diff) { mutableIntStateOf(0) }
    Column(modifier) {
        if (weeks.isEmpty()) { Text(stringResource(R.string.timetable_diff_empty)); return }
        val week = weeks[index.coerceIn(weeks.indices)]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { index-- }, enabled = index > 0) { Text("←") }
            Text(week.toString(), modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { index++ }, enabled = index < weeks.lastIndex) { Text("→") }
        }
        val entries = diff.changes.flatMapIndexed { i, change ->
            listOfNotNull(change.before?.let { Triple(i + 1, false, it) }, change.after?.let { Triple(i + 1, true, it) })
        }.filter { monday(it.third.start) == week }
        val firstHour = (entries.minOfOrNull { Instant.ofEpochMilli(it.third.start).atZone(zone).hour } ?: 8).coerceAtMost(8)
        val lastHour = (entries.maxOfOrNull { Instant.ofEpochMilli(it.third.end).atZone(zone).hour + 1 } ?: 20).coerceAtLeast(firstHour + 1)
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { day -> Text(week.plusDays(day.toLong()).dayOfMonth.toString(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)) {
            val cellWidth = maxWidth / 7
            val hourHeight = maxHeight / (lastHour - firstHour)
            repeat(7) { day -> Box(Modifier.offset(x = cellWidth * day).width(cellWidth).fillMaxHeight()
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) }
            entries.forEach { (number, added, lesson) ->
                val start = Instant.ofEpochMilli(lesson.start).atZone(zone)
                val duration = ((lesson.end - lesson.start) / 60000f).coerceAtLeast(15f)
                val top = (start.hour - firstHour) + start.minute / 60f
                val tint = if (added) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
                Box(Modifier.offset(x = cellWidth * (start.dayOfWeek.value - 1), y = hourHeight * top)
                    .width(cellWidth).height((hourHeight * (duration / 60f)).coerceAtLeast(28.dp))
                    .background(tint.copy(alpha = if (added) 0.9f else 0.45f), RoundedCornerShape(4.dp))
                    .border(if (added) 1.dp else 2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .padding(2.dp)) {
                    Text("${if (added) "+" else "−"}$number ${lesson.name}\n${formatClock(lesson.start)}", fontSize = 9.sp,
                        color = if (added) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}
