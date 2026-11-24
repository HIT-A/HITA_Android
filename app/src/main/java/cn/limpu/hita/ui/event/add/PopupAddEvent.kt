package cn.limpu.hita.ui.event.add

import cn.limpu.hita.data.model.timetable.CourseTime
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import androidx.compose.ui.graphics.Color as ComposeColor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.TeacherInfoRepository
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.repository.TimetableVersionStore
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.widgets.PopUpCalendarPicker
import cn.limpu.hita.ui.widgets.PopUpPickCourseTime
import cn.limpu.hita.ui.widgets.PopUpTimePeriodPicker

import com.limpu.style.widgets.DialogSelectableLiveList
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar

@AndroidEntryPoint
class PopupAddEvent(private val addSubjectMode: Boolean = false) :
    BottomSheetDialogFragment() {

    var initTimetable: Timetable? = null
    var initSubject: TermSubject? = null
    var initCourseTime: CourseTime? = null
    var editEvent: EventItem? = null
    private lateinit var viewModel: AddEventViewModel
    private var discardRequested by mutableStateOf(false)

    private fun requestClose() {
        if (viewModel.saveState.value == AddEventViewModel.SaveState.SAVING) return
        if (viewModel.dirty) discardRequested = true else dismiss()
    }

    override fun onStart() {
        super.onStart()
        isCancelable = false
        (dialog as? OnBackPressedDispatcherOwner)?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = requestClose()
            },
        )
    }

    fun setInitTimetable(timetable: Timetable?): PopupAddEvent {
        initTimetable = timetable
        return this
    }

    fun setInitTime(dow: Int, week: Int, period: TimePeriodInDay): PopupAddEvent {
        val ct = CourseTime()
        ct.dow = dow
        ct.weeks = mutableListOf(week)
        ct.period = period
        initCourseTime = ct
        return this
    }

    fun setInitSubject(subject: TermSubject): PopupAddEvent {
        initSubject = subject
        return this
    }

    fun setEditEvent(event: EventItem): PopupAddEvent {
        editEvent = event
        val ct = CourseTime()
        ct.dow = event.getDow()
        event.fromNumber.takeIf { it > 0 }?.let { from ->
            val to = from + event.lastNumber - 1
            initTimetable?.let { timetable ->
                ct.period = timetable.transformTimePeriod(from, to)
            }
        } ?: run {
            ct.period = TimePeriodInDay(TimeInDay(event.from), TimeInDay(event.to))
        }
        initCourseTime = ct
        return this
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = androidx.lifecycle.ViewModelProvider(this)[AddEventViewModel::class.java]
        viewModel.init(addSubjectMode, initTimetable, initSubject, initCourseTime, editEvent)
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    PopupAddEventScreen(
                        viewModel = viewModel,
                        addSubjectMode = viewModel.addSubject,
                        editEvent = viewModel.eventToEdit,
                        onSaved = { dismiss() },
                        onRequestClose = ::requestClose,
                        onShowTimetablePicker = { showTimetablePicker() },
                        onShowExistingEventPicker = { showExistingEventPicker() },
                        onShowDatePicker = { showDatePicker() },
                        onShowTimePicker = { showTimePicker() },
                        onShowCourseTimePicker = { mode -> showCourseTimePicker(mode) }
                    )
                    if (discardRequested) {
                        AlertDialog(
                            onDismissRequest = { discardRequested = false },
                            title = { Text(stringResource(R.string.ade_discard_title)) },
                            confirmButton = { TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.ade_discard)) } },
                            dismissButton = { TextButton(onClick = { discardRequested = false }) { Text(stringResource(R.string.ade_keep_editing)) } },
                        )
                    }
                }
            }
        }
    }


    private fun showTimetablePicker() {
        DialogSelectableLiveList<Timetable>().setTitle(R.string.ade_pick_timetable)
            .setInitValue(viewModel.timetableLiveData.value?.data)
            .setDataLoader(object : DialogSelectableLiveList.DataLoader<Timetable> {
                override fun loadData(): LiveData<List<DialogSelectableLiveList.ItemData<Timetable>>> {
                    return TimetableRepository(requireActivity().application).getTimetables()
                        .switchMap {
                            val res = mutableListOf<DialogSelectableLiveList.ItemData<Timetable>>()
                            for (data in it.filterNot(TimetableVersionStore::isProjection)) {
                                res.add(DialogSelectableLiveList.ItemData(data.name, data))
                            }
                            MutableLiveData(res)
                        }
                }
            }).setOnConfirmListener(object :
                DialogSelectableLiveList.OnConfirmListener<Timetable> {
                override fun onConfirm(title: String?, key: Timetable) {
                    viewModel.markEdited()
                    viewModel.timetableLiveData.value = DataState(key)
                }
            }).show(childFragmentManager, "set_timetable")
    }

    private fun showExistingEventPicker() {
        val timetable = viewModel.timetableLiveData.value?.data ?: return
        DialogSelectableLiveList<EventItem>().setTitle(R.string.ade_content_existing)
            .setInitValue(viewModel.selectedEventLiveData.value?.data)
            .setDataLoader(object : DialogSelectableLiveList.DataLoader<EventItem> {
                override fun loadData(): LiveData<List<DialogSelectableLiveList.ItemData<EventItem>>> {
                    return TimetableRepository(requireActivity().application)
                        .getEventsOfTimetable(timetable.id)
                        .switchMap { events ->
                            MutableLiveData(
                                events
                                    .filter { it.type != EventItem.TYPE.TAG }
                                    .distinctBy {
                                        listOf(
                                            it.name.trim(),
                                            it.place.orEmpty().trim(),
                                            it.teacher.orEmpty().trim(),
                                            it.subjectId,
                                            it.type.name,
                                        ).joinToString("|")
                                    }
                                    .sortedWith(compareBy<EventItem> { it.name }.thenBy { it.from.time })
                                    .map {
                                        DialogSelectableLiveList.ItemData(formatEventTemplateLabel(it), it)
                                    }
                            )
                        }
                }
            }).setOnConfirmListener(object :
                DialogSelectableLiveList.OnConfirmListener<EventItem> {
                override fun onConfirm(title: String?, key: EventItem) {
                    viewModel.markEdited()
                    viewModel.selectExistingEvent(key)
                }
            }).show(childFragmentManager, "pick_existing_event")
    }

    private fun showDatePicker() {
        if (viewModel.dateModeLiveData.value == AddEventViewModel.DateMode.WEEKLY) {
            showCourseTimePicker(PopUpPickCourseTime.Mode.DATE_ONLY)
        } else {
            val initDate = viewModel.customDateLiveData.value?.data
            PopUpCalendarPicker()
                .setInitValue(initDate)
                .setOnConfirmListener(object : PopUpCalendarPicker.OnConfirmListener {
                    override fun onConfirm(c: Calendar) {
                        c.set(Calendar.HOUR_OF_DAY, 0)
                        c.set(Calendar.MINUTE, 0)
                        c.set(Calendar.SECOND, 0)
                        c.set(Calendar.MILLISECOND, 0)
                        viewModel.markEdited()
                        viewModel.setCustomDate(c.timeInMillis)
                    }
                })
                .show(childFragmentManager, "pick_custom_date")
        }
    }

    private fun showTimePicker() {
        if (viewModel.timeModeLiveData.value == AddEventViewModel.TimeMode.PERIOD) {
            showCourseTimePicker(PopUpPickCourseTime.Mode.PERIOD_ONLY)
        } else {
            val initPeriod = viewModel.customTimePeriodLiveData.value?.data
            PopUpTimePeriodPicker()
                .setDialogTitle(R.string.ade_pick_time_range)
                .setInitialValue(initPeriod?.from, initPeriod?.to)
                .setOnDialogConformListener(object : PopUpTimePeriodPicker.OnDialogConformListener {
                    override fun onClick(timePeriodInDay: TimePeriodInDay) {
                        viewModel.markEdited()
                        viewModel.setCustomTimePeriod(
                            TimeInDay(timePeriodInDay.from.hour, timePeriodInDay.from.minute),
                            TimeInDay(timePeriodInDay.to.hour, timePeriodInDay.to.minute)
                        )
                    }
                }).show(childFragmentManager, "pick_custom_time_range")
        }
    }

    private fun showCourseTimePicker(mode: PopUpPickCourseTime.Mode) {
        viewModel.timetableLiveData.value?.data?.let { tt ->
            PopUpPickCourseTime(tt)
                .setMode(mode)
                .setInitialValue(tt, viewModel.timeRangeLiveDate.value?.data)
                .setSelectListener(object : PopUpPickCourseTime.OnTimeSelectedListener {
                    override fun onSelected(data: CourseTime) {
                        viewModel.markEdited()
                        viewModel.mergeCourseTimeSelection(data, mode == PopUpPickCourseTime.Mode.DATE_ONLY)
                    }
                }).show(childFragmentManager, "pick_course_time")
        }
    }

    private fun formatEventTemplateLabel(event: EventItem): String {
        return listOf(event.name, event.place.orEmpty(), event.teacher.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" / ")
    }
}

