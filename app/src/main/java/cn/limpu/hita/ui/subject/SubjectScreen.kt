package cn.limpu.hita.ui.subject

import android.text.TextUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.CourseNameUtils
import cn.limpu.hita.utils.TimeTools
import java.text.DecimalFormat
import java.util.Calendar
import java.util.regex.Pattern

internal data class SubjectScreenActions(
    val onBack: () -> Unit,
    val onOpenTimetable: (Timetable) -> Unit,
    val onTeacherLongClick: () -> Unit,
    val onCreditClick: () -> Unit,
    val onContribute: () -> Unit,
    val onCandidateClick: (CourseResourceItem) -> Unit,
    val onExternalClick: (ExternalCourseItem) -> Unit,
    val onExternalBack: (ExternalCourseItem) -> Unit,
    val onExternalEntryClick: (String, ExternalResourceEntry) -> Unit,
    val onToggleCourseExpand: () -> Unit,
    val onCourseClick: (EventItem) -> Unit,
    val onCourseLongClick: (EventItem) -> Unit,
    val onAddCourse: () -> Unit,
    val onClearSelection: () -> Unit,
    val onUpdateCourses: (SubjectResourceKey, List<EventItem>, Boolean) -> Unit,
    val onDeleteCourses: (SubjectResourceKey, List<EventItem>) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun SubjectScreen(
    state: SubjectScreenState,
    resources: SubjectResourceState,
    renderer: SubjectMarkdownRenderer,
    actions: SubjectScreenActions,
) {
    val tokens = HitaTheme.tokens
    val currentSubject = state.subject
    val timetable = state.timetable
    val teachers = state.teachers
    val subjectMetaSupported = state.subjectMetaSupported
    val visibleClasses = state.visibleClasses
    val courseExpanded = state.courseExpanded
    val selectedEventIds = state.selectedEventIds
    val courseProgress = state.courseProgress
    val selectionMode = selectedEventIds.isNotEmpty()
    val realClasses = state.classes
    val readmeUiState = resources.readme
    val hoaCandidates = resources.candidates
    val candidateExpanded = resources.candidateExpanded
    val candidateReadmeStates = resources.candidateReadmes
    val externalResources = resources.externalCourses
    val externalExpanded = resources.externalExpanded
    val externalBrowseStates = resources.directories
    val campusSourceHint = when (state.campus) {
        EASToken.Campus.BENBU -> stringResource(R.string.subject_resource_benbu_hint)
        EASToken.Campus.WEIHAI -> stringResource(R.string.subject_resource_weihai_hint)
        else -> ""
    }
    val onBack = actions.onBack
    val onOpenTimetable = actions.onOpenTimetable
    val onTeacherLongClick = actions.onTeacherLongClick
    val onCreditClick = actions.onCreditClick
    val onContribute = actions.onContribute
    val onCandidateClick = actions.onCandidateClick
    val onExternalClick = actions.onExternalClick
    val onExternalBack = actions.onExternalBack
    val onExternalEntryClick = actions.onExternalEntryClick
    val onToggleCourseExpand = actions.onToggleCourseExpand
    val onCourseClick = actions.onCourseClick
    val onCourseLongClick = actions.onCourseLongClick
    val onAddCourse = actions.onAddCourse
    val onClearSelection = actions.onClearSelection
    var editScope by remember(resources.key) { mutableStateOf<SubjectBatchEditScope?>(null) }
    var deleteScope by remember(resources.key) { mutableStateOf<SubjectBatchDeleteScope?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = currentSubject?.name?.let { CourseNameUtils.normalize(it) ?: it }.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
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
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedEventIds.size,
                onClearSelection = onClearSelection,
                onEditSelected = { editScope = SubjectBatchEditScope.SELECTED },
                onDeleteSelected = { deleteScope = SubjectBatchDeleteScope.SELECTED },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = tokens.spacing.xl)
        ) {
            item {
                timetable?.let { tt ->
                    TimetableInfoCard(timetable = tt, onClick = { onOpenTimetable(tt) })
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    ProgressInfoCard(
                        progress = courseProgress,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                    )
                    if (subjectMetaSupported && currentSubject != null) {
                        SubjectInfoCard(
                            title = subjectCategoryDisplay(currentSubject),
                            subtitle = stringResource(R.string.subject_type),
                            icon = R.drawable.ic_bx_type,
                            modifier = Modifier
                                .weight(1f)
                                .height(144.dp)
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = tokens.spacing.lg,
                            top = tokens.spacing.sm,
                            end = tokens.spacing.lg
                        ),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    SubjectInfoCard(
                        title = teachers.joinToString(" ").ifBlank { stringResource(R.string.none) },
                        subtitle = stringResource(R.string.subject_teacher),
                        icon = R.drawable.ic_bx_teacher,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                            .combinedClickable(onClick = {}, onLongClick = onTeacherLongClick)
                    )
                    if (subjectMetaSupported && currentSubject != null) {
                        SubjectInfoCard(
                            title = subjectCreditKey(currentSubject),
                            subtitle = stringResource(R.string.subject_credit),
                            icon = R.drawable.ic_bx_credit,
                            modifier = Modifier
                                .weight(1f)
                                .height(144.dp)
                                .clickable(onClick = onCreditClick)
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onContribute,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = stringResource(R.string.course_resource_contribute), fontWeight = FontWeight.Bold)
                }
            }
            item {
                ResourceSection(
                    readmeUiState = readmeUiState,
                    campusSourceHint = campusSourceHint,
                    hoaCandidates = hoaCandidates,
                    candidateExpanded = candidateExpanded,
                    candidateReadmeStates = candidateReadmeStates,
                    renderer = renderer,
                    onCandidateClick = onCandidateClick,
                )
            }
            if (externalResources.isNotEmpty()) {
                item {
                    ExternalResourceSection(
                        externalResources = externalResources,
                        externalExpanded = externalExpanded,
                        externalBrowseStates = externalBrowseStates,
                        onExternalClick = onExternalClick,
                        onExternalBack = onExternalBack,
                        onExternalEntryClick = onExternalEntryClick,
                    )
                }
            }
            item {
                AllCoursesSection(
                    visibleClasses = visibleClasses,
                    courseExpanded = courseExpanded,
                    selectedEventIds = selectedEventIds,
                    onToggleCourseExpand = onToggleCourseExpand,
                    onCourseClick = onCourseClick,
                    onCourseLongClick = onCourseLongClick,
                    onAddCourse = onAddCourse,
                    onEditAll = { editScope = SubjectBatchEditScope.ALL },
                    onDeleteAll = { deleteScope = SubjectBatchDeleteScope.ALL },
                )
            }
        }
    }
        editScope?.let { scope ->
            val targets = if (scope == SubjectBatchEditScope.ALL) {
                realClasses
            } else {
                realClasses.filter { it.id in selectedEventIds }
            }
            if (targets.isNotEmpty()) {
                SubjectBatchEditDialog(
                    scope = scope,
                    events = targets,
                    timetable = timetable,
                    onDismiss = { editScope = null },
                    onApply = { place, teacher, time, slotTimes ->
                        val (edited, skippedTime) = applySubjectBatchEdit(
                            events = targets.map(EventItem::subjectEditCopy),
                            timetable = timetable,
                            place = place,
                            teacher = teacher,
                            time = time,
                            slotTimes = slotTimes,
                        )
                        resources.key?.let { actions.onUpdateCourses(it, edited, skippedTime) }
                        editScope = null
                    },
                )
            }
        }
        deleteScope?.let { scope ->
            val count = if (scope == SubjectBatchDeleteScope.ALL) {
                realClasses.size
            } else {
                realClasses.count { it.id in selectedEventIds }
            }
            if (count > 0) {
                SubjectBatchDeleteDialog(
                    scope = scope,
                    count = count,
                    onDismiss = { deleteScope = null },
                    onConfirm = {
                        val targets = if (scope == SubjectBatchDeleteScope.ALL) realClasses
                            else realClasses.filter { it.id in selectedEventIds }
                        resources.key?.let { actions.onDeleteCourses(it, targets) }
                        deleteScope = null
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onEditSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
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
                onClick = onEditSelected,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(end = tokens.spacing.sm),
            ) {
                Text(text = stringResource(R.string.subject_batch_edit_selected))
            }
            Button(
                onClick = onDeleteSelected,
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
private fun TimetableInfoCard(timetable: Timetable, onClick: () -> Unit) {
    val tokens = HitaTheme.tokens
    val season = TimeTools.getSeason(timetable.startTime.time)
    val container = when (season) {
        TimeTools.SEASON.SPRING -> colorResource(R.color.spring)
        TimeTools.SEASON.SUMMER -> colorResource(R.color.summer)
        TimeTools.SEASON.AUTUMN -> colorResource(R.color.autumn)
        else -> colorResource(R.color.winter)
    }
    val textColor = when (season) {
        TimeTools.SEASON.SPRING -> colorResource(R.color.spring_text)
        TimeTools.SEASON.SUMMER -> colorResource(R.color.summer_text)
        TimeTools.SEASON.AUTUMN -> colorResource(R.color.autumn_text)
        else -> colorResource(R.color.winter_text)
    }
    val icon = when (season) {
        TimeTools.SEASON.SPRING -> R.drawable.season_spring
        TimeTools.SEASON.SUMMER -> R.drawable.season_summer
        TimeTools.SEASON.AUTUMN -> R.drawable.season_autumn
        else -> R.drawable.season_winter
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.belonging_timetable),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp
            )
            Surface(
                color = container,
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = tokens.spacing.md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = timetable.name ?: stringResource(R.string.default_timetable_name),
                        color = textColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressInfoCard(progress: Int, modifier: Modifier) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$progress%",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.progress),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            LinearProgressIndicator(
                progress = { progress / 100f },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.md)
            )
        }
    }
}

@Composable
private fun SubjectInfoCard(
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
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
private fun ResourceSection(
    readmeUiState: SubjectReadmeUiState,
    campusSourceHint: String,
    hoaCandidates: List<CourseResourceItem>,
    candidateExpanded: Map<String, Boolean>,
    candidateReadmeStates: Map<String, CandidateReadmeState>,
    renderer: SubjectMarkdownRenderer,
    onCandidateClick: (CourseResourceItem) -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Text(
                text = stringResource(R.string.course_resource_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            hoaCandidates.forEach { item ->
                val key = itemKey(item)
                UnifiedResourceCard(
                    title = item.courseName.takeIf { it.isNotBlank() } ?: item.repoName,
                    subtitle = listOfNotNull(
                        item.courseCode.takeIf { it.isNotBlank() },
                        item.repoType.takeIf { it.isNotBlank() },
                    ).joinToString("  ·  "),
                    source = "HOA",
                    sourceColor = MaterialTheme.colorScheme.primary,
                    expanded = candidateExpanded[key] == true,
                    onClick = { onCandidateClick(item) },
                )
                if (candidateExpanded[key] == true) {
                    val state = candidateReadmeStates[key] ?: CandidateReadmeState(loading = true)
                    CandidateReadmeContent(
                        state = state,
                        renderer = renderer,
                    )
                }
            }
            if (campusSourceHint.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.sm)
                ) {
                    Text(
                        text = campusSourceHint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(tokens.spacing.md)
                    )
                }
            }
            when (readmeUiState) {
                SubjectReadmeUiState.Loading -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = tokens.spacing.md)
                            .size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.course_readme_loading),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = tokens.spacing.sm)
                    )
                }
                is SubjectReadmeUiState.Message -> {
                    Text(
                        text = readmeUiState.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = tokens.spacing.sm)
                    )
                }
                is SubjectReadmeUiState.Success -> {
                    readmeUiState.source.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = stringResource(R.string.course_readme_source, it),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = tokens.spacing.sm)
                        )
                    }
                    MarkdownText(
                        markdown = readmeUiState.markdown,
                        source = readmeUiState.source,
                        renderer = renderer,
                        modifier = Modifier.padding(top = tokens.spacing.sm)
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateReadmeContent(
    state: CandidateReadmeState,
    renderer: SubjectMarkdownRenderer,
) {
    val tokens = HitaTheme.tokens
    when {
        state.loading -> CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(tokens.spacing.md)
                .size(22.dp)
        )
        state.markdown.isNotBlank() -> MarkdownText(
            markdown = state.markdown,
            source = state.source,
            renderer = renderer,
            modifier = Modifier.padding(
                start = tokens.spacing.sm,
                top = tokens.spacing.sm,
                end = tokens.spacing.sm,
                bottom = tokens.spacing.md
            )
        )
        else -> Text(
            text = state.message.ifBlank { "加载失败" },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(tokens.spacing.md)
        )
    }
}

