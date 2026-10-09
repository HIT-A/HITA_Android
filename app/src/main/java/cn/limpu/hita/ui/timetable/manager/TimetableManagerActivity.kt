package cn.limpu.hita.ui.timetable.manager

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.imp.ImportTimetableActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.FileProviderUtils
import cn.limpu.hita.utils.IcsImportUtils
import cn.limpu.hita.utils.ShareUtils
import cn.limpu.hita.utils.TimeTools
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class TimetableManagerActivity : HiltBaseActivity<ComposeViewBinding>() {

    protected val viewModel: TimetableManagerViewModel by viewModels()
    private var selectedTimetableIds by mutableStateOf(emptySet<String>())
    private var isExporting by mutableStateOf(false)
    private var lastExportSuccess: Boolean? by mutableStateOf(null)

    private val selectIcsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let { importICS(it) }
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    override fun initViews() {
        bindLiveData()
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                TimetableManagerScreen(
                    viewModel = viewModel,
                    selectedTimetableIds = selectedTimetableIds,
                    isExporting = isExporting,
                    lastExportSuccess = lastExportSuccess,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenTimetable = { ActivityUtils.startTimetableDetailActivity(getThis(), it.id) },
                    onCreateTimetable = { viewModel.startNewTimetable() },
                    onImportEas = {
                        ActivityUtils.startActivity(
                            this@TimetableManagerActivity,
                            ImportTimetableActivity::class.java
                        )
                    },
                    onImportIcs = { selectIcsLauncher.launch(IcsImportUtils.pickerMimeTypes()) },
                    onOpenFriends = { startActivity(cn.limpu.hita.ui.timetable.friend.FriendTimetableActivity.intent(this)) },
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
                    onDeleteSelected = { timetables ->
                        val toDelete = timetables.filter { selectedTimetableIds.contains(it.id) }
                        if (toDelete.isNotEmpty()) {
                            viewModel.startDeleteTimetables(toDelete)
                            selectedTimetableIds = emptySet()
                        }
                    },
                    onExport = { timetables -> showExportPicker(timetables) }
                )
            }
        }
    }

    private fun bindLiveData() {
        viewModel.exportToICSResult.observe(this) {
            isExporting = false
            lastExportSuccess = it.state == DataState.STATE.SUCCESS
            if (it.state == DataState.STATE.SUCCESS) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                Toast.makeText(getThis(), "已导出为ICS文件", Toast.LENGTH_SHORT).show()
                val path = it.data ?: return@observe
                val file = File(path)
                val uri = FileProviderUtils.getUriForFile(getThis(), file)
                val shareIntent = ShareUtils.buildShareIntentForUri(uri, "text/calendar")
                startActivity(Intent.createChooser(shareIntent, "分享"))
            } else if (it.state == DataState.STATE.FETCH_FAILED) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                Toast.makeText(getThis(), "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showExportPicker(timetables: List<Timetable>) {
        if (timetables.isEmpty()) {
            Toast.makeText(getThis(), R.string.timetable_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = timetables.map { it.name ?: getString(R.string.default_timetable_name) }
            .toTypedArray()
        AlertDialog.Builder(getThis())
            .setTitle(R.string.timetable_export_title)
            .setItems(names) { _, which ->
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                isExporting = true
                lastExportSuccess = null
                viewModel.exportToIcs(timetables[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun importICS(uri: android.net.Uri) {
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
            val displayName = IcsImportUtils.getDisplayName(this, uri)
            viewModel.importFromICSAsNewTimetable(inputStream, displayName).observe(this) {
                when (it.state) {
                    DataState.STATE.SUCCESS -> {
                        val result = it.data ?: return@observe
                        Toast.makeText(
                            this,
                            "已创建课表“${result.timetableName}”，导入 ${result.importedCount} 个课程",
                            Toast.LENGTH_SHORT
                        ).show()
                        ActivityUtils.startTimetableDetailActivity(this, result.timetableId)
                    }

                    DataState.STATE.FETCH_FAILED -> {
                        Toast.makeText(this, "导入失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }

                    else -> {
                        Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Toast.makeText(this, "正在导入...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TimetableManagerScreen(
    viewModel: TimetableManagerViewModel,
    selectedTimetableIds: Set<String>,
    isExporting: Boolean,
    lastExportSuccess: Boolean?,
    onBack: () -> Unit,
    onOpenTimetable: (Timetable) -> Unit,
    onCreateTimetable: () -> Unit,
    onImportEas: () -> Unit,
    onImportIcs: () -> Unit,
    onOpenFriends: () -> Unit,
    onStartSelection: (Timetable) -> Unit,
    onToggleSelection: (Timetable) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: (List<Timetable>) -> Unit,
    onExport: (List<Timetable>) -> Unit
) {
    val tokens = HitaTheme.tokens
    val timetables by viewModel.timetablesLiveData.observeAsState(emptyList())
    val selectionMode = selectedTimetableIds.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
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
                IconButton(onClick = { onExport(timetables) }, enabled = !isExporting) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                when (lastExportSuccess) {
                                    true -> R.drawable.ic_baseline_done_24
                                    false -> R.drawable.ic_baseline_error_24
                                    null -> R.drawable.ic_baseline_cloud_download_24
                                }
                            ),
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
        androidx.compose.material3.TextButton(onClick = onOpenFriends, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.friend_timetable_title))
        }
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedTimetableIds.size,
                timetables = timetables,
                onClearSelection = onClearSelection,
                onDeleteSelected = onDeleteSelected
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(tokens.spacing.sm)
        ) {
            items(timetables, key = { it.id }) { timetable ->
                TimetableCard(
                    timetable = timetable,
                    selected = selectedTimetableIds.contains(timetable.id),
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) onToggleSelection(timetable) else onOpenTimetable(timetable)
                    },
                    onLongClick = { onStartSelection(timetable) }
                )
            }
            item(key = "add") {
                AddTimetableCard(
                    onCreateTimetable = onCreateTimetable,
                    onImportEas = onImportEas,
                    onImportIcs = onImportIcs
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    timetables: List<Timetable>,
    onClearSelection: () -> Unit,
    onDeleteSelected: (List<Timetable>) -> Unit
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
    onLongClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(tokens.spacing.sm)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
}

@Composable
private fun AddTimetableCard(
    onCreateTimetable: () -> Unit,
    onImportEas: () -> Unit,
    onImportIcs: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AddActionButton(
                icon = R.drawable.ic_baseline_add_24,
                onClick = onCreateTimetable
            )
            AddActionButton(
                icon = R.drawable.ic_import,
                onClick = onImportEas
            )
            AddActionButton(
                icon = R.drawable.ic_baseline_cloud_download_24,
                onClick = onImportIcs
            )
        }
    }
}

@Composable
private fun AddActionButton(icon: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(64.dp)
            .padding(6.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
