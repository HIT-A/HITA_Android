package cn.limpu.hita.ui.timetable.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.DeletedEventStatus
import cn.limpu.hita.ui.design.HitaTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DeletionHistoryDialog(
    state: DeletionHistoryUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onRestore: (String) -> Unit,
) {
    val spacing = HitaTheme.tokens.spacing
    val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timetable_deletion_history)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.timetable_history_close)) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(stringResource(R.string.timetable_history_hint), style = MaterialTheme.typography.bodySmall)
                state.restoreSucceeded?.let { success ->
                    Text(
                        stringResource(if (success) R.string.timetable_history_restore_success
                            else R.string.timetable_history_restore_failed),
                        color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadFailed -> {
                        Text(stringResource(R.string.timetable_history_load_failed))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.timetable_history_retry)) }
                    }
                    state.entries.isEmpty() -> Text(stringResource(R.string.timetable_history_empty))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        items(state.entries, key = { it.id }) { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                                Text(entry.name ?: stringResource(R.string.timetable_history_unknown_course),
                                    style = MaterialTheme.typography.titleSmall)
                                entry.startsAt?.let {
                                    Text(stringResource(R.string.timetable_history_course_time, dates.format(Date(it))),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Text(stringResource(R.string.timetable_history_deleted_time, dates.format(Date(entry.deletedAt))),
                                    style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                    Text(
                                        stringResource(when (entry.status) {
                                            DeletedEventStatus.AVAILABLE -> R.string.timetable_history_available
                                            DeletedEventStatus.RESTORED -> R.string.timetable_history_restored
                                            DeletedEventStatus.CHANGED -> R.string.timetable_history_changed
                                            DeletedEventStatus.UNAVAILABLE -> R.string.timetable_history_unavailable
                                        }),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(
                                        onClick = { onRestore(entry.id) },
                                        enabled = entry.status == DeletedEventStatus.AVAILABLE && state.restoringId == null,
                                    ) {
                                        Text(stringResource(if (state.restoringId == entry.id)
                                            R.string.timetable_history_restoring else R.string.timetable_history_restore))
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
    )
}
