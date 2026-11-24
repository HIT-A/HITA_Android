package cn.limpu.hita.ui.timetable.manager.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.timetable.detail.TimetableDeletionConfirmation
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.TimeTools
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TimetableGroupViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val sharingRepository: cn.limpu.hita.data.repository.TimetableSharingRepository,
    private val exportRepository: cn.limpu.hita.data.repository.TimetableExportRepository,
) : ViewModel() {
    val groupsLiveData = timetableRepository.getGroups()
    val timetablesLiveData: LiveData<List<Timetable>> = timetableRepository.getTimetables()

    /** 组成员各自导出一个口令（FULL）。 */
    suspend fun exportMemberTokens(timetableIds: List<String>, nickname: String, mode: ShareMode) =
        sharingRepository.tokensForMine(timetableIds, nickname, mode)

    /** 组成员各自导出一个 ICS 文件。 */
    suspend fun exportMemberIcs(timetableIds: List<String>) = exportRepository.mineIcs(timetableIds)

    fun renameTimetable(timetableId: String, name: String, onResult: (Boolean) -> Unit) {
        timetableRepository.actionRenameTimetable(timetableId, name, onResult)
    }

    fun renameGroup(groupId: String, name: String, onResult: (Boolean) -> Unit) {
        timetableRepository.actionRenameGroup(groupId, name, onResult)
    }

    /** 移出组合；组空后由 repository 自动删组。 */
    fun removeFromGroup(timetableId: String) {
        timetableRepository.actionRemoveFromGroup(timetableId)
    }

    fun deleteTimetable(timetable: Timetable, completed: (Result<Unit>) -> Unit) {
        timetableRepository.actionDeleteTimetables(listOf(timetable), completed)
    }

    fun dissolveGroup(groupId: String) {
        timetableRepository.actionDissolveGroup(groupId)
    }
}

