package cn.limpu.hita.ui.timetable.detail

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R

@Composable
internal fun TimetableDeletionConfirmation(
    names: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    subjects: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (subjects) R.string.timetable_audit_delete_subjects else R.string.timetable_audit_delete_tables, names.size)) },
        text = { Text(
            stringResource(
                if (subjects) R.string.timetable_audit_delete_subjects_hint else R.string.timetable_audit_delete_tables_hint,
                names.joinToString("\n"),
            ),
            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        ) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.button_confirm), color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}
