package cn.limpu.hita.ui.timetable.manager

import android.content.Intent
import android.content.Context
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.data.repository.TimetableVersionStore
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.ui.timetable.detail.TimetableDeletionConfirmation
import cn.limpu.hita.ui.timetable.export.TimetableExportItem
import cn.limpu.hita.data.repository.FriendTimetableRepository
import cn.limpu.hita.data.repository.SharedTimetableImporter
import cn.limpu.hita.feature.timetableshare.protocol.FriendSaveResult
import cn.limpu.hita.ui.timetable.manager.import.ImportScopeChoice
import cn.limpu.hita.ui.timetable.manager.import.ImportScopeDialog
import cn.limpu.hita.ui.timetable.manager.import.ImportTargetDialog
import cn.limpu.hita.ui.timetable.manager.import.TokenImportSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.imp.ImportTimetableActivity
import cn.limpu.hita.ui.timetable.friend.FriendTimetableActivity
import cn.limpu.hita.ui.timetable.manager.group.RenameDialog
import cn.limpu.hita.ui.timetable.manager.group.TimetableGroupActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.IcsImportUtils
import cn.limpu.hita.utils.TimeTools
import com.limpu.component.data.DataState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 课表管理一级界面：独立课表卡 + 组合课表卡 + 统一「添加课表」入口。
 * 组合卡点击进入二级界面（[TimetableGroupActivity]）单独管理子课表。
 */
@AndroidEntryPoint
class TimetableManagerActivity : HiltBaseActivity<ComposeViewBinding>() {

    protected val viewModel: TimetableManagerViewModel by viewModels()
    private var selectedTimetableIds: Set<String>
        get() = viewModel.selectedTimetableIds
        set(value) { viewModel.selectedTimetableIds = value }
    private var addMenuOpen by mutableStateOf(false)

    companion object {
        private const val EXTRA_OPEN_ADD = "timetable.open_add"
        fun openAddIntent(context: Context) = Intent(context, TimetableManagerActivity::class.java)
            .putExtra(EXTRA_OPEN_ADD, true)
    }
    private var renameTarget by mutableStateOf<Timetable?>(null)
    private var renameGroupTarget by mutableStateOf<TimetableGroupEntity?>(null)
    private var dissolveGroupTarget by mutableStateOf<TimetableGroupEntity?>(null)

    // 导出：多选面板 → 口令弹层 / ICS 文件分享。
    private var exportSheetOpen by mutableStateOf(false)
    private var exportBusy by mutableStateOf(false)
    private var exportTokensResult by mutableStateOf<List<Pair<String, String>>?>(null)

    // 导入流程状态：ICS 文件待定(等「导入为」选择) → 我的课表成功后等「同屏显示」选择。
    private var pendingIcsFile: Pair<android.net.Uri, String>?
        get() = viewModel.pendingIcsFile
        set(value) { viewModel.pendingIcsFile = value }
    private var pendingScopeImport: Pair<String, String>?
        get() = viewModel.pendingScopeImport
        set(value) { viewModel.pendingScopeImport = value }

    // 口令导入弹层状态
    private var tokenSheetOpen: Boolean
        get() = viewModel.tokenSheetOpen
        set(value) { viewModel.tokenSheetOpen = value }
    private val tokenForm get() = viewModel.tokenForm

    @Inject lateinit var friendTimetableRepository: FriendTimetableRepository

