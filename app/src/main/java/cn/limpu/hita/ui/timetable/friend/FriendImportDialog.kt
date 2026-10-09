package cn.limpu.hita.ui.timetable.friend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.feature.timetableshare.protocol.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun friendDate(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(millis))

@Composable
fun FriendImportDialog(state: FriendImportUiState, onEdit: (String) -> Unit, onParse: () -> Unit,
                       onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friend_import_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = state.input, onValueChange = onEdit, enabled = !state.saving,
                    label = { Text(stringResource(R.string.friend_import_input)) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp), minLines = 3, maxLines = 5)
                TextButton(onClick = onParse, enabled = state.input.isNotBlank() && !state.loading && !state.saving) {
                    Text(stringResource(R.string.friend_import_parse))
                }
                if (state.loading || state.saving) CircularProgressIndicator(Modifier.size(24.dp))
                state.errorResource?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                state.preview?.let { preview ->
                    val snapshot = preview.snapshot
                    Text(snapshot.nickname, style = MaterialTheme.typography.titleMedium)
                    Text(snapshot.term.termName)
                    Text(stringResource(if (snapshot.mode == ShareMode.FULL) R.string.friend_import_full else R.string.friend_import_busy))
                    Text(stringResource(R.string.friend_timetable_interval, friendDate(snapshot.term.startMillis), friendDate(snapshot.term.endMillis)))
                    when (preview.kind) {
                        ImportKind.NEW -> Text(stringResource(R.string.friend_import_new))
                        ImportKind.SAME -> Text(stringResource(R.string.friend_import_same))
                        ImportKind.UPDATE -> {
                            Text(stringResource(R.string.friend_import_update_notice))
                            preview.existingRemark?.takeIf { it.isNotBlank() }?.let {
                                Text(stringResource(R.string.friend_import_existing_remark, it))
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(snapshot.occurrences) { occurrence ->
                            Column {
                                Text(if (snapshot.mode == ShareMode.BUSY) stringResource(R.string.friend_timetable_busy)
                                    else snapshot.courses[requireNotNull(occurrence.courseIndex)])
                                Text(stringResource(R.string.friend_timetable_interval, friendDate(occurrence.startMillis), friendDate(occurrence.endMillis)))
                                if (snapshot.mode == ShareMode.FULL) occurrence.place?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canConfirm) {
                Text(stringResource(when (state.preview?.kind) {
                    ImportKind.SAME -> R.string.friend_import_open
                    ImportKind.UPDATE -> R.string.friend_import_update
                    else -> R.string.friend_import_save
                }))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.friend_timetable_close)) } }
    )
}
