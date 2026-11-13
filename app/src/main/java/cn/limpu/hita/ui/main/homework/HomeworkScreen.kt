package cn.limpu.hita.ui.main.homework

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.webkit.CookieManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.homework.HomeworkItem
import cn.limpu.hita.data.repository.grader.GraderState
import cn.limpu.hita.data.repository.homework.HomeworkState
import cn.limpu.hita.data.repository.homework.HomeworkStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** 日期格式器在文件作用域构造一次；DateTimeFormatter 不可变且线程安全。 */
private val HOMEWORK_MONTH_DAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
private val HOMEWORK_YEAR_WEEKDAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年 · EEEE", Locale.SIMPLIFIED_CHINESE)
private val HOMEWORK_WEEKDAY_SHORT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEEE", Locale.SIMPLIFIED_CHINESE)

/**
 * 待办页：所有日期的待办常驻显示。
 *
 * 结构自顶向下：快捷添加 → 未完成的按日期分组（升序，未安排置底）→
 * 一个整体折叠的「已完成」区块 → 评阅入口。选中日期的快捷入口只决定新增项落在哪天，
 * 不再过滤列表内容，因此不存在「切到别的日子就看不到待办」的情况。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeworkScreen(
    viewModel: HomeworkViewModel,
    /**
     * 仅供 UI 测试覆盖评阅入口可见性；生产传 `null`，由 [HomeworkViewModel.graderVisible]
     * 按深圳校区 + 已登录判定。
     */
    graderVisibleOverride: Boolean? = null,
    /** 仅测试用：替换评阅卡片状态，验证失败态下的按钮组合。 */
    graderStateOverride: GraderState? = null,
) {
    val state by viewModel.state.observeAsState(HomeworkState())
    val busy by viewModel.busy.observeAsState(false)
    val today by viewModel.today.observeAsState(LocalDate.now().toString())
    val observedGrader by viewModel.graderState.observeAsState(GraderState())
    val grader = graderStateOverride ?: observedGrader
    val graderVisible by viewModel.graderVisible.observeAsState(viewModel.graderVisible.value == true)
    val showGrader = graderVisibleOverride ?: graderVisible
    val now by viewModel.now.observeAsState(System.currentTimeMillis())
    val graderTasks = remember(grader.tasks, now) {
        grader.tasks.filter { it.dueAt >= now }.sortedBy { it.dueAt }
    }
    var dateOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var quickDraft by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var draftDate by rememberSaveable { mutableStateOf(today) }
    var originalDraft by rememberSaveable { mutableStateOf("") }
    var originalDate by rememberSaveable { mutableStateOf(today) }
    var draftTime by rememberSaveable { mutableStateOf("23:59") }
    var originalTime by rememberSaveable { mutableStateOf("23:59") }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickerDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerForEditor by rememberSaveable { mutableStateOf(false) }
    val selectedDate = dateOverride ?: today
    val selectedDay = LocalDate.parse(selectedDate)
    // 一次扫描产出所有日期的分组；键盘输入或时间刷新都不会重复遍历。
    val board = remember(state.items) { buildHomeworkBoard(state.items) }
    val context = LocalContext.current
    val graderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshToday()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun selectDate(value: String) {
        dateOverride = value.takeUnless { it == today }
        draftDate = value
    }
    fun openEditor(item: HomeworkItem? = null) {
        editingId = item?.id
        draft = item?.title ?: quickDraft
        draftDate = item?.plannedDate ?: selectedDate
        draftTime = item?.plannedTime ?: "23:59"
        originalDraft = draft
        originalDate = draftDate
        originalTime = draftTime
        editing = true
        confirmDelete = false
        confirmDiscard = false
    }
    fun closeEditor() {
        if (busy) return
        if (draft != originalDraft || draftDate != originalDate || draftTime != originalTime) confirmDiscard = true else editing = false
    }
    fun pickDate(value: String, editor: Boolean = false) {
        pickerForEditor = editor
        pickerDate = value
    }
    fun complete(item: HomeworkItem, value: Boolean) {
        viewModel.complete(item.id, value) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    context.getString(if (value) R.string.homework_marked_done else R.string.homework_restored),
                    actionLabel = context.getString(R.string.homework_undo),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.complete(item.id, !value)
            }
        }
    }
    fun addQuickly() {
        if (busy || !state.ready || quickDraft.isBlank()) return
        val text = quickDraft
        val planned = selectedDate
        viewModel.save(null, text, planned) {
            quickDraft = ""
            selectDate(planned)
        }
    }

    LaunchedEffect(graderTasks, now, showGrader) {
        if (!showGrader) return@LaunchedEffect
        graderTasks.firstOrNull()?.let { task ->
            delay((task.dueAt - now).coerceAtLeast(0L) + 1L)
            viewModel.refreshToday()
        }
    }
    // A saved dialog request is recreated on rotation and dismissed with its composition.
    pickerDate?.let { value ->
        DisposableEffect(value, pickerForEditor) {
            val initial = LocalDate.parse(value)
            val dialog = DatePickerDialog(context, { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day).toString()
                if (pickerForEditor) draftDate = picked else selectDate(picked)
                pickerDate = null
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            dialog.setOnDismissListener { pickerDate = null }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }
    if (showTimePicker) {
        DisposableEffect(Unit) {
            val initial = LocalTime.parse(draftTime)
            val dialog = TimePickerDialog(context, { _, hour, minute ->
                draftTime = LocalTime.of(hour, minute).toString()
                showTimePicker = false
            }, initial.hour, initial.minute, true)
            dialog.setOnDismissListener { showTimePicker = false }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    HomeworkPlannerHeader(
                        today = today,
                        selectedDate = selectedDate,
                        selectedDay = selectedDay,
                        board = board,
                        onSelectDate = ::selectDate,
                        onPickDate = { pickDate(selectedDate) },
                    )
                }
                item(key = "add") {
                    HomeworkQuickAdd(
                        draft = quickDraft,
                        onDraftChange = { if (it.length <= HomeworkStore.MAX_TITLE_LENGTH) quickDraft = it },
                        enabled = state.ready && !busy,
                        plannedDate = selectedDate,
                        onAdd = ::addQuickly,
                        onAddWithDate = { openEditor() },
                    )
                }
                if (!state.ready && !state.failed) {
                    item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                if (state.failed) {
                    item(key = "error") {
                        Column {
                            Text(stringResource(R.string.homework_storage_error), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::reload, enabled = !busy) { Text(stringResource(R.string.homework_retry)) }
                        }
                    }
                }
                if (state.ready && board.totalCount == 0) {
                    item(key = "empty") { HomeworkEmptyState() }
                }
                if (state.ready) {
                    val split = board.groups.splitUndated()
                    val undated = split.undated
                    split.dated.forEach { group ->
                        item(key = "group-${group.date}", contentType = "group") {
                            HomeworkGroupSection(
                                label = groupLabel(group.date, today),
                                subtitle = daySubtitle(group.date),
                                pending = group.pending,
                                completed = emptyList(),
                                busy = busy,
                                onComplete = ::complete,
                                onEdit = ::openEditor,
                            )
                        }
                    }
                    if (undated != null) {
                        item(key = "group-undated", contentType = "group") {
                            HomeworkGroupSection(
                                label = stringResource(R.string.homework_undated_section),
                                subtitle = stringResource(R.string.homework_undated_section_subtitle, undated.pending.size),
                                pending = undated.pending,
                                completed = emptyList(),
                                busy = busy,
                                onComplete = ::complete,
                                onEdit = ::openEditor,
                            )
                        }
                    }
                    if (board.completedCount > 0) {
                        item(key = "completed-toggle") {
                            CompletedSectionHeader(
                                count = board.completedCount,
                                expanded = showCompleted,
                                onToggle = { showCompleted = !showCompleted },
                            )
                        }
                        if (showCompleted) {
                            val completedSplit = board.completedGroups.splitUndated()
                            val undatedCompleted = completedSplit.undated
                            completedSplit.dated.forEach { group ->
                                item(key = "done-${group.date}", contentType = "group") {
                                    HomeworkGroupSection(
                                        label = groupLabel(group.date, today),
                                        subtitle = daySubtitle(group.date),
                                        pending = emptyList(),
                                        completed = group.completed,
                                        busy = busy,
                                        onComplete = ::complete,
                                        onEdit = ::openEditor,
                                    )
                                }
                            }
                            if (undatedCompleted != null) {
                                item(key = "done-undated", contentType = "group") {
                                    HomeworkGroupSection(
                                        label = stringResource(R.string.homework_undated_section),
                                        subtitle = stringResource(
                                            R.string.homework_undated_section_subtitle,
                                            undatedCompleted.completed.size,
                                        ),
                                        pending = emptyList(),
                                        completed = undatedCompleted.completed,
                                        busy = busy,
                                        onComplete = ::complete,
                                        onEdit = ::openEditor,
                                    )
                                }
                            }
                        }
                    }
                }
                if (showGrader) {
                    item(key = "grader-connection") {
                        GraderConnectionCard(
                            state = grader,
                            onLogin = { graderLauncher.launch(Intent(context, GraderLoginActivity::class.java)) },
                            onRefresh = viewModel::refreshGrader,
                            onDisconnect = {
                                viewModel.disconnectGrader {
                                    CookieManager.getInstance().setCookie(
                                        "https://grader.hitsz.edu.cn",
                                        "sessionid=; Max-Age=0; Path=/; Secure; HttpOnly",
                                    ) { CookieManager.getInstance().flush() }
                                }
                            },
                            empty = graderTasks.isEmpty(),
                        )
                    }
                    if (graderTasks.isNotEmpty()) {
                        item(key = "grader-pending-heading") {
                            Text(stringResource(R.string.grader_pending_count, graderTasks.size),
                                style = MaterialTheme.typography.titleSmall)
                        }
                        items(graderTasks, key = { "grader-${it.id}" }) { task ->
                            GraderTaskRow(task, grader.syncing) { viewModel.completeGrader(task.id, it) }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = 92.dp))
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = ::closeEditor,
            title = { Text(stringResource(if (editingId == null) R.string.homework_add else R.string.homework_edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= HomeworkStore.MAX_TITLE_LENGTH) draft = it },
                        label = { Text(stringResource(R.string.homework_content)) },
                        supportingText = { Text(stringResource(R.string.homework_character_count, draft.length, HomeworkStore.MAX_TITLE_LENGTH)) },
                        enabled = !busy, minLines = 2, maxLines = 5,
                    )
                    TextButton(onClick = { pickDate(draftDate, editor = true) }, enabled = !busy) {
                        Text(stringResource(R.string.homework_planned_date, draftDate))
                    }
                    TextButton(onClick = { showTimePicker = true }, enabled = !busy) {
                        Text(stringResource(R.string.homework_deadline_time, draftTime))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { draftDate = today }, enabled = !busy) { Text(stringResource(R.string.homework_today)) }
                        TextButton(onClick = { draftDate = LocalDate.parse(today).plusDays(1).toString() }, enabled = !busy) {
                            Text(stringResource(R.string.homework_tomorrow))
                        }
                    }
                    if (state.failed) Text(stringResource(R.string.homework_storage_error), color = MaterialTheme.colorScheme.error)
                    if (editingId != null) {
                        TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                            Text(stringResource(R.string.homework_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && draft.isNotBlank(), onClick = {
                    viewModel.save(editingId, draft, draftDate, draftTime) {
                        if (editingId == null) quickDraft = ""
                        selectDate(draftDate)
                        editing = false
                    }
                }) { Text(stringResource(R.string.homework_save)) }
            },
            dismissButton = {
                TextButton(onClick = ::closeEditor, enabled = !busy) { Text(stringResource(R.string.homework_cancel)) }
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.homework_discard_title)) },
            text = { Text(stringResource(R.string.homework_discard_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; editing = false }) { Text(stringResource(R.string.homework_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.homework_keep_editing)) }
            },
        )
    }
    if (confirmDelete && editingId != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            title = { Text(stringResource(R.string.homework_delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.homework_delete_message))
                    if (state.failed) Text(stringResource(R.string.homework_storage_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingId?.let { id -> viewModel.delete(id) { confirmDelete = false; editing = false } }
                }, enabled = !busy) { Text(stringResource(R.string.homework_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text(stringResource(R.string.homework_cancel)) }
            },
        )
    }
}

/** 顶部只负责「新待办加到哪一天」，列表本身不再被日期过滤。 */
@Composable
private fun HomeworkPlannerHeader(
    today: String,
    selectedDate: String,
    selectedDay: LocalDate,
    board: HomeworkBoard,
    onSelectDate: (String) -> Unit,
    onPickDate: () -> Unit,
) {
    val counts = remember(board.groups) {
        board.groups.mapNotNull { group -> group.date?.let { it to group.pending.size } }.toMap()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelectDate(selectedDay.minusDays(1).toString()) }) {
                Icon(
                    painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                    stringResource(R.string.homework_previous),
                    Modifier.rotate(180f),
                )
            }
            Column(
                Modifier.weight(1f).clickable(onClick = onPickDate).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(selectedDay.format(HOMEWORK_MONTH_DAY), style = MaterialTheme.typography.headlineSmall)
                Text(
                    selectedDay.format(HOMEWORK_YEAR_WEEKDAY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onSelectDate(selectedDay.plusDays(1).toString()) }) {
                Icon(
                    painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                    stringResource(R.string.homework_next),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                onClick = { onSelectDate(today) },
                label = { Text(stringResource(R.string.homework_today)) },
                selected = selectedDate == today,
            )
            AssistChip(
                onClick = onPickDate,
                label = { Text(stringResource(R.string.homework_calendar)) },
            )
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(stringResource(R.string.homework_pending_count, board.pendingCount))
                },
            )
        }
        Text(
            stringResource(R.string.homework_board_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HomeworkWeekStrip(
            selectedDay = selectedDay,
            today = today,
            counts = counts,
            onSelectDate = onSelectDate,
        )
    }
}

