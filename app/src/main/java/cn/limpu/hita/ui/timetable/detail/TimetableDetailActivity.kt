package cn.limpu.hita.ui.timetable.detail

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.timetable.share.TimetableShareActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaCoursePaletteDialog
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaCourseColor
import cn.limpu.hita.ui.event.add.PopupAddEvent
import cn.limpu.hita.ui.widgets.PopUpCalendarPicker
import cn.limpu.hita.ui.widgets.PopUpTimePeriodPicker
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.FileProviderUtils
import cn.limpu.hita.utils.ShareUtils
import cn.limpu.hita.utils.TextTools
import com.limpu.style.ThemeTools
import com.limpu.style.widgets.PopUpEditText
import com.limpu.style.widgets.PopUpText
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class TimetableDetailActivity : HiltBaseActivity<ComposeViewBinding>() {

    protected val viewModel: TimetableDetailViewModel by viewModels()
    private var selectedSubjectIds by mutableStateOf(emptySet<String>())
    private var isExporting by mutableStateOf(false)
    private var lastExportSuccess: Boolean? by mutableStateOf(null)

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    override fun initViews() {
        bindLiveData()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                TimetableDetailScreen(
                    viewModel = viewModel,
                    selectedSubjectIds = selectedSubjectIds,
                    isExporting = isExporting,
                    lastExportSuccess = lastExportSuccess,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onEditName = { editTimetableName() },
                    onEditDate = { editStartDate() },
                    onShareTimetable = {
                        viewModel.timetableLiveData.value?.id?.let { id ->
                            startActivity(Intent(this, TimetableShareActivity::class.java)
                                .putExtra(TimetableShareActivity.EXTRA_TIMETABLE_ID, id))
                        }
                    },
                    onExport = {
                        binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        isExporting = true
                        lastExportSuccess = null
                        viewModel.exportToIcs()
                    },
                    onOpenTeacherResource = { teacher ->
                        ActivityUtils.startCourseResourceSearchActivity(
                            getThis(),
                            teacher,
                            ActivityUtils.CourseResourceMode.VIEW,
                        )
                    },
                    onOpenTeacherHomepage = { teacher ->
                        ActivityUtils.startTeacherHomepageSearch(getThis(), teacher)
                    },
                    onOpenSubject = { subject ->
                        ActivityUtils.startSubjectActivity(getThis(), subject.id)
                    },
                    onToggleSubjectSelection = { subject ->
                        if (subject.type == TermSubject.TYPE.TAG) return@TimetableDetailScreen
                        selectedSubjectIds = if (selectedSubjectIds.contains(subject.id)) {
                            selectedSubjectIds - subject.id
                        } else {
                            selectedSubjectIds + subject.id
                        }
                    },
                    onStartSubjectSelection = { subject ->
                        if (subject.type != TermSubject.TYPE.TAG) {
                            selectedSubjectIds = selectedSubjectIds + subject.id
                        }
                    },
                    onPickSubjectColor = { subject, color ->
                        viewModel.startChangeSubjectColor(subject.id, color)
                    },
                    onClearSelection = { selectedSubjectIds = emptySet() },
                    onDeleteSelected = { subjects ->
                        val toDelete = subjects.filter { selectedSubjectIds.contains(it.id) }
                        if (toDelete.isNotEmpty()) {
                            viewModel.startDeleteSubjects(toDelete)
                            selectedSubjectIds = emptySet()
                        }
                    },
                    onAddSubject = {
                        PopupAddEvent(true)
                            .setInitTimetable(viewModel.timetableLiveData.value)
                            .show(supportFragmentManager, "add_subject")
                    },
                    onResetColors = {
                        PopUpText().setTitle(R.string.dialog_title_random_allocate)
                            .setOnConfirmListener(object : PopUpText.OnConfirmListener {
                                override fun OnConfirm() {
                                    viewModel.startResetSubjectColors()
                                }
                            }).show(supportFragmentManager, "sure")
                    },
                    onEditPeriod = { period, position ->
                        PopUpTimePeriodPicker().setInitialValue(period.from, period.to)
                            .setDialogTitle(R.string.pick_time_period)
                            .setOnDialogConformListener(object :
                                PopUpTimePeriodPicker.OnDialogConformListener {
                                override fun onClick(timePeriodInDay: TimePeriodInDay) {
                                    viewModel.startChangeTimetableStructure(timePeriodInDay, position)
                                }
                            }).show(supportFragmentManager, "pick")
                    }
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
            } else {
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                Toast.makeText(getThis(), "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun editTimetableName() {
        viewModel.timetableLiveData.value?.let {
            PopUpEditText().setTitle(R.string.notifi_curriculum_set_name)
                .setText(it.name)
                .setOnConfirmListener(object : PopUpEditText.OnConfirmListener {
                    override fun OnConfirm(text: String) {
                        if (text.isBlank()) return
                        it.name = text
                        viewModel.startSaveTimetableInfo()
                    }
                }).show(supportFragmentManager, "pick")
        }
    }

    private fun editStartDate() {
        PopUpCalendarPicker().setInitValue(viewModel.timetableLiveData.value?.startTime?.time)
            .setOnConfirmListener(object : PopUpCalendarPicker.OnConfirmListener {
                override fun onConfirm(c: Calendar) {
                    viewModel.timetableLiveData.value?.let {
                        viewModel.startChangeTimetableStartTime(c.timeInMillis)
                    }
                }
            }).show(supportFragmentManager, "pick")
    }

    override fun onStart() {
        super.onStart()
        intent.getStringExtra("id")?.let {
            viewModel.startRefresh(it)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TimetableDetailScreen(
    viewModel: TimetableDetailViewModel,
    selectedSubjectIds: Set<String>,
    isExporting: Boolean,
    lastExportSuccess: Boolean?,
    onBack: () -> Unit,
    onEditName: () -> Unit,
    onEditDate: () -> Unit,
    onShareTimetable: () -> Unit,
    onExport: () -> Unit,
    onOpenTeacherResource: (String?) -> Unit,
    onOpenTeacherHomepage: (String) -> Unit,
    onOpenSubject: (TermSubject) -> Unit,
    onToggleSubjectSelection: (TermSubject) -> Unit,
    onStartSubjectSelection: (TermSubject) -> Unit,
    onPickSubjectColor: (TermSubject, Int) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: (List<TermSubject>) -> Unit,
    onAddSubject: () -> Unit,
    onResetColors: () -> Unit,
    onEditPeriod: (TimePeriodInDay, Int) -> Unit
) {
    val tokens = HitaTheme.tokens
    val canEditCourseColors = HitaTheme.preferenceStyle == ThemeTools.STYLE.CLASSIC
    val timetable by viewModel.timetableLiveData.observeAsState()
    val subjects by viewModel.subjectsLiveData.observeAsState(emptyList())
    val teachers by viewModel.teacherInfoLiveData.observeAsState(emptyList())
    val selectionMode = selectedSubjectIds.isNotEmpty()
    var colorSubject by remember { mutableStateOf<TermSubject?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = timetable?.name ?: stringResource(R.string.title_timetable_manager),
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
                TextButton(onClick = onShareTimetable, enabled = timetable != null) {
                    Text(stringResource(R.string.timetable_share_action))
                }
                IconButton(onClick = onExport, enabled = !isExporting && timetable != null) {
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

        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedSubjectIds.size,
                subjects = subjects,
                onClearSelection = onClearSelection,
                onDeleteSelected = onDeleteSelected
            )
        }

        val currentTimetable = timetable
        if (currentTimetable == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return
        }

        val calendar = remember(currentTimetable.startTime.time) {
            Calendar.getInstance().apply { timeInMillis = currentTimetable.startTime.time }
        }
        val dateText = TextTools.getNormalDateText(androidx.compose.ui.platform.LocalContext.current, calendar)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = tokens.spacing.xl)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    DetailInfoCard(
                        title = currentTimetable.name.orEmpty(),
                        subtitle = stringResource(R.string.timetable_name),
                        icon = R.drawable.ic_timetable,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                            .clickable(onClick = onEditName)
                    )
                    DetailInfoCard(
                        title = dateText,
                        subtitle = stringResource(R.string.start_date_of_curriculum),
                        icon = R.drawable.ic_baseline_timetable_24,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                            .clickable(onClick = onEditDate)
                    )
                }
            }

            val visibleTeachers = teachers.filter { !it.name.isNullOrBlank() }
            if (visibleTeachers.isNotEmpty()) {
                item {
                    SectionTitle(text = stringResource(R.string.title_teachers))
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = tokens.spacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                    ) {
                        items(visibleTeachers) { teacher ->
                            TeacherCard(
                                teacher = teacher,
                                onClick = { onOpenTeacherResource(teacher.name) },
                                onLongClick = {
                                    teacher.name?.takeIf { it.isNotBlank() }?.let(onOpenTeacherHomepage)
                                }
                            )
                        }
                    }
                }
            }

            item {
                SectionTitleWithActions(
                    text = stringResource(R.string.title_subjects),
                    onAdd = onAddSubject,
                    onResetColors = onResetColors,
                    showResetColors = canEditCourseColors,
                )
            }
            items(subjects, key = { it.id }) { subject ->
                if (subject.type == TermSubject.TYPE.TAG) {
                    SubjectGroupTitle(text = subject.name)
                } else {
                    SubjectCard(
                        subject = subject,
                        viewModel = viewModel,
                        selected = selectedSubjectIds.contains(subject.id),
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) onToggleSubjectSelection(subject) else onOpenSubject(subject)
                        },
                        onLongClick = { onStartSubjectSelection(subject) },
                        onColorClick = { colorSubject = subject },
                        canEditColor = canEditCourseColors,
                    )
                }
            }

            item {
                SectionTitle(text = stringResource(R.string.timetable_structure_label))
            }
            item {
                ScheduleStructureCard(
                    periods = currentTimetable.scheduleStructure,
                    onEditPeriod = onEditPeriod
                )
            }
        }
    }

    colorSubject?.let { subject ->
        HitaCoursePaletteDialog(
            selectedColor = hitaCourseColor(
                courseKey = subject.id.ifBlank { subject.name },
                storedColor = subject.color,
            ),
            onSelected = { color ->
                onPickSubjectColor(subject, color.toArgb())
                colorSubject = null
            },
            onDismiss = { colorSubject = null },
        )
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    subjects: List<TermSubject>,
    onClearSelection: () -> Unit,
    onDeleteSelected: (List<TermSubject>) -> Unit
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
                onClick = { onDeleteSelected(subjects) },
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

@Composable
private fun DetailInfoCard(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HitaTheme.tokens.spacing.xxl, top = HitaTheme.tokens.spacing.lg, bottom = HitaTheme.tokens.spacing.sm),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SectionTitleWithActions(
    text: String,
    onAdd: () -> Unit,
    onResetColors: () -> Unit,
    showResetColors: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier
                .weight(1f)
                .padding(start = HitaTheme.tokens.spacing.xxl),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onAdd) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_add_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showResetColors) {
            IconButton(onClick = onResetColors, modifier = Modifier.padding(end = HitaTheme.tokens.spacing.lg)) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_color_lens_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeacherCard(
    teacher: TeacherInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .padding(vertical = tokens.spacing.sm)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = tokens.spacing.lg,
                vertical = tokens.spacing.md
            )
        ) {
            Text(
                text = teacher.name.orEmpty(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = teacher.subjectName.orEmpty(),
                modifier = Modifier
                    .padding(top = tokens.spacing.xs)
                    .alpha(0.5f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SubjectGroupTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(
            start = 30.dp,
            top = HitaTheme.tokens.spacing.lg,
            end = HitaTheme.tokens.spacing.lg,
            bottom = 6.dp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubjectCard(
    subject: TermSubject,
    viewModel: TimetableDetailViewModel,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onColorClick: (Int) -> Unit,
    canEditColor: Boolean,
) {
    val tokens = HitaTheme.tokens
    val progressLiveData = remember(subject.id) { viewModel.getSubjectProgress(subject.id) }
    val progressPair by progressLiveData.observeAsState(0 to 1)
    val progress = remember(progressPair) {
        val total = progressPair.second.coerceAtLeast(1)
        (progressPair.first / total.toFloat()).coerceIn(0f, 1f)
    }
    val courseColor = hitaCourseColor(
        courseKey = subject.id.ifBlank { subject.name },
        storedColor = subject.color,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = tokens.spacing.lg, top = tokens.spacing.lg, bottom = tokens.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subject.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subject.school?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.unknown_department),
                    modifier = Modifier.padding(top = tokens.spacing.xs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.sm, end = tokens.spacing.lg)
                        .height(8.dp),
                    color = courseColor,
                    trackColor = courseColor.copy(alpha = 0.2f)
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable(enabled = !selectionMode && canEditColor) {
                        onColorClick(courseColor.toArgb())
                    },
                contentAlignment = Alignment.Center
            ) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        color = courseColor.copy(alpha = 0.8f)
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun ScheduleStructureCard(
    periods: List<TimePeriodInDay>,
    onEditPeriod: (TimePeriodInDay, Int) -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.lg),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = tokens.spacing.sm)) {
            periods.forEachIndexed { index, period ->
                StructureRow(
                    index = index,
                    period = period,
                    showDivider = index != periods.lastIndex,
                    onClick = { onEditPeriod(period, index) }
                )
            }
        }
    }
}

@Composable
private fun StructureRow(
    index: Int,
    period: TimePeriodInDay,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(
                start = tokens.spacing.lg,
                top = 10.dp,
                end = tokens.spacing.lg,
                bottom = 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.schedule_list_item_pattern, index + 1),
                modifier = Modifier
                    .weight(1f)
                    .alpha(0.3f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = period.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