@Composable
private fun ExternalResourceSection(
    externalResources: List<ExternalCourseItem>,
    externalExpanded: Map<String, Boolean>,
    externalBrowseStates: Map<String, ExternalBrowseUiState>,
    onExternalClick: (ExternalCourseItem) -> Unit,
    onExternalBack: (ExternalCourseItem) -> Unit,
    onExternalEntryClick: (String, ExternalResourceEntry) -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Text(
                text = stringResource(R.string.external_resource_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            externalResources.forEach { item ->
                val key = itemKey(item)
                UnifiedResourceCard(
                    title = item.courseName,
                    subtitle = item.category,
                    source = when (item.source) {
                        ResourceSource.HITCS -> "HITCS"
                        ResourceSource.FIREWORKS -> "薪火"
                    },
                    sourceColor = colorResource(
                        when (item.source) {
                            ResourceSource.HITCS -> R.color.subject3
                            ResourceSource.FIREWORKS -> R.color.subject4
                        }
                    ),
                    expanded = externalExpanded[key] == true,
                    onClick = { onExternalClick(item) },
                )
                if (externalExpanded[key] == true) {
                    val state = externalBrowseStates[key]
                    ExternalBrowseContent(
                        item = item,
                        key = key,
                        state = state,
                        onBack = { onExternalBack(item) },
                        onEntryClick = { entry -> onExternalEntryClick(key, entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedResourceCard(
    title: String,
    subtitle: String,
    source: String,
    sourceColor: Color,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(tokens.radius.lg),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = tokens.spacing.sm)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(tokens.spacing.lg)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = tokens.spacing.xs)
                    )
                }
            }
            SourceChip(
                text = source,
                color = sourceColor,
                modifier = Modifier.padding(start = tokens.spacing.sm)
            )
            Icon(
                painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

@Composable
private fun SourceChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val tokens = HitaTheme.tokens
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = CircleShape,
        modifier = modifier
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = tokens.spacing.sm, vertical = tokens.spacing.xs)
        )
    }
}

@Composable
private fun ExternalBrowseContent(
    item: ExternalCourseItem,
    key: String,
    state: ExternalBrowseUiState?,
    onBack: () -> Unit,
    onEntryClick: (ExternalResourceEntry) -> Unit,
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.sm, vertical = tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = state?.breadcrumb != item.courseName) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(180f)
            )
        }
        Text(
            text = state?.breadcrumb ?: item.courseName,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    when {
        state == null || state.loading -> CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(tokens.spacing.md)
                .size(22.dp)
        )
        state.message.isNotBlank() -> Text(
            text = state.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(tokens.spacing.md)
        )
        else -> state.entries.forEach { entry ->
            ExternalEntryRow(entry = entry, onClick = { onEntryClick(entry) })
        }
    }
}