@SuppressLint("SetTextI18n")
@Composable
private fun PopupAddEventScreen(
    viewModel: AddEventViewModel,
    addSubjectMode: Boolean,
    editEvent: EventItem?,
    onSaved: () -> Unit,
    onRequestClose: () -> Unit,
    onShowTimetablePicker: () -> Unit,
    onShowExistingEventPicker: () -> Unit,
    onShowDatePicker: () -> Unit,
    onShowTimePicker: () -> Unit,
    onShowCourseTimePicker: (PopUpPickCourseTime.Mode) -> Unit
) {
    val tokens = HitaTheme.tokens

    val doneLiveData by viewModel.doneLiveData.observeAsState()
    val contentMode by viewModel.contentModeLiveData.observeAsState()
    val selectedEvent by viewModel.selectedEventLiveData.observeAsState()
    val dateMode by viewModel.dateModeLiveData.observeAsState()
    val timeMode by viewModel.timeModeLiveData.observeAsState()
    val customDate by viewModel.customDateLiveData.observeAsState()
    val customTimePeriod by viewModel.customTimePeriodLiveData.observeAsState()
    val timeRange by viewModel.timeRangeLiveDate.observeAsState()
    val teacherState by viewModel.teacherLiveData.observeAsState()
    val locationState by viewModel.locationLiveData.observeAsState()
    val timetableState by viewModel.timetableLiveData.observeAsState()
    val nameState by viewModel.nameLiveData.observeAsState()
    val saveState by viewModel.saveState.observeAsState(AddEventViewModel.SaveState.EDITING)
    val saving = saveState == AddEventViewModel.SaveState.SAVING
    val frozen = saving || saveState == AddEventViewModel.SaveState.SAVED
    LaunchedEffect(saveState) {
        if (saveState == AddEventViewModel.SaveState.SAVED) onSaved()
    }

    val nameText = nameState.orEmpty()
    val locationText = locationState?.data.orEmpty()
    val teacherText = teacherState?.data.orEmpty()

    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = ComposeColor.Transparent,
        bottomBar = {
            Surface {
                Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)) {
                    if (saveState == AddEventViewModel.SaveState.FAILED) {
                        Text(stringResource(R.string.ade_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onRequestClose, enabled = !frozen) { Text(stringResource(R.string.ade_close)) }
                        Button(onClick = viewModel::createEvent, enabled = doneLiveData == true && !frozen) {
                            Text(stringResource(if (saving) R.string.ade_saving else R.string.ade_save))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .imePadding()
        ) {
            // Title
            val titleText = when {
                editEvent != null -> stringResource(R.string.ade_title_edit, editEvent.name.orEmpty())
                addSubjectMode -> stringResource(R.string.add_subject)
                else -> stringResource(R.string.ade_title)
            }
            SectionTitle(titleText)

            // Timetable picker
            val ttState = timetableState
            if (ttState?.state != DataState.STATE.FETCH_FAILED) {
                val ttSelected = ttState?.state == DataState.STATE.SUCCESS
                PickerRow(
                    iconRes = R.drawable.ic_timetable,
                    label = stringResource(R.string.ade_pick_timetable),
                    value = ttState?.data?.name,
                    isSelected = ttSelected,
                    onClick = { if (!frozen) onShowTimetablePicker() }
                )
            }

            // ── Content section ──
            SectionTitle(stringResource(R.string.ade_section_content))

            if (addSubjectMode || editEvent == null) {
                val isCustom = contentMode == AddEventViewModel.ContentMode.CUSTOM
                SegmentedToggle(
                    option1 = stringResource(R.string.ade_content_existing),
                    option2 = stringResource(R.string.ade_content_custom),
                    selectedFirst = !isCustom,
                    onSelectFirst = { if (!frozen) onShowExistingEventPicker() },
                    onSelectSecond = { if (!frozen && contentMode != AddEventViewModel.ContentMode.CUSTOM) {
                        viewModel.markEdited()
                        viewModel.setContentMode(AddEventViewModel.ContentMode.CUSTOM)
                    } }
                )
            }

            val isCustomContent = contentMode == AddEventViewModel.ContentMode.CUSTOM || addSubjectMode || editEvent != null
            if (isCustomContent) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        viewModel.markEdited()
                        viewModel.nameLiveData.value = it
                    },
                    enabled = !frozen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.xl, vertical = tokens.spacing.xs),
                    label = { Text(stringResource(R.string.ade_namehint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(tokens.radius.md)
                )
            }

            // ── Date section ──
            SectionTitle(stringResource(R.string.ade_section_date))

            if (editEvent == null) {
                val isSingle = dateMode == AddEventViewModel.DateMode.SINGLE
                SegmentedToggle(
                    option1 = stringResource(R.string.ade_date_single),
                    option2 = stringResource(R.string.ade_date_weekly),
                    selectedFirst = isSingle,
                    onSelectFirst = { if (!frozen && !isSingle) {
                        viewModel.markEdited()
                        viewModel.setDateMode(AddEventViewModel.DateMode.SINGLE)
                    } },
                    onSelectSecond = { if (!frozen && isSingle) {
                        viewModel.markEdited()
                        viewModel.setDateMode(AddEventViewModel.DateMode.WEEKLY)
                    } }
                )
            }

            val dateLabel = remember(dateMode, customDate, timeRange) {
                when (dateMode) {
                    AddEventViewModel.DateMode.SINGLE -> {
                        val state = customDate
                        if (state?.state == DataState.STATE.SUCCESS) {
                            val c = Calendar.getInstance()
                            c.timeInMillis = state.data ?: 0L
                            "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
                        } else null
                    }
                    AddEventViewModel.DateMode.WEEKLY -> {
                        val state = timeRange
                        if (state?.state == DataState.STATE.SUCCESS) {
                            formatWeeklyLabel(state.data)
                        } else null
                    }
                    else -> null
                }
            }
            PickerRow(
                iconRes = R.drawable.ic_baseline_date_range_24,
                label = stringResource(R.string.ade_section_date),
                value = dateLabel,
                isSelected = dateLabel != null,
                onClick = { if (!frozen) onShowDatePicker() },
                placeholder = if (dateMode == AddEventViewModel.DateMode.WEEKLY) {
                    stringResource(R.string.ade_pick_weekly_date)
                } else {
                    stringResource(R.string.ade_set_date)
                }
            )

            // ── Time section ──
            SectionTitle(stringResource(R.string.ade_section_time))

            val isPeriod = timeMode == AddEventViewModel.TimeMode.PERIOD
            SegmentedToggle(
                option1 = stringResource(R.string.ade_time_clock),
                option2 = stringResource(R.string.ade_time_period),
                selectedFirst = !isPeriod,
                onSelectFirst = { if (!frozen && isPeriod) {
                    viewModel.markEdited()
                    viewModel.setTimeMode(AddEventViewModel.TimeMode.CLOCK)
                } },
                onSelectSecond = { if (!frozen && !isPeriod) {
                    viewModel.markEdited()
                    viewModel.setTimeMode(AddEventViewModel.TimeMode.PERIOD)
                } }
            )

            val timeLabel = remember(timeMode, customTimePeriod, timeRange, timetableState) {
                when (timeMode) {
                    AddEventViewModel.TimeMode.CLOCK -> {
                        val state = customTimePeriod
                        if (state?.state == DataState.STATE.SUCCESS) {
                            val p = state.data
                            if (p != null) "${formatTime(p.from)} - ${formatTime(p.to)}" else null
                        } else null
                    }
                    AddEventViewModel.TimeMode.PERIOD -> {
                        val state = timeRange
                        if (state?.state == DataState.STATE.SUCCESS) {
                            val ct = state.data
                            val tt = timetableState?.data
                            if (ct != null && tt != null) {
                                val (start, end) = tt.transformCourseNumber(ct.period)
                                "第${start}-${end}节 (${formatTime(ct.period.from)} - ${formatTime(ct.period.to)})"
                            } else null
                        } else null
                    }
                    else -> null
                }
            }
            PickerRow(
                iconRes = R.drawable.ic_baseline_access_time_24,
                label = stringResource(R.string.ade_section_time),
                value = timeLabel,
                isSelected = timeLabel != null,
                onClick = { if (!frozen) onShowTimePicker() },
                placeholder = if (timeMode == AddEventViewModel.TimeMode.PERIOD) {
                    stringResource(R.string.ade_pick_time)
                } else {
                    stringResource(R.string.ade_pick_time_range)
                }
            )
            val selectedPeriod = if (isPeriod) timeRange?.data?.period else customTimePeriod?.data
            if (selectedPeriod == null || selectedPeriod.to.toMills() <= selectedPeriod.from.toMills()) {
                Text(
                    stringResource(if (selectedPeriod == null) R.string.ade_time_required else R.string.ade_time_invalid),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = tokens.spacing.xl),
                )
            }

            // ── Location ──
            OutlinedTextField(
                value = locationText,
                onValueChange = {
                    viewModel.markEdited()
                    viewModel.locationLiveData.value = DataState(it)
                },
                enabled = !frozen,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.xl, vertical = tokens.spacing.xs),
                label = { Text(stringResource(R.string.ade_optional_location)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                shape = RoundedCornerShape(tokens.radius.md)
            )

            // ── Teacher ──
            OutlinedTextField(
                value = teacherText,
                onValueChange = {
                    viewModel.markEdited()
                    viewModel.teacherLiveData.value = DataState(it)
                },
                enabled = !frozen,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.xl, vertical = tokens.spacing.xs),
                label = { Text(stringResource(R.string.ade_optional_teacher_note)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                shape = RoundedCornerShape(tokens.radius.md)
            )
        }
        } // end of scrollable Column
    } // end of Scaffold

// ── New reusable composables ──

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HitaTheme.tokens.spacing.xl,
                top = 28.dp,
                end = HitaTheme.tokens.spacing.xl,
                bottom = HitaTheme.tokens.spacing.sm
            )
    )
}

