package cn.limpu.hita.ui.main.homework

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import cn.limpu.hita.data.model.homework.HomeworkSubmissionFormat
import cn.limpu.hita.data.model.homework.HomeworkSubmissionMethod
import cn.limpu.hita.data.repository.grader.GraderState
import cn.limpu.hita.data.repository.geniusta.GeniusTaState
import cn.limpu.hita.data.repository.homework.HomeworkState
import cn.limpu.hita.data.repository.homework.HomeworkStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

/** One chronological todo list, with date headings and one collapsible completed section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeworkScreen(
    viewModel: HomeworkViewModel,
    graderVisibleOverride: Boolean? = null,
    graderStateOverride: GraderState? = null,
    geniusTaStateOverride: GeniusTaState? = null,
) {
    val state by viewModel.state.observeAsState(HomeworkState())
    val busy by viewModel.busy.observeAsState(false)
    val today by viewModel.today.observeAsState(LocalDate.now().toString())
    val observedGrader by viewModel.graderState.observeAsState(GraderState())
    val grader = graderStateOverride ?: observedGrader
    val observedGeniusTa by viewModel.geniusTaState.observeAsState(GeniusTaState())
    val geniusTa = geniusTaStateOverride ?: observedGeniusTa
    val graderVisible by viewModel.graderVisible.observeAsState(viewModel.graderVisible.value == true)
    val showGrader = graderVisibleOverride ?: graderVisible
    val now by viewModel.now.observeAsState(System.currentTimeMillis())
    var geniusLogin by remember { mutableStateOf(false) }
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
    val deviceZone = ZoneId.systemDefault()
    val board = remember(state.items, showGrader, grader.accountId, grader.tasks, geniusTa.accountId, geniusTa.tasks, deviceZone, now) {
        val imported = buildList {
            if (showGrader) {
                grader.accountId?.let { add(ImportedTodoGroup(TodoPlatform.GRADER, it, grader.tasks)) }
            }
            geniusTa.accountId?.let { add(ImportedTodoGroup(TodoPlatform.GENIUSTA, it, geniusTa.tasks)) }
        }
        buildHomeworkBoard(state.items, imported, deviceZone, now)
    }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val graderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshToday()
    }
    val submitLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        viewModel.refreshToday()
        // 成功后从列表侧给出确认，避免用户只看到页面自己关掉。
        scope.launch { snackbar.showSnackbar(context.getString(R.string.grader_submit_success)) }
    }
    val openGraderLogin = { graderLauncher.launch(Intent(context, GraderLoginActivity::class.java)) }
    // 每条待办各自的提交回执：撤销只对同一条、同一次写入生效。
    val actions = remember(viewModel) { HomeworkCompletionActions(viewModel::setCompleted) }
    val receipts by actions.receipts.collectAsState()
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
    fun undo(key: TodoKey, receipt: TodoCompletionReceipt) {
        scope.launch { actions.undo(key, receipt) }
    }
    fun complete(row: TodoRow, value: Boolean) {
        scope.launch {
            // 按 key + 回执推进，两次快速完成各留各的撤销，不会互相覆盖。
            actions.change(row, value)
            val receipt = actions.receipts.value[row.key]
            if (receipt?.phase == TodoCompletionPhase.SaveFailed) {
                snackbar.showSnackbar(context.getString(R.string.homework_toggle_failed), withDismissAction = true)
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
    fun openGraderSubmit(row: TodoRow) {
        val key = row.key as? TodoKey.Imported ?: return
        if (key.platform != TodoPlatform.GRADER) return
        val intent = Intent(context, GraderSubmitActivity::class.java).apply {
            putExtra(GraderSubmitActivity.EXTRA_ASSIGNMENT_ID, key.id)
            putExtra(GraderSubmitActivity.EXTRA_ACCOUNT_ID, key.accountId)
            putExtra(GraderSubmitActivity.EXTRA_TITLE, row.title)
            putExtra(GraderSubmitActivity.EXTRA_COURSE, row.course)
            putExtra(GraderSubmitActivity.EXTRA_DUE_AT, row.dueAt ?: 0L)
            putExtra(GraderSubmitActivity.EXTRA_RESUBMIT, row.submitted)
        }
        submitLauncher.launch(intent)
    }

    val nextDue = board.nextImportedDeadline
    LaunchedEffect(nextDue, now) {
        if (nextDue != null) {
            delay((nextDue - System.currentTimeMillis()).coerceAtLeast(0L) + 1L)
            viewModel.refreshToday()
        }
    }
    val graderError = if (showGrader) grader.error?.messageRes else null
    val geniusError = if (!geniusLogin) geniusTa.error?.messageRes else null
    LaunchedEffect(graderError, geniusError) {
        val message = listOfNotNull(graderError, geniusError).joinToString("\n") { context.getString(it) }
        if (message.isNotEmpty()) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, withDismissAction = true)
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
        Box(Modifier.fillMaxSize()) {
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
                        onAdd = ::addQuickly,
                        onAddWithDate = { openEditor() },
                    )
                }
                item(key = "platform-connections") {
                    TodoPlatformConnections(
                        grader = grader.takeIf { showGrader },
                        geniusTa = geniusTa,
                        onGraderLogin = openGraderLogin,
                        onGraderRefresh = viewModel::refreshGrader,
                        onGraderDisconnect = viewModel::disconnectGrader,
                        onGeniusTaLogin = { geniusLogin = true },
                        onGeniusTaRefresh = viewModel::refreshGeniusTa,
                        onGeniusTaDisconnect = viewModel::disconnectGeniusTa,
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
                val importing = (showGrader && (!grader.ready || grader.syncing)) ||
                    !geniusTa.ready || geniusTa.syncing
                val emptyBoard = board.pending.isEmpty() && board.completed.isEmpty()
                if (state.ready && !state.failed && emptyBoard) {
                    if (importing) {
                        item(key = "importing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    } else if (graderError == null && geniusTa.error == null) {
                        item(key = "empty") { HomeworkEmptyState() }
                    }
                }
                fun rowBusy(key: TodoKey): Boolean = when (key) {
                    is TodoKey.Personal -> busy
                    is TodoKey.Imported -> when (key.platform) {
                        TodoPlatform.GRADER -> grader.syncing
                        TodoPlatform.GENIUSTA -> geniusTa.syncing
                    }
                }
                todoRows(board.pending, today, ::rowBusy, ::complete, ::openEditor, receipts, ::undo, ::openGraderSubmit)
                if (board.completed.isNotEmpty()) {
                    item(key = "completed-toggle") {
                        CompletedSectionHeader(
                            count = board.completed.size,
                            expanded = showCompleted,
                            onToggle = { showCompleted = !showCompleted },
                        )
                    }
                    if (showCompleted) {
                        todoRows(
                            board.completed, today, ::rowBusy, ::complete, ::openEditor, receipts, ::undo, ::openGraderSubmit,
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = 92.dp))
        }
    }

    if (geniusLogin) {
        GeniusTaLoginDialog(
            state = geniusTa,
            onDismiss = { geniusLogin = false },
            readSavedLogin = viewModel::savedGeniusTaLogin,
            forgetSavedLogin = viewModel::forgetGeniusTaLogin,
            onLogin = viewModel::connectGeniusTa,
            onConnected = { saved ->
                geniusLogin = false
                if (!saved) scope.launch {
                    snackbar.showSnackbar(context.getString(R.string.todo_platform_credential_error), withDismissAction = true)
                }
            },
        )
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
    val counts = board.pendingByDate
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
                    Text(stringResource(R.string.homework_pending_count, board.pending.size))
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

private fun LazyListScope.todoRows(
    rows: List<TodoRow>,
    today: String,
    isBusy: (TodoKey) -> Boolean,
    onComplete: (TodoRow, Boolean) -> Unit,
    onEdit: (HomeworkItem) -> Unit,
    receipts: Map<TodoKey, TodoCompletionReceipt>,
    onUndo: (TodoKey, TodoCompletionReceipt) -> Unit,
    onSubmit: (TodoRow) -> Unit,
) {
    itemsIndexed(rows, key = { _, row -> row.key.listKey }, contentType = { _, _ -> "todo" }) { index, row ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (index == 0 || row.date != rows[index - 1].date) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        row.date?.let { groupLabel(it, today) } ?: stringResource(R.string.homework_undated_section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    row.date?.let { date ->
                        Text(
                            daySubtitle(date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val submitAction: (() -> Unit)? =
                if ((row.key as? TodoKey.Imported)?.platform == TodoPlatform.GRADER) {
                    { onSubmit(row) }
                } else {
                    null
                }
            HomeworkRow(
                row = row,
                busy = isBusy(row.key) || receipts[row.key]?.busy == true,
                onComplete = { onComplete(row, it) },
                receipt = receipts[row.key],
                onEdit = row.personal?.let { personal -> { onEdit(personal) } },
                onUndo = { receipt -> onUndo(row.key, receipt) },
                onSubmit = submitAction,
            )
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
    onAdd: () -> Unit,
    onAddWithDate: () -> Unit,
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.homework_quick_add)) },
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        trailingIcon = {
            Row {
                IconButton(onClick = onAddWithDate, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_baseline_access_time_24), stringResource(R.string.homework_add_options))
                }
                IconButton(onClick = onAdd, enabled = enabled && draft.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_baseline_add_24), stringResource(R.string.homework_add))
                }
            }
        },
    )
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
private fun HomeworkRow(
    row: TodoRow,
    busy: Boolean,
    onComplete: (Boolean) -> Unit,
    onEdit: (() -> Unit)?,
    receipt: TodoCompletionReceipt? = null,
    onUndo: (TodoCompletionReceipt) -> Unit = {},
    onSubmit: (() -> Unit)? = null,
) {
    val item = row.personal
    val imported = row.key as? TodoKey.Imported
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = row.completed, onCheckedChange = onComplete, enabled = !busy && !row.submitted,
                modifier = Modifier.semantics { contentDescription = row.title },
            )
            Column(
                modifier = Modifier.weight(1f)
                    .then(if (onEdit != null) Modifier.clickable(enabled = !busy, onClick = onEdit) else Modifier)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    row.title,
                    textDecoration = if (row.completed) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (row.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    row.deadlineText?.let {
                        stringResource(if (imported == null) R.string.homework_deadline else R.string.homework_imported_deadline, it)
                    }
                        ?: stringResource(R.string.homework_no_deadline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.course.isNotBlank()) {
                    Text(
                        stringResource(R.string.homework_subject, row.course),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 撤销只出现在刚刚保存成功、且仍是这次写入的那一行。
                receipt?.takeIf { !row.submitted && it.canUndo && it.completed == row.completed }?.let { current ->
                    TextButton(
                        onClick = { onUndo(current) },
                        enabled = !busy,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.homework_undo), style = MaterialTheme.typography.labelMedium)
                    }
                }
                // 只在真的填过提交信息时补一行，避免每行都堆一排「未填写」。
                if (item != null && item.hasSubmissionInfo) {
                    val parts = buildList {
                        when (item.submissionMethod) {
                            HomeworkSubmissionMethod.ONLINE -> add(stringResource(R.string.homework_submission_online))
                            HomeworkSubmissionMethod.PAPER -> add(stringResource(R.string.homework_submission_paper))
                            HomeworkSubmissionMethod.EMAIL -> add(stringResource(R.string.homework_submission_email))
                            HomeworkSubmissionMethod.UNKNOWN -> Unit
                        }
                        when (item.submissionFormat) {
                            HomeworkSubmissionFormat.DOCUMENT -> add(stringResource(R.string.homework_format_document))
                            HomeworkSubmissionFormat.PDF -> add(stringResource(R.string.homework_format_pdf))
                            HomeworkSubmissionFormat.HANDWRITTEN -> add(stringResource(R.string.homework_format_handwritten))
                            HomeworkSubmissionFormat.PRESENTATION -> add(stringResource(R.string.homework_format_presentation))
                            HomeworkSubmissionFormat.UNKNOWN -> Unit
                        }
                        if (item.submissionDetail.isNotBlank()) add(item.submissionDetail)
                    }
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (imported != null) {
                    Text(
                        stringResource(R.string.homework_imported_source, stringResource(imported.platform.titleRes)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onSubmit != null) {
                TextButton(onClick = onSubmit, enabled = !busy) {
                    Text(
                        stringResource(
                            if (row.submitted) R.string.grader_submit_resubmit_action else R.string.grader_submit_action,
                        )
                    )
                }
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit, enabled = !busy) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_edit_24),
                        stringResource(R.string.homework_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}
