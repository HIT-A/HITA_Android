package cn.limpu.hita.ui.main.timetable.compare

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonConfigSheet(timetables: List<ComparisonPersonalChoice>, friends: List<FriendTimetableEntity>,
    initial: ComparisonConfig?, onDismiss: () -> Unit, onConfirm: (ComparisonConfig) -> Unit,
    onManage: () -> Unit, onImport: () -> Unit, onFriends: () -> Unit) {
    var personalId by rememberSaveable { mutableStateOf(initial?.personalId.orEmpty()) }
    var friendId by rememberSaveable { mutableStateOf(initial?.friendId.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(comparisonTime(initial?.startMinute ?: 510)) }
    var end by rememberSaveable { mutableStateOf(comparisonTime(initial?.endMinute ?: 1350)) }
    var showFree by rememberSaveable { mutableStateOf(initial?.showFree ?: true) }
    var showConflicts by rememberSaveable { mutableStateOf(initial?.showConflicts ?: true) }
    val own = timetables.firstOrNull { it.id == personalId }
    val friend = friends.firstOrNull { it.shareId == friendId }
    // Capture value fields so a changed source disables confirmation immediately, before the effect runs.
    val sourceKey = listOf(own, friend)
    var candidate by remember { mutableStateOf<ComparisonCandidate?>(null) }
    var acceptedKey by remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(sourceKey) {
        candidate = null; acceptedKey = null
        if (own != null && friend != null) {
            candidate = readComparisonCandidate(own, friend)
            acceptedKey = sourceKey
        }
    }
    val current = candidate.takeIf { acceptedKey == sourceKey && own != null && friend != null }
    val startMinute = parseComparisonTime(start)
    val endMinute = parseComparisonTime(end)
    val config = if (startMinute != null && endMinute != null)
        ComparisonConfig(personalId, friendId, startMinute, endMinute, showFree, showConflicts) else null
    val problem = if (current != null && config != null) {
        if (!current.valid) ConfigProblem.INVALID_STRUCTURE
        else comparisonConfigError(config, current.ownPeriods, current.friendPeriods)
    } else null
    val canConfirm = current != null && config != null && problem == null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.comparison_config_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.comparison_config_explanation))
            ConfigSelector(stringResource(R.string.comparison_personal), own?.name.orEmpty(),
                timetables.map { it.id to it.name.orEmpty() }, onSelect = { personalId = it })
            if (timetables.isEmpty()) TextButton(onClick = onManage) { Text(stringResource(R.string.comparison_manage_personal)) }
            ConfigSelector(stringResource(R.string.comparison_friend), friend?.let { it.remark?.takeIf(String::isNotBlank) ?: it.nickname }.orEmpty(),
                friends.map { it.shareId to (it.remark?.takeIf(String::isNotBlank) ?: it.nickname) }, onSelect = { friendId = it })
            if (friends.isEmpty()) TextButton(onClick = onImport) { Text(stringResource(R.string.timetable_social_import)) }
            TextButton(onClick = onFriends) { Text(stringResource(R.string.comparison_view_friends)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.comparison_start_time)) },
                    singleLine = true, isError = startMinute == null, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.comparison_end_time)) },
                    singleLine = true, isError = endMinute == null, modifier = Modifier.weight(1f))
            }
            if (config == null) Text(stringResource(R.string.comparison_time_format), color = MaterialTheme.colorScheme.error)
            if (current != null) {
                Text(stringResource(R.string.comparison_own_structure, structureText(current.ownPeriods)))
                Text(stringResource(R.string.comparison_friend_structure, structureText(current.friendPeriods)))
            } else if (own != null && friend != null) Text(stringResource(R.string.loading))
            problem?.let {
                Text(stringResource(when (it) {
                    ConfigProblem.INVALID_RANGE -> R.string.comparison_invalid_range
                    ConfigProblem.INVALID_STRUCTURE -> R.string.comparison_invalid_structure
                    ConfigProblem.INCOMPATIBLE -> R.string.comparison_incompatible_periods
                    ConfigProblem.NO_COMPLETE_PERIOD -> R.string.comparison_no_complete_period
                }), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().toggleable(value = showFree, role = Role.Checkbox,
                onValueChange = { showFree = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showFree, onCheckedChange = null)
                Text(stringResource(R.string.comparison_show_free))
            }
            Row(Modifier.fillMaxWidth().toggleable(value = showConflicts, role = Role.Checkbox,
                onValueChange = { showConflicts = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showConflicts, onCheckedChange = null)
                Text(stringResource(R.string.comparison_show_conflicts))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
                Button(enabled = canConfirm, onClick = { config?.takeIf { canConfirm }?.let(onConfirm) }) { Text(stringResource(R.string.button_confirm)) }
            }
        }
    }
}

@Composable
private fun ConfigSelector(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty()) {
                Text(selected.ifBlank { stringResource(R.string.comparison_select_object) })
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}
private fun structureText(periods: List<cn.limpu.hita.feature.timetablecompare.ComparisonPeriod>): String =
    periods.joinToString("、") { "${comparisonTime(it.startMinute)}–${comparisonTime(it.endMinute)}" }.ifEmpty { "—" }
