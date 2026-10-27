package cn.limpu.hita.ui.eas.catalog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.CoursePlanPreviewMapper
import cn.limpu.hita.data.repository.CoursePlanPreviewDiagnostics
import cn.limpu.hita.data.repository.CourseSelectionDraft
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.main.timetable.ReadOnlyTimetableWeek
import cn.limpu.hita.utils.TermNameFormatter
import com.limpu.component.data.DataState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoursePlanPreviewScreen(
    viewModel: ShenzhenCourseCatalogViewModel,
    onBack: () -> Unit,
    onShowInTimetable: () -> Unit,
    onHideFromTimetable: () -> Unit,
    onClearDraft: () -> Unit
) {
    val termDisplay by viewModel.selectedTermDisplay.observeAsState()
    val term = termDisplay?.toTermItem()
    val selectedState by viewModel.selectedCoursesLiveData.observeAsState()
    val startDateState by viewModel.startDateLiveData.observeAsState()
    val scheduleState by viewModel.scheduleStructureLiveData.observeAsState()
    val draft by viewModel.courseSelectionDraftLiveData.observeAsState()

    CoursePlanPreviewContent(
        term = term,
        selectedState = selectedState,
        startDateState = startDateState,
        scheduleState = scheduleState,
        draft = draft,
        onBack = onBack,
        onShowInTimetable = onShowInTimetable,
        onHideFromTimetable = onHideFromTimetable,
        onClearDraft = onClearDraft,
        onRetryDependencies = viewModel::retryCoursePlanPreviewDependencies,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoursePlanPreviewContent(
    term: TermItem?,
    selectedState: DataState<List<ShenzhenCourseCatalogItem>>?,
    startDateState: DataState<java.util.Calendar>?,
    scheduleState: DataState<MutableList<TimePeriodInDay>>?,
    draft: CourseSelectionDraft?,
    onBack: () -> Unit,
    onShowInTimetable: () -> Unit,
    onHideFromTimetable: () -> Unit,
    onClearDraft: () -> Unit,
    onRetryDependencies: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    val selectedCourses = selectedState?.data.orEmpty()
        .distinctBy { course -> course.taskId.ifBlank { course.id } }
    val selectedIDs = remember(selectedCourses) {
        selectedCourses.mapTo(hashSetOf()) { course -> course.taskId.ifBlank { course.id } }
    }
    val manualCourses = draft?.courses.orEmpty().filterNot { course ->
        course.taskId.ifBlank { course.id } in selectedIDs
    }
    val startDate = startDateState?.data?.timeInMillis
    val schedule = scheduleState?.data.orEmpty()
    val projection = remember(selectedCourses, manualCourses, startDate, schedule) {
        if (startDate == null || schedule.isEmpty()) {
            null
        } else {
            CoursePlanPreviewMapper.map(
                selectedCourses = selectedCourses,
                draftCourses = manualCourses,
                termStartMillis = startDate,
                schedule = schedule
            )
        }
    }
    val effectiveCourses = remember(selectedCourses, manualCourses) {
        (selectedCourses + manualCourses)
            .distinctBy { course -> course.taskId.ifBlank { course.id } }
    }
    val debugReport = remember(term?.id, effectiveCourses, projection) {
        projection?.takeIf { it.incompleteCourses.isNotEmpty() }?.let {
            CoursePlanPreviewDiagnostics.report(
                termID = term?.id.orEmpty(),
                allCourses = effectiveCourses,
                projection = it
            )
        }
    }
    var selectedWeek by remember(term?.id) { mutableIntStateOf(1) }
    var selectedEvent by remember { mutableStateOf<EventItem?>(null) }
    var showIncompleteCourses by remember { mutableStateOf(false) }

    LaunchedEffect(projection?.maxWeek) {
        selectedWeek = selectedWeek.coerceIn(1, projection?.maxWeek ?: 1)
    }
    LaunchedEffect(debugReport) {
        if (BuildConfig.DEBUG && debugReport != null) {
            Log.d(COURSE_PLAN_PREVIEW_LOG_TAG, debugReport)
        }
    }

    val metadataLoading = listOf(startDateState, scheduleState).any { state ->
        state == null || state.state == DataState.STATE.NOTHING || state.state == DataState.STATE.LOADING
    }
    val metadataError = previewDependencyError(startDateState, scheduleState)
    val selectedCoursesReady = selectedState?.state == DataState.STATE.SUCCESS
    val canShowInTimetable = selectedCoursesReady && projection?.courses?.isNotEmpty() == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("预览课表", maxLines = 1)
                        term?.let {
                            Text(
                                text = TermNameFormatter.fullTermName(it),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_close_24),
                            contentDescription = "关闭预览"
                        )
                    }
                },
                actions = {
                    if (!projection?.incompleteCourses.isNullOrEmpty()) {
                        TextButton(onClick = { showIncompleteCourses = true }) {
                            Text("时间异常 ${projection?.incompleteCourses?.size}")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            CoursePlanPreviewActions(
                canShowInTimetable = canShowInTimetable,
                projectionEnabled = draft?.projectionEnabled == true,
                hasManualCourses = manualCourses.isNotEmpty(),
                onShowInTimetable = onShowInTimetable,
                onHideFromTimetable = onHideFromTimetable,
                onClearDraft = onClearDraft
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CoursePlanPreviewSummary(
                selectedCount = selectedCourses.size,
                manualCount = manualCourses.size,
                selectedState = selectedState
            )
            when {
                metadataLoading -> CoursePlanPreviewLoading(
                    courses = selectedCourses + manualCourses
                )
                metadataError != null -> CoursePlanPreviewError(
                    message = metadataError,
                    courses = selectedCourses + manualCourses,
                    onRetry = onRetryDependencies
                )
                projection == null -> CoursePlanPreviewError(
                    message = "本学期日期或作息为空，暂时无法绘制周课表。",
                    courses = selectedCourses + manualCourses,
                    onRetry = onRetryDependencies
                )
                projection.courses.isEmpty() -> CoursePlanPreviewError(
                    message = "课程没有可解析的上课时间。课程仍保留在预览清单中。",
                    courses = selectedCourses + manualCourses,
                    onRetry = onRetryDependencies
                )
                else -> {
                    WeekSelector(
                        week = selectedWeek,
                        maxWeek = projection.maxWeek,
                        onPrevious = { selectedWeek = (selectedWeek - 1).coerceAtLeast(1) },
                        onNext = { selectedWeek = (selectedWeek + 1).coerceAtMost(projection.maxWeek) }
                    )
                    ReadOnlyTimetableWeek(
                        startDate = projection.weekStartMillis(selectedWeek),
                        events = projection.eventsForWeek(selectedWeek),
                        scheduleStructure = projection.schedule,
                        onPreviousWeek = {
                            selectedWeek = (selectedWeek - 1).coerceAtLeast(1)
                        },
                        onNextWeek = {
                            selectedWeek = (selectedWeek + 1).coerceAtMost(projection.maxWeek)
                        },
                        onEventClick = { selectedEvent = it }
                    )
                }
            }
        }
    }

    selectedEvent?.let { event ->
        CoursePlanEventDialog(event = event, onDismiss = { selectedEvent = null })
    }
    if (showIncompleteCourses) {
        IncompleteCoursesDialog(
            courses = projection?.incompleteCourses.orEmpty(),
            schedulePeriodCount = projection?.schedule?.size ?: 0,
            debugReport = debugReport,
            onCopyDebugReport = {
                val report = debugReport ?: return@IncompleteCoursesDialog
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("HITA 选课预览诊断", report))
            },
            onDismiss = { showIncompleteCourses = false }
        )
    }
}

@Composable
private fun CoursePlanPreviewSummary(
    selectedCount: Int,
    manualCount: Int,
    selectedState: DataState<List<ShenzhenCourseCatalogItem>>?
) {
    val tokens = HitaTheme.tokens
    val shape = RoundedCornerShape(tokens.radius.lg)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
            .hitaGlassCardModifier(shape),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder(),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.md)) {
            Text(
                "${selectedCount + manualCount} 门课程 · 教务已选 $selectedCount · 手动加入 $manualCount",
                fontWeight = FontWeight.SemiBold
            )
            if (selectedState?.state != DataState.STATE.SUCCESS) {
                Text(
                    text = selectedState?.message ?: "正在读取本学期已选课程",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = tokens.spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun CoursePlanPreviewLoading(courses: List<ShenzhenCourseCatalogItem>) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator()
        Text(
            "正在读取第一教学周日期与作息…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        PreviewCourseList(courses)
    }
}

@Composable
private fun CoursePlanPreviewError(
    message: String,
    courses: List<ShenzhenCourseCatalogItem>,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("暂时无法绘制周课表", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text("重试日期与作息")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
            Text("课程清单仍然可用", fontWeight = FontWeight.SemiBold)
        }
        items(courses.distinctBy { course -> course.taskId.ifBlank { course.id } }) { course ->
            PreviewCourseRow(course)
        }
    }
}

@Composable
private fun PreviewCourseList(courses: List<ShenzhenCourseCatalogItem>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(courses.distinctBy { course -> course.taskId.ifBlank { course.id } }) { course ->
            PreviewCourseRow(course)
        }
    }
}