    private val selectIcsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let { picked ->
            val displayName = IcsImportUtils.getDisplayName(this, picked)
            pendingIcsFile = picked to (displayName ?: "课程.ics")
        }
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    override fun initViews() {
        addMenuOpen = intent.getBooleanExtra(EXTRA_OPEN_ADD, false)
        intent.removeExtra(EXTRA_OPEN_ADD)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                LaunchedEffect(viewModel.importMessage) {
                    viewModel.importMessage?.let { message ->
                        viewModel.consumeImportMessage()
                        Toast.makeText(getThis(), message, Toast.LENGTH_SHORT).show()
                    }
                }
                TimetableManagerScreen(
                    viewModel = viewModel,
                    selectedTimetableIds = selectedTimetableIds,
                    exportBusy = exportBusy,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenTimetable = { ActivityUtils.startTimetableDetailActivity(getThis(), it.id) },
                    onOpenGroup = { startActivity(TimetableGroupActivity.intent(getThis(), it.id)) },
                    onOpenFriends = { startActivity(FriendTimetableActivity.intent(getThis())) },
                    onAddClick = { addMenuOpen = true },
                    onCreateBlank = viewModel::startNewTimetable,
                    onRenameTimetable = { renameTarget = it },
                    onRenameGroup = { renameGroupTarget = it },
                    onDissolveGroup = { dissolveGroupTarget = it },
                    onStartSelection = { timetable ->
                        selectedTimetableIds = selectedTimetableIds + timetable.id
                    },
                    onToggleSelection = { timetable ->
                        selectedTimetableIds = if (selectedTimetableIds.contains(timetable.id)) {
                            selectedTimetableIds - timetable.id
                        } else {
                            selectedTimetableIds + timetable.id
                        }
                    },
                    onClearSelection = { selectedTimetableIds = emptySet() },
                    onDeleteSelected = viewModel::requestDelete,
                    onExport = { exportSheetOpen = true }
                )

                viewModel.deleteTarget?.let { target ->
                    TimetableDeletionConfirmation(
                        names = target.map { it.name ?: getString(R.string.default_timetable_name) },
                        busy = viewModel.deleting,
                        onDismiss = viewModel::cancelDelete,
                        onConfirm = viewModel::confirmDelete,
                    )
                }

                if (exportSheetOpen) {
                    val groups by viewModel.groupsLiveData.observeAsState(emptyList())
                    val allTimetables by viewModel.timetablesLiveData.observeAsState(emptyList())
                    val membersByGroup = allTimetables.groupBy { it.groupId }
                    val exportItems = groups.mapNotNull { group ->
                        val members = membersByGroup[group.id].orEmpty()
                        if (members.isEmpty() || members.any(TimetableVersionStore::isProjection)) null else TimetableExportItem(
                            TimetableManagerViewModel.groupItemKey(group.id), group.name,
                            getString(R.string.timetable_group_member_count, members.size), members.mapTo(hashSetOf()) { it.id })
                    } + allTimetables.filter { it.groupId == null && !TimetableVersionStore.isProjection(it) }.map {
                        TimetableExportItem(it.id,
                            it.name ?: getString(R.string.default_timetable_name),
                            TimeTools.printDate(it.startTime.time))
                    }
                    cn.limpu.hita.ui.timetable.export.TimetableExportSheet(
                        items = exportItems,
                        initiallySelected = selectedTimetableIds,
                        busy = exportBusy,
                        onDismiss = { if (!exportBusy) exportSheetOpen = false },
                        onExportToken = { items, mode -> runExport(items, requireNotNull(mode)) },
                        onExportIcs = { items -> runExport(items, null) },
                    )
                }
                exportTokensResult?.let { tokens ->
                    cn.limpu.hita.ui.timetable.export.TimetableExportTokensDialog(
                        tokens = tokens,
                        onCopy = { cn.limpu.hita.ui.timetable.export.TimetableExportSharer.copyToken(getThis(), it) },
                        onShareAll = {
                            lifecycleScope.launch {
                                try {
                                    cn.limpu.hita.ui.timetable.export.TimetableExportSharer.shareTokens(getThis(), tokens)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    Toast.makeText(getThis(), R.string.timetable_export_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDismiss = { exportTokensResult = null },
                    )
                }

                if (addMenuOpen) {
                    AddTimetableSheet(
                        onDismiss = { addMenuOpen = false },
                        onImportEas = {
                            addMenuOpen = false
                            ActivityUtils.startActivity(getThis(), ImportTimetableActivity::class.java)
                        },
                        onImportIcs = {
                            addMenuOpen = false
                            selectIcsLauncher.launch(IcsImportUtils.pickerMimeTypes())
                        },
                        onImportToken = {
                            addMenuOpen = false
                            viewModel.editTokenInput("")
                            tokenSheetOpen = true
                        },
                        onCreateBlank = {
                            addMenuOpen = false
                            viewModel.startNewTimetable()
                        },
                    )
                }

                renameTarget?.let { target ->
                    RenameDialog(
                        initialName = target.name.orEmpty(),
                        onDismiss = { renameTarget = null },
                        onConfirm = { name ->
                            viewModel.renameTimetable(target.id, name) { success ->
                                if (!success) {
                                    Toast.makeText(getThis(), R.string.timetable_rename_invalid, Toast.LENGTH_SHORT).show()
                                }
                            }
                            renameTarget = null
                        }
                    )
                }
                renameGroupTarget?.let { target ->
                    RenameDialog(
                        initialName = target.name,
                        onDismiss = { renameGroupTarget = null },
                        onConfirm = { name ->
                            viewModel.renameGroup(target.id, name) {}
                            renameGroupTarget = null
                        }
                    )
                }
                dissolveGroupTarget?.let { target ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { dissolveGroupTarget = null },
                        title = { Text(stringResource(R.string.timetable_group_dissolve)) },
                        text = { Text(stringResource(R.string.timetable_group_dissolve_hint)) },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.dissolveGroup(target.id)
                                dissolveGroupTarget = null
                            }) { Text(stringResource(R.string.button_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { dissolveGroupTarget = null }) {
                                Text(stringResource(R.string.button_cancel))
                            }
                        }
                    )
                }

                // ICS 选好文件后的「导入为」选择。
                pendingIcsFile?.let { (uri, displayName) ->
                    ImportTargetDialog(
                        sourceName = displayName,
                        onDismiss = { pendingIcsFile = null },
                        onMine = {
                            pendingIcsFile = null
                            importIcsAsMine(uri, displayName)
                        },
                        onFriend = {
                            pendingIcsFile = null
                            importIcsAsFriend(uri, displayName)
                        }
                    )
                }

                // 课表口令导入弹层。
                if (tokenSheetOpen) {
                    TokenImportSheet(
                        input = tokenForm.input,
                        loading = tokenForm.loading,
                        errorResource = tokenForm.errorResource,
                        preview = tokenForm.preview,
                        saving = tokenForm.saving,
                        onDismiss = {
                            if (!tokenForm.saving) {
                                viewModel.editTokenInput("")
                                tokenSheetOpen = false
                            }
                        },
                        onInputChange = viewModel::editTokenInput,
                        onParse = viewModel::parseToken,
                        onImportFriend = viewModel::importTokenAsFriend,
                        onImportMine = viewModel::importTokenAsMine,
                    )
                }

                // 导入为我的课表后的「同屏显示」选择(无默认选中)。
                pendingScopeImport?.let { (importedId, importedName) ->
                    val groups by viewModel.groupsLiveData.observeAsState(emptyList())
                    val allTimetables by viewModel.timetablesLiveData.observeAsState(emptyList())
                    ImportScopeDialog(
                        timetableName = importedName,
                        groups = groups,
                        singles = allTimetables.filter { it.groupId == null && it.id != importedId },
                        onDismiss = { pendingScopeImport = null },
                        onConfirm = { choice ->
                            pendingScopeImport = null
                            when (choice) {
                                ImportScopeChoice.Separate -> Unit
                                is ImportScopeChoice.IntoGroup ->
                                    viewModel.joinGroup(importedId, choice.groupId)
                                is ImportScopeChoice.WithTimetable ->
                                    viewModel.createGroupWith(choice.timetableId, importedId)
                            }
                        }
                    )
                }
            }
        }
    }

