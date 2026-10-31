package cn.limpu.hita.ui.main.homework

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import cn.limpu.hita.data.repository.homework.HomeworkState
import cn.limpu.hita.data.repository.homework.HomeworkStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeworkScreen(viewModel: HomeworkViewModel) {
    val state by viewModel.state.observeAsState(HomeworkState())
    val busy by viewModel.busy.observeAsState(false)
    val today by viewModel.today.observeAsState(LocalDate.now().toString())
    var dateOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var undated by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var quickDraft by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var draftDate by rememberSaveable { mutableStateOf(today) }
    var originalDraft by rememberSaveable { mutableStateOf("") }
    var originalDate by rememberSaveable { mutableStateOf(today) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickerDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerForEditor by rememberSaveable { mutableStateOf(false) }
    val selectedDate = dateOverride ?: today
    val date = LocalDate.parse(selectedDate)
    val daily = remember(state.items, selectedDate, undated) {
        state.items.filter { it.plannedDate == if (undated) null else selectedDate }
    }
    val pending = daily.filterNot { it.completed }
    val completed = daily.filter { it.completed }
    val unplannedCount = state.items.count { it.plannedDate == null }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val weekState = rememberLazyListState()
    val weekStart = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val counts = remember(state.items) { state.items.filterNot { it.completed }.groupingBy { it.plannedDate }.eachCount() }

    fun selectDate(value: String) {
        dateOverride = value.takeUnless { it == today }
        undated = false
    }
    fun openEditor(item: HomeworkItem? = null) {
        editingId = item?.id
        draft = item?.title ?: quickDraft
        draftDate = item?.plannedDate ?: if (undated) today else selectedDate
        originalDraft = draft
        originalDate = draftDate
        editing = true
        confirmDelete = false
        confirmDiscard = false
    }
    fun closeEditor() {
        if (busy) return
        if (draft != originalDraft || draftDate != originalDate) confirmDiscard = true else editing = false
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
        val planned = if (undated) today else selectedDate
        viewModel.save(null, text, planned) {
            quickDraft = ""
            selectDate(planned)
        }
    }

    LaunchedEffect(weekStart, selectedDate) {
        weekState.animateScrollToItem((date.dayOfWeek.value - 4).coerceAtLeast(0))
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

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "date") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { selectDate(date.minusDays(1).toString()) },
                                enabled = date.year > 1 || date.dayOfYear > 1,
                            ) {
                                Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                                    stringResource(R.string.homework_previous), Modifier.rotate(180f))
                            }
                            Column(
                                Modifier.weight(1f).clickable { pickDate(selectedDate) }.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    if (undated) stringResource(R.string.homework_undated_count, unplannedCount)
                                    else date.format(DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Text(
                                    if (undated) stringResource(R.string.homework_undated_subtitle)
                                    else date.format(DateTimeFormatter.ofPattern("yyyy年 · EEEE", Locale.SIMPLIFIED_CHINESE)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { selectDate(date.plusDays(1).toString()) },
                                enabled = date.year < 9999 || date.dayOfYear < date.lengthOfYear(),
                            ) {
                                Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), stringResource(R.string.homework_next))
                            }
                        }
                        if (!undated) {
                            LazyRow(state = weekState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(7) { offset ->
                                    val day = weekStart.plusDays(offset.toLong())
                                    val count = counts[day.toString()] ?: 0
                                    val selected = day == date
                                    val label = stringResource(R.string.homework_day_description, day.toString(), count)
                                    Surface(
                                        onClick = { selectDate(day.toString()) },
                                        modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = label },
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    ) {
                                        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(day.format(DateTimeFormatter.ofPattern("EEEEE", Locale.SIMPLIFIED_CHINESE)),
                                                style = MaterialTheme.typography.labelSmall)
                                            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                                            Text(if (count > 0) count.toString() else "·", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !undated && selectedDate == today, onClick = { selectDate(today) },
                                label = { Text(stringResource(R.string.homework_today)) })
                            FilterChip(selected = false, onClick = { pickDate(selectedDate) },
                                label = { Text(stringResource(R.string.homework_calendar)) })
                            if (unplannedCount > 0 || undated) {
                                FilterChip(selected = undated, onClick = { undated = true },
                                    label = { Text(stringResource(R.string.homework_undated_count, unplannedCount)) })
                            }
                        }
                        if (undated) {
                            Text(stringResource(R.string.homework_undated_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.ready && daily.isNotEmpty()) {
                    item(key = "progress") {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.homework_progress, completed.size, daily.size),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            LinearProgressIndicator(
                                progress = { completed.size.toFloat() / daily.size },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                        }
                    }
                }
                item(key = "add") {
                    Column {
                        OutlinedTextField(
                            value = quickDraft,
                            onValueChange = { if (it.length <= HomeworkStore.MAX_TITLE_LENGTH) quickDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.ready && !busy,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.homework_quick_add)) },
                            shape = RoundedCornerShape(20.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addQuickly() }),
                            trailingIcon = {
                                IconButton(onClick = { addQuickly() }, enabled = state.ready && !busy && quickDraft.isNotBlank()) {
                                    Icon(painterResource(R.drawable.ic_baseline_add_24), stringResource(R.string.homework_add))
                                }
                            },
                        )
                        TextButton(onClick = { openEditor() }, enabled = state.ready && !busy) {
                            Text(stringResource(R.string.homework_add_with_date))
                        }
                    }
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
                if (state.ready) {
                    if (pending.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_baseline_check_24), null,
                                    Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(when {
                                    undated -> R.string.homework_undated_empty
                                    completed.isNotEmpty() -> R.string.homework_all_done
                                    else -> R.string.homework_day_empty
                                }), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.homework_empty_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        item(key = "pending-heading") {
                            Text(stringResource(R.string.homework_pending_count, pending.size), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(pending, key = { it.id }) { item ->
                        HomeworkRow(item, busy, { complete(item, it) }, { openEditor(item) })
                    }
                    if (completed.isNotEmpty()) {
                        item(key = "completed-heading") {
                            TextButton(onClick = { showCompleted = !showCompleted }) {
                                Text(stringResource(if (showCompleted) R.string.homework_hide_completed else R.string.homework_completed_count, completed.size))
                            }
                        }
                        if (showCompleted) {
                            items(completed, key = { it.id }) { item ->
                                HomeworkRow(item, busy, { complete(item, it) }, { openEditor(item) })
                            }
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
                    viewModel.save(editingId, draft, draftDate) {
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

@Composable
private fun HomeworkRow(item: HomeworkItem, busy: Boolean, onComplete: (Boolean) -> Unit, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.completed, onCheckedChange = onComplete, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = item.title },
            )
            Text(
                item.title,
                modifier = Modifier.weight(1f).clickable(enabled = !busy, onClick = onEdit).padding(vertical = 12.dp),
                textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None,
                color = if (item.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onEdit, enabled = !busy) {
                Icon(painterResource(R.drawable.ic_baseline_edit_24), stringResource(R.string.homework_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
