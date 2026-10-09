package cn.limpu.hita.ui.timetable.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.ShareSummary
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.ui.design.HitaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableShareScreen(
    form: ShareFormState, timetables: List<Timetable>, summary: ShareSummary?, loading: Boolean,
    errorResource: Int?, showSummaryRetry: Boolean, onRetrySummary: () -> Unit,
    onBack: () -> Unit, onSelect: (String) -> Unit,
    onNickname: (String) -> Unit, onMode: (ShareMode) -> Unit, onGenerate: () -> Unit,
    onDismiss: () -> Unit, onCopy: (String) -> Unit, onShare: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.timetable_share_action)) }, navigationIcon = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.timetable_share_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(HitaTheme.tokens.spacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = timetables.isNotEmpty()) {
                    Text(timetables.firstOrNull { it.id == form.timetableId }?.name
                        ?: stringResource(R.string.timetable_share_select))
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    timetables.forEach { timetable ->
                        DropdownMenuItem(text = { Text(timetable.name.orEmpty()) }, onClick = {
                            expanded = false
                            onSelect(timetable.id)
                        })
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (timetables.isEmpty()) Text(stringResource(R.string.timetable_share_no_timetable))
            summary?.takeIf { it.mode == form.mode }?.let {
                Text(it.name, style = MaterialTheme.typography.titleLarge)
                Text(it.termName)
                if (it.mode == ShareMode.BUSY) {
                    Text(stringResource(R.string.timetable_share_busy_counts, it.occurrenceCount))
                } else {
                    Text(stringResource(R.string.timetable_share_counts, it.courseCount, it.occurrenceCount))
                }
                if (it.occurrenceCount == 0) Text(stringResource(R.string.timetable_share_empty_schedule))
            }
            OutlinedTextField(value = form.nickname, onValueChange = onNickname,
                label = { Text(stringResource(R.string.timetable_share_nickname)) },
                singleLine = true, isError = !form.nicknameValid, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.timetable_share_nickname_hint)) })
            ShareMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().clickable { onMode(mode) }) {
                    RadioButton(selected = form.mode == mode, onClick = { onMode(mode) })
                    Text(stringResource(if (mode == ShareMode.FULL) R.string.timetable_share_full else R.string.timetable_share_busy),
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
            Text(stringResource(R.string.timetable_share_busy_hint), style = MaterialTheme.typography.bodySmall)
            errorResource?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (showSummaryRetry) {
                OutlinedButton(onClick = onRetrySummary, enabled = !loading) {
                    Text(stringResource(R.string.timetable_share_retry))
                }
            }
            Button(onClick = onGenerate, enabled = !form.generating && !loading && form.nicknameValid &&
                (summary?.occurrenceCount ?: 0) > 0) {
                Text(stringResource(if (form.generating) R.string.timetable_share_generating else R.string.timetable_share_generate))
            }
        }
    }
    form.token?.let { token ->
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.timetable_share_token_title)) },
            text = { SelectionContainer {
                Text(token, modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
            } },
            confirmButton = { TextButton(onClick = { onCopy(token) }) { Text(stringResource(R.string.timetable_share_copy)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onShare(token) }) { Text(stringResource(R.string.timetable_share_system)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.timetable_share_close)) }
                }
            })
    }
}