    /** 导出执行：口令 → 结果弹层；ICS → 系统分享文件。 */
    private fun runExport(items: List<TimetableExportItem>, mode: ShareMode?) {
        if (exportBusy || items.isEmpty()) return
        exportBusy = true
        lifecycleScope.launch {
            try {
                if (mode != null) {
                    exportTokensResult = viewModel.exportTokens(items, getString(R.string.timetable_share_default_nickname), mode)
                    exportSheetOpen = false
                } else {
                    val files = viewModel.exportIcsFiles(items.map { it.key })
                    exportSheetOpen = false
                    binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    Toast.makeText(getThis(), R.string.timetable_export_ics_done, Toast.LENGTH_SHORT).show()
                    cn.limpu.hita.ui.timetable.export.TimetableExportSharer.shareIcsFiles(getThis(), files)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                Toast.makeText(getThis(), R.string.timetable_export_failed, Toast.LENGTH_SHORT).show()
            } finally {
                exportBusy = false
            }
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (selectedTimetableIds.isNotEmpty()) {
                selectedTimetableIds = emptySet()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    /** ICS → 我的课表：沿用既有导入,成功后询问是否同屏显示。 */
    private fun importIcsAsMine(uri: android.net.Uri, displayName: String) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Toast.makeText(this, "无法读取所选 ICS 文件", Toast.LENGTH_SHORT).show()
                return
            }
            viewModel.importFromICSAsNewTimetable(inputStream, displayName)
            Toast.makeText(this, "正在导入...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** ICS → 好友课表：转 HITA1 快照(昵称取文件名),走好友表既有更新语义。 */
    private fun importIcsAsFriend(uri: android.net.Uri, displayName: String) {
        val nickname = displayName.substringBeforeLast('.').ifBlank { displayName }
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = checkNotNull(contentResolver.openInputStream(uri)) {
                        "cannot open ICS document"
                    }
                    val snapshot = SharedTimetableImporter.readFriendIcs(input, displayName, nickname)
                    friendTimetableRepository.importSnapshot(snapshot)
                }
                when (result) {
                    is FriendSaveResult.Saved -> {
                        Toast.makeText(getThis(), R.string.ics_import_friend_saved, Toast.LENGTH_SHORT).show()
                        startActivity(FriendTimetableActivity.intent(getThis()))
                    }
                    is FriendSaveResult.Unchanged ->
                        Toast.makeText(getThis(), R.string.ics_import_friend_unchanged, Toast.LENGTH_SHORT).show()
                    is FriendSaveResult.NeedsConfirmation -> viewModel.showFriendConfirmation(result.preview)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(getThis(), R.string.timetable_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

}

/** 一级网格条目：组合课表卡或独立课表卡。 */
private sealed interface ManagerGridItem {
    val key: String
    data class GroupItem(val group: TimetableGroupEntity, val memberCount: Int) : ManagerGridItem {
        override val key: String get() = "group-${group.id}"
    }
    data class TimetableItem(val timetable: Timetable) : ManagerGridItem {
        override val key: String get() = timetable.id
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TimetableManagerScreen(
    viewModel: TimetableManagerViewModel,
    selectedTimetableIds: Set<String>,
    exportBusy: Boolean,
    onBack: () -> Unit,
    onOpenTimetable: (Timetable) -> Unit,
    onOpenGroup: (TimetableGroupEntity) -> Unit,
    onOpenFriends: () -> Unit,
    onAddClick: () -> Unit,
    onCreateBlank: () -> Unit,
    onRenameTimetable: (Timetable) -> Unit,
    onRenameGroup: (TimetableGroupEntity) -> Unit,
    onDissolveGroup: (TimetableGroupEntity) -> Unit,
    onStartSelection: (Timetable) -> Unit,
    onToggleSelection: (Timetable) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: (List<Timetable>) -> Unit,
    onExport: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val loadedTimetables by viewModel.timetablesLiveData.observeAsState()
    val timetables = loadedTimetables.orEmpty()
    val groups by viewModel.groupsLiveData.observeAsState(emptyList())
    val selectionMode = selectedTimetableIds.isNotEmpty()

    val memberCounts = timetables.groupingBy { it.groupId }.eachCount()
    val gridItems = remember(timetables, groups) {
        val groupItems = groups.mapNotNull { group ->
            val count = memberCounts[group.id] ?: 0
            if (count == 0) null else ManagerGridItem.GroupItem(group, count)
        }
        val singles = timetables.filter { it.groupId == null }
            .map { ManagerGridItem.TimetableItem(it) }
        groupItems + singles
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.title_timetable_manager),
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
                IconButton(onClick = onExport, enabled = !exportBusy && timetables.isNotEmpty()) {
                    if (exportBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_cloud_download_24),
                            contentDescription = stringResource(R.string.export),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )
        // 好友课表入口：与课表卡网格分离的列表行,弱化但可达。
        FriendEntryRow(onClick = onOpenFriends)
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedTimetableIds.size,
                timetables = timetables,
                onClearSelection = onClearSelection,
                onDeleteSelected = onDeleteSelected,
                onExportSelected = onExport
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(tokens.spacing.sm)
        ) {
            items(gridItems, key = { it.key }) { item ->
                when (item) {
                    is ManagerGridItem.GroupItem -> GroupTimetableCard(
                        group = item.group,
                        memberCount = item.memberCount,
                        onClick = { onOpenGroup(item.group) },
                        onRename = { onRenameGroup(item.group) },
                        onDissolve = { onDissolveGroup(item.group) },
                    )
                    is ManagerGridItem.TimetableItem -> TimetableCard(
                        timetable = item.timetable,
                        selected = selectedTimetableIds.contains(item.timetable.id),
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode && !TimetableVersionStore.isProjection(item.timetable)) onToggleSelection(item.timetable)
                            else onOpenTimetable(item.timetable)
                        },
                        onRename = { if (!TimetableVersionStore.isProjection(item.timetable)) onRenameTimetable(item.timetable) },
                        onLongSelect = { if (!TimetableVersionStore.isProjection(item.timetable)) onStartSelection(item.timetable) },
                    )
                }
            }
            item(key = "add", span = { GridItemSpan(2) }) {
                if (loadedTimetables?.isEmpty() == true) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.timetable_audit_empty))
                        TextButton(onClick = onAddClick, enabled = !viewModel.creating) { Text(stringResource(R.string.timetable_audit_import)) }
                        TextButton(onClick = onCreateBlank, enabled = !viewModel.creating) { Text(stringResource(R.string.timetable_add_blank)) }
                    }
                } else AddTimetableCard(onClick = onAddClick)
            }
        }
    }
}