@Composable
private fun PreviewCourseRow(course: ShenzhenCourseCatalogItem) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(course.courseName, fontWeight = FontWeight.Medium)
            Text(
                listOf(course.teacher, course.schedule).filter(String::isNotBlank).joinToString(" · ")
                    .ifBlank { "上课时间信息不完整" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WeekSelector(
    week: Int,
    maxWeek: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onPrevious, enabled = week > 1) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = "上一周",
                modifier = Modifier.rotate(180f)
            )
        }
        Text("第 $week 周", fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onNext, enabled = week < maxWeek) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = "下一周"
            )
        }
    }
}

@Composable
private fun CoursePlanPreviewActions(
    canShowInTimetable: Boolean,
    projectionEnabled: Boolean,
    hasManualCourses: Boolean,
    onShowInTimetable: () -> Unit,
    onHideFromTimetable: () -> Unit,
    onClearDraft: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onShowInTimetable,
                enabled = canShowInTimetable,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (projectionEnabled) "更新时间表并打开" else "在时间表中显示")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (projectionEnabled) {
                    OutlinedButton(onClick = onHideFromTimetable, modifier = Modifier.weight(1f)) {
                        Text("从时间表隐藏")
                    }
                }
                if (hasManualCourses) {
                    TextButton(onClick = onClearDraft, modifier = Modifier.weight(1f)) {
                        Text("清空手动草稿")
                    }
                }
            }
        }
    }
}