@Composable
private fun SegmentedToggle(
    option1: String,
    option2: String,
    selectedFirst: Boolean,
    onSelectFirst: () -> Unit,
    onSelectSecond: () -> Unit,
) {
    val shape = RoundedCornerShape(HitaTheme.tokens.radius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HitaTheme.tokens.spacing.xl, vertical = HitaTheme.tokens.spacing.xs)
            .height(40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (selectedFirst) Modifier
                        .shadow(2.dp, shape)
                        .background(MaterialTheme.colorScheme.surface, shape)
                    else Modifier
                        .background(ComposeColor.Transparent, shape)
                )
                .clickable { onSelectFirst() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = option1,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = if (selectedFirst) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (!selectedFirst) Modifier
                        .shadow(2.dp, shape)
                        .background(MaterialTheme.colorScheme.surface, shape)
                    else Modifier
                        .background(ComposeColor.Transparent, shape)
                )
                .clickable { onSelectSecond() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = option2,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = if (!selectedFirst) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PickerRow(
    iconRes: Int? = null,
    label: String,
    value: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    showCancel: Boolean = false,
    onCancel: (() -> Unit)? = null,
    placeholder: String? = null,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HitaTheme.tokens.spacing.xl, vertical = HitaTheme.tokens.spacing.xs)
            .height(48.dp)
            .clip(RoundedCornerShape(HitaTheme.tokens.radius.md))
            .border(1.dp, borderColor, RoundedCornerShape(HitaTheme.tokens.radius.md))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = HitaTheme.tokens.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(HitaTheme.tokens.spacing.sm))
        }
        Text(
            text = label,
            fontSize = 14.sp,
            color = fg,
            modifier = Modifier.weight(1f)
        )
        val displayValue = if (!value.isNullOrBlank()) value else (placeholder ?: "")
        Text(
            text = displayValue,
            fontSize = 14.sp,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (showCancel && onCancel != null) {
            Spacer(modifier = Modifier.width(HitaTheme.tokens.spacing.xs))
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_cancel_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatWeeklyLabel(courseTime: CourseTime?): String {
    if (courseTime == null) return ""
    val weeks = formatWeeks(courseTime.weeks)
    return if (weeks.isBlank()) {
        "周${courseTime.dow}"
    } else {
        "${weeks}周 周${courseTime.dow}"
    }
}

private fun formatWeeks(weeks: List<Int>): String {
    val set = weeks.toSet()
    val frags = mutableListOf<String>()
    for (s in set.sorted()) {
        if (set.contains(s - 1)) continue
        var ts = s
        while (set.contains(ts + 1)) {
            ts++
        }
        when (ts) {
            s -> frags.add("$s")
            s + 1 -> {
                frags.add("$s")
                frags.add("$ts")
            }
            else -> frags.add("$s-$ts")
        }
    }
    return frags.joinToString(", ")
}

private fun formatTime(t: TimeInDay): String {
    return String.format("%02d:%02d", t.hour, t.minute)
}