@Composable
private fun FriendEntryRow(onClick: () -> Unit) {
    val tokens = HitaTheme.tokens
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_share_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.friend_timetable_title),
                modifier = Modifier.padding(start = tokens.spacing.md),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    timetables: List<Timetable>,
    onClearSelection: () -> Unit,
    onDeleteSelected: (List<Timetable>) -> Unit,
    onExportSelected: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已选择 $selectedCount 项",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onExportSelected) {
                Text(text = stringResource(R.string.export))
            }
            Button(
                onClick = { onDeleteSelected(timetables) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(text = "删除")
            }
            IconButton(onClick = onClearSelection) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_close_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimetableCard(
    timetable: Timetable,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onLongSelect: () -> Unit
) {
    val tokens = HitaTheme.tokens
    var contextMenuOpen by remember { mutableStateOf(false) }
    val season = TimeTools.getSeason(timetable.startTime.time)
    val image = when (season) {
        TimeTools.SEASON.SPRING -> R.drawable.season_spring
        TimeTools.SEASON.SUMMER -> R.drawable.season_summer
        TimeTools.SEASON.AUTUMN -> R.drawable.season_autumn
        else -> R.drawable.season_winter
    }
    val container = when (season) {
        TimeTools.SEASON.SPRING -> Color(0xFFE5F4DF)
        TimeTools.SEASON.SUMMER -> Color(0xFFE1F0FF)
        TimeTools.SEASON.AUTUMN -> Color(0xFFFFE8D6)
        else -> Color(0xFFE8E8F0)
    }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(tokens.spacing.sm)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (!selectionMode) contextMenuOpen = true }
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = tokens.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier
                        .size(140.dp)
                        .padding(tokens.spacing.lg),
                    shape = CircleShape,
                    color = container
                ) {
                    Image(
                        painter = painterResource(image),
                        contentDescription = null,
                        modifier = Modifier.padding(18.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = timetable.name ?: stringResource(R.string.default_timetable_name),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = TimeTools.printDate(timetable.startTime.time),
                            modifier = Modifier.padding(top = tokens.spacing.xs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = { onClick() })
                    }
                }
            }
        }
        DropdownMenu(expanded = contextMenuOpen, onDismissRequest = { contextMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.timetable_rename)) },
                onClick = { contextMenuOpen = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.timetable_manager_select)) },
                onClick = { contextMenuOpen = false; onLongSelect() }
            )
        }
    }
}