@Composable
private fun ExternalEntryRow(entry: ExternalResourceEntry, onClick: () -> Unit) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(tokens.radius.md),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xs)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(tokens.spacing.md)
        ) {
            Icon(
                painter = painterResource(
                    if (entry.isDir) R.drawable.ic_baseline_menu_24 else R.drawable.ic_baseline_cloud_download_24
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = tokens.spacing.md)
            ) {
                Text(
                    text = entry.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.isDir && entry.size > 0) {
                    Text(
                        text = formatFileSize(entry.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AllCoursesSection(
    visibleClasses: List<EventItem>,
    courseExpanded: Boolean,
    selectedEventIds: Set<String>,
    onToggleCourseExpand: () -> Unit,
    onCourseClick: (EventItem) -> Unit,
    onCourseLongClick: (EventItem) -> Unit,
    onAddCourse: () -> Unit,
    onEditAll: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.subject_title_all_courses),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onEditAll) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_edit_24),
                        contentDescription = stringResource(R.string.subject_batch_edit_all),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDeleteAll) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_delete_24),
                        contentDescription = stringResource(R.string.subject_batch_delete_all),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onAddCourse) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_add_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = tokens.spacing.sm)
            ) {
                visibleClasses.forEach { event ->
                    if (event.type == EventItem.TYPE.TAG) {
                        ExpandCourseChip(
                            expanded = courseExpanded,
                            onClick = onToggleCourseExpand
                        )
                    } else {
                        CourseChip(
                            event = event,
                            selected = selectedEventIds.contains(event.id),
                            selectionMode = selectedEventIds.isNotEmpty(),
                            onClick = { onCourseClick(event) },
                            onLongClick = { onCourseLongClick(event) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CourseChip(
    event: EventItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val passed = TimeTools.passed(event.to)
    val calendar = Calendar.getInstance().apply { timeInMillis = event.from.time }
    val context = LocalContext.current
    val text = TimeTools.getDateString(context, calendar, true, TimeTools.TTY_REPLACE)
    Surface(
        color = if (passed) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(28.dp)
                )
            } else if (!passed) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExpandCourseChip(expanded: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.outlineVariant,
        shape = CircleShape,
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (expanded) 180f else 0f)
        )
    }
}

@Composable
private fun MarkdownText(
    markdown: String,
    source: String,
    renderer: SubjectMarkdownRenderer,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context -> renderer.createView(context, textColor) },
        update = { textView -> renderer.render(textView, markdown, source, textColor) },
    )
}

