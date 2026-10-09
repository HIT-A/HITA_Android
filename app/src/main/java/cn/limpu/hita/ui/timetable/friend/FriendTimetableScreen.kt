package cn.limpu.hita.ui.timetable.friend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendTimetableScreen(viewModel: FriendTimetableViewModel, onBack: () -> Unit) {
    val spacing = HitaTheme.tokens.spacing
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.friend_timetable_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.friend_timetable_back)) } },
                actions = { TextButton(onClick = viewModel::showImport) { Text(stringResource(R.string.friend_import_title)) } })
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (viewModel.listLoading) item { CircularProgressIndicator() }
            if (viewModel.listError) item {
                Text(stringResource(R.string.friend_list_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retryList) { Text(stringResource(R.string.blog_retry)) }
            }
            if (!viewModel.listLoading && !viewModel.listError && viewModel.friends.isEmpty()) item {
                Text(stringResource(R.string.friend_list_empty))
                Button(onClick = viewModel::showImport) { Text(stringResource(R.string.friend_import_title)) }
            }
            items(viewModel.friends, key = { it.shareId }) { row ->
                Card(onClick = { viewModel.open(row.shareId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.remark?.takeIf { it.isNotBlank() } ?: row.nickname, style = MaterialTheme.typography.titleMedium)
                        Text(row.termName)
                        Text(stringResource(R.string.friend_list_updated, friendDate(row.updatedAt)))
                        Row {
                            TextButton(onClick = { viewModel.showRename(row) }) { Text(stringResource(R.string.friend_remark_title)) }
                            TextButton(onClick = { viewModel.showDelete(row) }) { Text(stringResource(R.string.friend_delete_title)) }
                        }
                    }
                }
            }
        }
    }
    if (viewModel.importOpen) FriendImportDialog(viewModel.form, viewModel::editInput, viewModel::parse,
        viewModel::confirm, viewModel::dismissImport)
    viewModel.renameRow?.let {
        AlertDialog(onDismissRequest = viewModel::dismissRename,
            title = { Text(stringResource(R.string.friend_remark_title)) },
            text = {
                Column {
                    OutlinedTextField(value = viewModel.remark, onValueChange = viewModel::editRemark,
                        enabled = !viewModel.managing, isError = !friendRemarkValid(viewModel.remark),
                        label = { Text(stringResource(R.string.friend_remark_hint)) },
                        supportingText = { Text(stringResource(R.string.friend_remark_limit)) })
                    if (viewModel.managementError) Text(stringResource(R.string.friend_management_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::rename, enabled = !viewModel.managing && friendRemarkValid(viewModel.remark)) {
                Text(stringResource(R.string.friend_remark_save))
            } },
            dismissButton = { TextButton(onClick = viewModel::dismissRename, enabled = !viewModel.managing) { Text(stringResource(R.string.friend_timetable_close)) } })
    }
    viewModel.deleteRow?.let { row ->
        AlertDialog(onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.friend_delete_title)) },
            text = { Column {
                Text(stringResource(R.string.friend_delete_notice, row.remark?.takeIf { it.isNotBlank() } ?: row.nickname))
                if (viewModel.managementError) Text(stringResource(R.string.friend_management_failed), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = viewModel::delete, enabled = !viewModel.managing) { Text(stringResource(R.string.friend_delete_title)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete, enabled = !viewModel.managing) { Text(stringResource(R.string.friend_timetable_close)) } })
    }
}