/** 一周的日期条：只用于挑选新增日期，并显示每天还有多少未完成。 */
@Composable
private fun HomeworkWeekStrip(
    selectedDay: LocalDate,
    today: String,
    counts: Map<String, Int>,
    onSelectDate: (String) -> Unit,
) {
    val weekStart = selectedDay.minusDays((selectedDay.dayOfWeek.value - 1).toLong())
    val week = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        week.forEach { day ->
            val key = day.toString()
            val count = counts[key] ?: 0
            val selected = day == selectedDay
            val label = stringResource(R.string.homework_day_description, key, count)
            Surface(
                onClick = { onSelectDate(key) },
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
                shape = RoundedCornerShape(18.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        day.format(HOMEWORK_WEEKDAY_SHORT),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (key == today && !selected) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current,
                    )
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                    Text(if (count > 0) count.toString() else "·", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** 一个日期分组的卡片壳；未完成在上、已完成在下（同组内已完成只在展开时出现）。 */
@Composable
private fun HomeworkGroupSection(
    label: String,
    subtitle: String,
    pending: List<HomeworkItem>,
    completed: List<HomeworkItem>,
    busy: Boolean,
    onComplete: (HomeworkItem, Boolean) -> Unit,
    onEdit: (HomeworkItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (pending.isNotEmpty()) {
                Text(
                    stringResource(R.string.homework_pending_count, pending.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        pending.forEach { item ->
            HomeworkRow(item, busy, { onComplete(item, it) }, { onEdit(item) })
        }
        completed.forEach { item ->
            HomeworkRow(item, busy, { onComplete(item, it) }, { onEdit(item) })
        }
    }
}

/** 已完成区块的整体折叠头：默认收起，一次点击展开全部。 */
@Composable
private fun CompletedSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.homework_completed_section, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (expanded) R.string.homework_collapse else R.string.homework_show_all, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 90f else 0f),
            )
        }
    }
}

@Composable
private fun HomeworkQuickAdd(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    plannedDate: String,
    onAdd: () -> Unit,
    onAddWithDate: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.homework_quick_add_date, plannedDate)) },
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            trailingIcon = {
                IconButton(onClick = onAdd, enabled = enabled && draft.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_baseline_add_24), stringResource(R.string.homework_add))
                }
            },
        )
        TextButton(onClick = onAddWithDate, enabled = enabled) {
            Text(stringResource(R.string.homework_add_with_date))
        }
    }
}

@Composable
private fun HomeworkEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_baseline_check_24),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.homework_empty), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.homework_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 有日期分组的标题：今天/明天用相对说法，其余用「M月d日」。 */
@Composable
private fun groupLabel(date: String, today: String): String = when (date) {
    today -> stringResource(R.string.homework_today)
    LocalDate.parse(today).plusDays(1).toString() -> stringResource(R.string.homework_tomorrow)
    else -> LocalDate.parse(date).format(HOMEWORK_MONTH_DAY)
}

@Composable
private fun daySubtitle(date: String): String =
    LocalDate.parse(date).format(HOMEWORK_YEAR_WEEKDAY)

@Composable
private fun HomeworkRow(item: HomeworkItem, busy: Boolean, onComplete: (Boolean) -> Unit, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.completed, onCheckedChange = onComplete, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = item.title },
            )
            Column(
                modifier = Modifier.weight(1f).clickable(enabled = !busy, onClick = onEdit).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.title,
                    textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (item.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    item.deadlineText?.let { stringResource(R.string.homework_deadline, it) }
                        ?: stringResource(R.string.homework_no_deadline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit, enabled = !busy) {
                Icon(
                    painterResource(R.drawable.ic_baseline_edit_24),
                    stringResource(R.string.homework_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