/** 组合课表卡：组名 + 成员数徽标;点击进二级界面,长按弹重命名/解散。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTimetableCard(
    group: TimetableGroupEntity,
    memberCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    var contextMenuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(tokens.spacing.sm)
                .combinedClickable(onClick = onClick, onLongClick = { contextMenuOpen = true }),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = tokens.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier
                        .size(140.dp)
                        .padding(tokens.spacing.lg),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_apps_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg)
                ) {
                    Text(
                        text = group.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.timetable_group_member_count, memberCount),
                        modifier = Modifier.padding(top = tokens.spacing.xs),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
        DropdownMenu(expanded = contextMenuOpen, onDismissRequest = { contextMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.timetable_rename)) },
                onClick = { contextMenuOpen = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.timetable_group_dissolve)) },
                onClick = { contextMenuOpen = false; onDissolve() }
            )
        }
    }
}

/** 统一「添加课表」入口卡：单一按钮,点击弹底部菜单。 */
@Composable
private fun AddTimetableCard(onClick: () -> Unit) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.sm)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = tokens.spacing.lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_add_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.timetable_add_entry),
                modifier = Modifier.padding(start = tokens.spacing.sm),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 添加课表底部菜单：教务导入 / 导入 ICS / 课表口令导入 / 新建空白课表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTimetableSheet(
    onDismiss: () -> Unit,
    onImportEas: () -> Unit,
    onImportIcs: () -> Unit,
    onImportToken: () -> Unit,
    onCreateBlank: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.timetable_add_entry),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            AddSheetRow(
                icon = R.drawable.ic_import,
                title = stringResource(R.string.import_timetable),
                subtitle = stringResource(R.string.timetable_add_import_eas_hint),
                onClick = onImportEas
            )
            AddSheetRow(
                icon = R.drawable.ic_baseline_cloud_download_24,
                title = stringResource(R.string.import_ics_timetable),
                subtitle = stringResource(R.string.timetable_add_import_ics_hint),
                onClick = onImportIcs
            )
            AddSheetRow(
                icon = R.drawable.ic_baseline_share_24,
                title = stringResource(R.string.timetable_add_import_token),
                subtitle = stringResource(R.string.timetable_add_import_token_hint),
                onClick = onImportToken
            )
            AddSheetRow(
                icon = R.drawable.ic_baseline_add_24,
                title = stringResource(R.string.timetable_add_blank),
                subtitle = stringResource(R.string.timetable_add_blank_hint),
                onClick = onCreateBlank
            )
        }
    }
}

@Composable
private fun AddSheetRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