/** 组合课表二级界面：只管理组内子课表（重命名/移出/删除)与组合本身(重命名/解散),不提供导入入口。 */
@AndroidEntryPoint
class TimetableGroupActivity : HiltBaseActivity<ComposeViewBinding>() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        fun intent(context: Context, groupId: String): Intent =
            Intent(context, TimetableGroupActivity::class.java).putExtra(EXTRA_GROUP_ID, groupId)
    }

    private val viewModel: TimetableGroupViewModel by viewModels()
    private val groupId: String by lazy { intent.getStringExtra(EXTRA_GROUP_ID).orEmpty() }

    override fun initViewBinding(): ComposeViewBinding = ComposeViewBinding(ComposeView(this))

    override fun initViews() {
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                TimetableGroupScreen(
                    viewModel = viewModel,
                    groupId = groupId,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenTimetable = { ActivityUtils.startTimetableDetailActivity(this, it.id) },
                    onDissolved = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableGroupScreen(
    viewModel: TimetableGroupViewModel,
    groupId: String,
    onBack: () -> Unit,
    onOpenTimetable: (Timetable) -> Unit,
    onDissolved: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    val groups by viewModel.groupsLiveData.observeAsState()
    val timetables by viewModel.timetablesLiveData.observeAsState(emptyList())
    val group = groups?.firstOrNull { it.id == groupId }
    val members = timetables.filter { it.groupId == groupId }.sortedBy { it.startTime.time }

    var renameTarget by remember { mutableStateOf<Timetable?>(null) }
    var deleteTarget by remember { mutableStateOf<Timetable?>(null) }
    var renameGroupOpen by remember { mutableStateOf(false) }
    var dissolveConfirmOpen by remember { mutableStateOf(false) }

    // 组成员导出：多选面板 → 口令弹层 / ICS 文件分享。
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    var exportOpen by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportTokensResult by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    // 组被解散/删空后自动退出二级界面。
    LaunchedEffect(groups, groupId) {
        if (groups != null && group == null) onDissolved()
    }
    if (groups != null && group == null) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = group?.name ?: stringResource(R.string.default_timetable_group_name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            actions = {
                TextButton(onClick = { exportOpen = true }, enabled = members.isNotEmpty()) {
                    Text(stringResource(R.string.export))
                }
                TextButton(onClick = { renameGroupOpen = true }) {
                    Text(stringResource(R.string.timetable_rename))
                }
                TextButton(onClick = { dissolveConfirmOpen = true }) {
                    Text(
                        stringResource(R.string.timetable_group_dissolve),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(tokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
        ) {
            items(members, key = { it.id }) { timetable ->
                GroupMemberRow(
                    timetable = timetable,
                    onClick = { onOpenTimetable(timetable) },
                    onRename = { renameTarget = timetable },
                    onRemoveFromGroup = { viewModel.removeFromGroup(timetable.id) },
                    onDelete = { deleteTarget = timetable },
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initialName = target.name.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameTimetable(target.id, name) { /* 非法名字由输入框校验拦截 */ }
                renameTarget = null
            }
        )
    }
    if (renameGroupOpen && group != null) {
        RenameDialog(
            initialName = group!!.name,
            onDismiss = { renameGroupOpen = false },
            onConfirm = { name ->
                viewModel.renameGroup(group!!.id, name) {}
                renameGroupOpen = false
            }
        )
    }
    deleteTarget?.let { target ->
        // 明确写出「删本地」，并在失败后保留对话框与目标，不假装已删除。
        var deleting by remember(target) { mutableStateOf(false) }
        TimetableDeletionConfirmation(
            names = listOf(target.name ?: stringResource(R.string.default_timetable_name)),
            busy = deleting,
            onDismiss = { if (!deleting) deleteTarget = null },
            onConfirm = {
                if (deleting) return@TimetableDeletionConfirmation
                deleting = true
                viewModel.deleteTimetable(target) { result ->
                    deleting = false
                    if (result.isFailure) {
                        Toast.makeText(context, R.string.timetable_audit_write_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        deleteTarget = null
                    }
                }
            },
        )
    }
    if (dissolveConfirmOpen && group != null) {
        AlertDialog(
            onDismissRequest = { dissolveConfirmOpen = false },
            title = { Text(stringResource(R.string.timetable_group_dissolve)) },
            text = { Text(stringResource(R.string.timetable_group_dissolve_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dissolveGroup(group!!.id)
                    dissolveConfirmOpen = false
                }) { Text(stringResource(R.string.button_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { dissolveConfirmOpen = false }) { Text(stringResource(R.string.button_cancel)) }
            }
        )
    }

    if (exportOpen) {
        cn.limpu.hita.ui.timetable.export.TimetableExportSheet(
            items = members.map {
                cn.limpu.hita.ui.timetable.export.TimetableExportItem(it.id,
                    it.name ?: stringResource(R.string.default_timetable_name),
                    TimeTools.printDate(it.startTime.time))
            },
            initiallySelected = emptySet(),
            busy = exportBusy,
            onDismiss = { if (!exportBusy) exportOpen = false },
            onExportToken = { items, mode ->
                exportScope.launch {
                    val keys = items.map { it.key }
                    if (exportBusy || keys.isEmpty() || mode == null) return@launch
                    exportBusy = true
                    try {
                        exportTokensResult = viewModel.exportMemberTokens(keys,
                            context.getString(R.string.timetable_share_default_nickname), mode)
                        exportOpen = false
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.timetable_export_failed, Toast.LENGTH_SHORT).show()
                    } finally { exportBusy = false }
                }
            },
            onExportIcs = { items ->
                exportScope.launch {
                    val keys = items.map { it.key }
                    if (exportBusy || keys.isEmpty()) return@launch
                    exportBusy = true
                    try {
                        val files = viewModel.exportMemberIcs(keys)
                        exportOpen = false
                        Toast.makeText(context, R.string.timetable_export_ics_done, Toast.LENGTH_SHORT).show()
                        cn.limpu.hita.ui.timetable.export.TimetableExportSharer.shareIcsFiles(context, files)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.timetable_export_failed, Toast.LENGTH_SHORT).show()
                    } finally { exportBusy = false }
                }
            },
        )
    }
    exportTokensResult?.let { tokens ->
        cn.limpu.hita.ui.timetable.export.TimetableExportTokensDialog(
            tokens = tokens,
            onCopy = { cn.limpu.hita.ui.timetable.export.TimetableExportSharer.copyToken(context, it) },
            onShareAll = {
                exportScope.launch {
                    try {
                        cn.limpu.hita.ui.timetable.export.TimetableExportSharer.shareTokens(context, tokens)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.timetable_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { exportTokensResult = null },
        )
    }
}

@Composable
private fun GroupMemberRow(
    timetable: Timetable,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRemoveFromGroup: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timetable.name ?: stringResource(R.string.default_timetable_name),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = TimeTools.printDate(timetable.startTime.time),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_more_horiz_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    properties = PopupProperties(focusable = true)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.timetable_rename)) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.timetable_group_remove_member)) },
                        onClick = { menuOpen = false; onRemoveFromGroup() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** 课表/组合通用重命名对话框：trim、非空、≤40 码点,非法时禁用确定。 */
@Composable
internal fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = value.trim()
    val valid = trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 40
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timetable_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = !valid,
                supportingText = {
                    if (!valid) Text(stringResource(R.string.timetable_rename_invalid))
                }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) {
                Text(stringResource(R.string.button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}