private fun subjectCategoryDisplay(subject: SubjectDetails): String {
    val line1 = subject.selectCategory?.takeIf { it.isNotBlank() } ?: "无"
    val line2 = subject.field?.takeIf { it.isNotBlank() } ?: "无"
    val line3 = subject.nature?.takeIf { it.isNotBlank() } ?: when (subject.type) {
        TermSubject.TYPE.COM_A -> "必修 · 考试"
        TermSubject.TYPE.COM_B -> "必修 · 考查"
        TermSubject.TYPE.OPT_A -> "选修 · 专选"
        TermSubject.TYPE.OPT_B -> "选修 · 任选"
        TermSubject.TYPE.MOOC -> "MOOC"
        TermSubject.TYPE.TAG -> "标签"
    }
    return listOf(line1, line2, line3).joinToString("\n")
}

private fun subjectCreditKey(subject: SubjectDetails): String {
    val pt = Pattern.compile("[0-9]*[.]*[0-9]*")
    val matcher = pt.matcher(subject.credit.toString())
    val df = DecimalFormat("0.0")
    if (matcher.find()) {
        val c = matcher.group(0) ?: return "0.0"
        if (TextUtils.isEmpty(c)) return "0.0"
        return df.format(java.lang.Double.valueOf(c))
    }
    return "0.0"
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