@Composable
private fun CoursePlanEventDialog(event: EventItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text(event.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                event.teacher?.takeIf(String::isNotBlank)?.let { Text("教师：$it") }
                event.place?.takeIf(String::isNotBlank)?.let { Text("地点：$it") }
                Text("第 ${event.fromNumber}-${event.fromNumber + event.lastNumber - 1} 节")
            }
        }
    )
}

@Composable
private fun IncompleteCoursesDialog(
    courses: List<ShenzhenCourseCatalogItem>,
    schedulePeriodCount: Int,
    debugReport: String?,
    onCopyDebugReport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            if (BuildConfig.DEBUG && debugReport != null) {
                TextButton(onClick = onCopyDebugReport) { Text("复制调试信息") }
            }
        },
        title = { Text("未完整排入网格的课程") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(courses) { course ->
                    Column {
                        Text(course.courseName, fontWeight = FontWeight.Medium)
                        CoursePlanPreviewDiagnostics.issueReasons(
                            course,
                            schedulePeriodCount
                        ).forEach { reason ->
                            Text(
                                reason,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    )
}

private const val COURSE_PLAN_PREVIEW_LOG_TAG = "CoursePlanPreview"

private fun previewDependencyError(
    startDateState: DataState<*>?,
    scheduleState: DataState<*>?
): String? {
    val states = listOf(
        startDateState to "第一教学周日期读取失败",
        scheduleState to "作息读取失败"
    )
    return states.firstNotNullOfOrNull { (state, fallback) ->
        if (
            state != null && state.state !in setOf(
                DataState.STATE.NOTHING,
                DataState.STATE.LOADING,
                DataState.STATE.SUCCESS
            )
        ) {
            state.message ?: fallback
        } else {
            null
        }
    }
}
