package cn.limpu.hita.ui.resource

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog as ComposeAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.resource.CourseContributionOps
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.widgets.PopUpCalendarPicker
import cn.limpu.hita.utils.LogUtils
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class CourseContributionActivity : AppCompatActivity() {
    private val viewModel: CourseContributionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        super.onCreate(savedInstanceState)

        setContent {
            HitaComposeTheme() {
                CourseContributionScreen(
                    viewModel = viewModel,
                    repoName = intent.getStringExtra("repoName") ?: "",
                    courseName = intent.getStringExtra("courseName")
                        ?: intent.getStringExtra("repoName").orEmpty(),
                    courseCode = intent.getStringExtra("courseCode")
                        ?: intent.getStringExtra("repoName").orEmpty(),
                    initialRepoType = intent.getStringExtra("repoType") ?: "normal",
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    showModePicker = { labels, onPick ->
                        AlertDialog.Builder(this)
                            .setItems(labels.toTypedArray()) { _, which -> onPick(which) }
                            .show()
                    },
                    showCoursePicker = { labels, onPick ->
                        AlertDialog.Builder(this)
                            .setItems(labels.toTypedArray()) { _, which -> onPick(which) }
                            .show()
                    },
                    pickDateTime = { selectedDate, onPicked ->
                        PopUpCalendarPicker().setInitValue(selectedDate.timeInMillis)
                            .setOnConfirmListener(object : PopUpCalendarPicker.OnConfirmListener {
                                override fun onConfirm(c: Calendar) {
                                    val result = selectedDate.clone() as Calendar
                                    result.set(Calendar.YEAR, c.get(Calendar.YEAR))
                                    result.set(Calendar.MONTH, c.get(Calendar.MONTH))
                                    result.set(Calendar.DAY_OF_MONTH, c.get(Calendar.DAY_OF_MONTH))
                                    TimePickerDialog(
                                        this@CourseContributionActivity,
                                        { _, hour, minute ->
                                            result.set(Calendar.HOUR_OF_DAY, hour)
                                            result.set(Calendar.MINUTE, minute)
                                            onPicked(result)
                                        },
                                        selectedDate.get(Calendar.HOUR_OF_DAY),
                                        selectedDate.get(Calendar.MINUTE),
                                        DateFormat.is24HourFormat(this@CourseContributionActivity),
                                    ).show()
                                }
                            }).show(supportFragmentManager, "pick_date")
                    }
                )
            }
        }
    }
}

private enum class ContributionMode {
    NORMAL_TEACHER_REVIEW,
    NORMAL_SECTION_APPEND,
    MULTI_COURSE_REVIEW,
    MULTI_TEACHER_REVIEW,
}

private enum class CourseTargetMode {
    EXISTING,
    NEW,
}

private data class ContributionPreviewOwner(
    val draft: Any,
    val request: CourseContributionViewModel.PreviewRequest,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseContributionScreen(
    viewModel: CourseContributionViewModel,
    repoName: String,
    courseName: String,
    courseCode: String,
    initialRepoType: String,
    onBack: () -> Unit,
    showModePicker: (List<String>, (Int) -> Unit) -> Unit,
    showCoursePicker: (List<String>, (Int) -> Unit) -> Unit,
    pickDateTime: (Calendar, (Calendar) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val tokens = HitaTheme.tokens
    val structureState by viewModel.structureLiveData.observeAsState()
    val previewState by viewModel.previewLiveData.observeAsState()
    val submitState by viewModel.submitLiveData.observeAsState()
    val submitting by viewModel.submitInFlight.observeAsState(false)
    var repoType by rememberSaveable(initialRepoType) { mutableStateOf(initialRepoType) }
    var selectedMode by rememberSaveable { mutableStateOf<ContributionMode?>(null) }
    var selectedCourseName by rememberSaveable { mutableStateOf<String?>(null) }
    var courseTargetMode by rememberSaveable { mutableStateOf(CourseTargetMode.EXISTING) }
    var newCourseNameText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var selectedDate by rememberSaveable { mutableStateOf(Calendar.getInstance()) }
    var typeText by rememberSaveable { mutableStateOf("") }
    var courseText by rememberSaveable { mutableStateOf("") }
    var sectionText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var teacherText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var topicText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var normalTeacherText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var contentText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var authorNameText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var authorLinkText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var owner by remember { mutableStateOf<ContributionPreviewOwner?>(null) }
    // 光标移动不是改稿；任何影响实际提交内容的变化都会让旧预览失效。
    val draft = remember(
        repoName, courseCode, courseName, repoType, selectedMode, selectedCourseName,
        courseTargetMode, newCourseNameText.text, selectedDate.timeInMillis, sectionText.text,
        teacherText.text, topicText.text, normalTeacherText.text, contentText.text,
        authorNameText.text, authorLinkText.text,
    ) { Any() }
    val loading = structureState == null || submitting ||
        (owner != null && previewState?.request !== owner?.request)
    val previewDialog = previewState?.takeIf { result ->
        owner?.let { it.draft === draft && it.request === result.request } == true &&
            result.data.state == DataState.STATE.SUCCESS && !result.data.data?.markdown.isNullOrBlank()
    }

    fun modeLabel(mode: ContributionMode): String {
        return when (mode) {
            ContributionMode.NORMAL_TEACHER_REVIEW ->
                context.getString(R.string.course_contribution_mode_teacher_review)
            ContributionMode.NORMAL_SECTION_APPEND ->
                context.getString(R.string.course_contribution_mode_section_append)
            ContributionMode.MULTI_COURSE_REVIEW ->
                context.getString(R.string.course_contribution_mode_course_review)
            ContributionMode.MULTI_TEACHER_REVIEW ->
                context.getString(R.string.course_contribution_mode_multi_teacher_review)
        }
    }

    fun setMode(mode: ContributionMode) {
        selectedMode = mode
        typeText = modeLabel(mode)
    }

    fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(Unit) {
        viewModel.load(repoName)
    }

    LaunchedEffect(structureState) {
        val state = structureState ?: return@LaunchedEffect
        if (state.state == DataState.STATE.SUCCESS) {
            val summary = state.data ?: return@LaunchedEffect
            LogUtils.d("Loaded: repoType=${summary.repoType}, courses=${summary.courses.size}, teachers=${summary.teachers.size}")
            repoType = summary.repoType.ifBlank { repoType }
            if (repoType == "multi-project" && selectedMode == null) {
                val first = summary.courses.firstOrNull()
                selectedCourseName = first?.name
                courseText = first?.let { it.name.ifBlank { it.code } }.orEmpty()
                courseTargetMode = if (first == null) CourseTargetMode.NEW else CourseTargetMode.EXISTING
            }
            if (repoType != "multi-project" && normalTeacherText.text.isBlank()) {
                summary.teachers.firstOrNull()?.let {
                    normalTeacherText = TextFieldValue(it)
                }
            }
            if (selectedMode == null) {
                setMode(
                    if (repoType == "multi-project") {
                        ContributionMode.MULTI_COURSE_REVIEW
                    } else {
                        ContributionMode.NORMAL_SECTION_APPEND
                    }
                )
            }
        } else {
            showMessage(state.message ?: context.getString(R.string.course_resource_failed))
        }
    }

    fun discardPreview(result: CourseContributionViewModel.PreviewResult) {
        if (owner?.request === result.request) owner = null
        viewModel.dismissPreview(result.request)
    }

    LaunchedEffect(previewState, draft) {
        val result = previewState ?: return@LaunchedEffect
        val activeOwner = owner
        if (activeOwner == null || activeOwner.request !== result.request || activeOwner.draft !== draft) {
            discardPreview(result)
            showMessage(context.getString(R.string.course_contribution_preview_expired))
        } else if (result.data.state != DataState.STATE.SUCCESS || result.data.data?.markdown.isNullOrBlank()) {
            discardPreview(result)
            showMessage(result.data.message ?: context.getString(
                if (result.data.state == DataState.STATE.SUCCESS) R.string.course_contribution_preview_empty
                else R.string.course_resource_failed,
            ))
        }
    }

    LaunchedEffect(submitState) {
        val state = submitState ?: return@LaunchedEffect
        if (state.state == DataState.STATE.SUCCESS) {
            showMessage(context.getString(R.string.course_contribution_success, state.data ?: ""))
        } else {
            showMessage(state.message ?: context.getString(R.string.course_resource_failed))
        }
    }

    fun submit() {
        if (loading) return
        val mode = selectedMode ?: run {
            showMessage(context.getString(R.string.course_contribution_pick_type))
            return
        }
        val content = contentText.text.trim()
        val authorName = authorNameText.text.trim()
        val authorLink = authorLinkText.text.trim()

        if (authorName.isBlank()) {
            showMessage(context.getString(R.string.course_contribution_fill_author))
            return
        }

        val author = mapOf(
            "name" to authorName,
            "link" to authorLink,
            "date" to formatDate(selectedDate),
        )
        var ops = JSONArray()
        when (mode) {
            ContributionMode.NORMAL_TEACHER_REVIEW -> {
                if (content.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_fill_content))
                    return
                }
                val teacher = normalTeacherText.text.trim()
                if (teacher.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_pick_teacher))
                    return
                }
                ops.put(JSONObject().apply {
                    put("op", "add_lecturer_review")
                    put("lecturer_name", teacher)
                    put("content", content)
                    put("author", JSONObject(author))
                })
            }
            ContributionMode.NORMAL_SECTION_APPEND -> {
                val section = sectionText.text.trim().ifBlank {
                    structureState?.data?.appendTargets?.firstOrNull().orEmpty()
                }
                if (section.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_pick_section))
                    return
                }
                if (content.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_fill_content))
                    return
                }
                ops.put(JSONObject().apply {
                    put("op", "add_section_item")
                    put("title", section)
                    put("content", content)
                    put("author", JSONObject(author))
                })
            }
            ContributionMode.MULTI_COURSE_REVIEW -> {
                val isNewCourse = courseTargetMode == CourseTargetMode.NEW
                val targetCourseName = if (isNewCourse) {
                    newCourseNameText.text.trim()
                } else {
                    selectedCourseName?.trim().orEmpty()
                }
                if (targetCourseName.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_pick_course))
                    return
                }
                if (isNewCourse && structureState?.data?.courses.orEmpty().any {
                        it.name.trim().equals(targetCourseName, ignoreCase = true)
                    }
                ) {
                    showMessage(context.getString(R.string.course_contribution_course_exists))
                    return
                }
                if (content.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_fill_content))
                    return
                }
                ops = JSONArray(
                    CourseContributionOps.courseSection(
                        courseName = targetCourseName,
                        sectionTitle = topicText.text.trim().ifBlank {
                            if (isNewCourse) "课程概况" else "课程评价"
                        },
                        content = content,
                        author = author,
                        createCourse = isNewCourse,
                    )
                )
            }
            ContributionMode.MULTI_TEACHER_REVIEW -> {
                val isNewCourse = courseTargetMode == CourseTargetMode.NEW
                val targetCourseName = if (isNewCourse) {
                    newCourseNameText.text.trim()
                } else {
                    selectedCourseName?.trim().orEmpty()
                }
                if (targetCourseName.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_pick_course))
                    return
                }
                if (isNewCourse && structureState?.data?.courses.orEmpty().any {
                        it.name.trim().equals(targetCourseName, ignoreCase = true)
                    }
                ) {
                    showMessage(context.getString(R.string.course_contribution_course_exists))
                    return
                }
                val teacher = teacherText.text.trim()
                if (teacher.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_pick_teacher))
                    return
                }
                if (content.isBlank()) {
                    showMessage(context.getString(R.string.course_contribution_fill_content))
                    return
                }
                ops = JSONArray(
                    CourseContributionOps.courseTeacherReview(
                        courseName = targetCourseName,
                        teacherName = teacher,
                        content = content,
                        author = author,
                        createCourse = isNewCourse,
                    )
                )
            }
        }

        owner = ContributionPreviewOwner(draft, viewModel.preview(repoName, courseCode, courseName, repoType, ops))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.course_contribution_title),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = stringResource(R.string.course_contribution_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.rotate(180f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(tokens.spacing.lg)
            ) {
                Text(
                    text = "$courseCode · $courseName",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                SelectField(
                    label = stringResource(R.string.course_contribution_type),
                    value = typeText,
                    onClick = {
                        val modes = if (repoType == "multi-project") {
                            listOf(
                                ContributionMode.MULTI_COURSE_REVIEW,
                                ContributionMode.MULTI_TEACHER_REVIEW,
                            )
                        } else {
                            listOf(
                                ContributionMode.NORMAL_TEACHER_REVIEW,
                                ContributionMode.NORMAL_SECTION_APPEND,
                            )
                        }
                        showModePicker(modes.map { modeLabel(it) }) { which ->
                            setMode(modes[which])
                        }
                    },
                    modifier = Modifier.padding(top = tokens.spacing.lg)
                )

                if (
                    selectedMode == ContributionMode.MULTI_COURSE_REVIEW ||
                    selectedMode == ContributionMode.MULTI_TEACHER_REVIEW
                ) {
                    CourseTargetSelector(
                        selected = courseTargetMode,
                        onSelected = { courseTargetMode = it },
                        modifier = Modifier.padding(top = tokens.spacing.md),
                    )
                    if (courseTargetMode == CourseTargetMode.EXISTING) {
                        SelectField(
                            label = stringResource(R.string.course_contribution_course),
                            value = courseText,
                            onClick = {
                                val courses = structureState?.data?.courses.orEmpty()
                                val labels = courses.map { it.name.ifBlank { it.code } }
                                if (courses.isNotEmpty()) {
                                    showCoursePicker(labels) { which ->
                                        selectedCourseName = courses[which].name
                                        courseText = labels[which]
                                        teacherText = TextFieldValue("")
                                        sectionText = TextFieldValue("")
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = tokens.spacing.md)
                        )
                    } else {
                        FormInput(
                            label = stringResource(R.string.course_contribution_new_course_name),
                            value = newCourseNameText,
                            onValueChange = { newCourseNameText = it },
                            hint = stringResource(R.string.course_contribution_new_course_hint),
                            modifier = Modifier.padding(top = tokens.spacing.md),
                        )
                    }
                }

                if (selectedMode == ContributionMode.NORMAL_SECTION_APPEND) {
                    FormInput(
                        label = stringResource(R.string.course_contribution_section),
                        value = sectionText,
                        onValueChange = { sectionText = it },
                        hint = stringResource(R.string.course_contribution_section_hint),
                        modifier = Modifier.padding(top = tokens.spacing.md)
                    )
                }

                if (selectedMode == ContributionMode.MULTI_TEACHER_REVIEW) {
                    FormInput(
                        label = stringResource(R.string.course_contribution_teacher),
                        value = teacherText,
                        onValueChange = { teacherText = it },
                        hint = stringResource(R.string.course_contribution_teacher_hint),
                        modifier = Modifier.padding(top = tokens.spacing.md)
                    )
                }

                if (selectedMode == ContributionMode.MULTI_COURSE_REVIEW) {
                    FormInput(
                        label = stringResource(R.string.course_contribution_topic),
                        value = topicText,
                        onValueChange = { topicText = it },
                        modifier = Modifier.padding(top = tokens.spacing.md)
                    )
                }

                if (selectedMode == ContributionMode.NORMAL_TEACHER_REVIEW) {
                    FormInput(
                        label = stringResource(R.string.course_contribution_teacher),
                        value = normalTeacherText,
                        onValueChange = { normalTeacherText = it },
                        modifier = Modifier.padding(top = tokens.spacing.md)
                    )
                }

                FormInput(
                    label = stringResource(R.string.course_contribution_content),
                    value = contentText,
                    onValueChange = { contentText = it },
                    minHeight = 160.dp,
                    singleLine = false,
                    modifier = Modifier.padding(top = tokens.spacing.md)
                )

                FormInput(
                    label = stringResource(R.string.course_contribution_author_name),
                    value = authorNameText,
                    onValueChange = { authorNameText = it },
                    modifier = Modifier.padding(top = tokens.spacing.md)
                )

                FormInput(
                    label = stringResource(R.string.course_contribution_author_link),
                    value = authorLinkText,
                    onValueChange = { authorLinkText = it },
                    modifier = Modifier.padding(top = tokens.spacing.md)
                )

                SelectField(
                    label = stringResource(R.string.course_contribution_author_date),
                    value = formatDate(selectedDate),
                    onClick = {
                        pickDateTime(selectedDate) { picked ->
                            selectedDate = picked
                        }
                    },
                    modifier = Modifier.padding(top = tokens.spacing.md)
                )

                Button(
                    onClick = { submit() },
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(top = tokens.spacing.xl)
                ) {
                    Text(text = stringResource(R.string.course_contribution_preview))
                }

                Spacer(modifier = Modifier.height(tokens.spacing.xl))
            }
        }

        if (loading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        previewDialog?.let { snapshot ->
            val preview = requireNotNull(snapshot.data.data)
            ComposeAlertDialog(
                onDismissRequest = { discardPreview(snapshot) },
                title = { Text(stringResource(R.string.course_contribution_preview_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.course_contribution_preview_repository, snapshot.request.repoName))
                        if (preview.changedFiles.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.course_contribution_preview_files,
                                    preview.changedFiles.joinToString("、"),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                        if (preview.warnings.isNotEmpty()) {
                            Text(
                                text = preview.warnings.joinToString("\n"),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = tokens.spacing.sm),
                            )
                        }
                        Text(
                            text = preview.markdown,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(top = tokens.spacing.md)
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (owner?.draft !== draft || !viewModel.confirmPreview(snapshot)) {
                            discardPreview(snapshot)
                            showMessage(context.getString(R.string.course_contribution_preview_expired))
                        } else {
                            owner = null
                        }
                    }) {
                        Text(stringResource(R.string.course_contribution_confirm_submit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { discardPreview(snapshot) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseTargetSelector(
    selected: CourseTargetMode,
    onSelected: (CourseTargetMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        CourseTargetMode.EXISTING to stringResource(R.string.course_contribution_existing_course),
        CourseTargetMode.NEW to stringResource(R.string.course_contribution_new_course),
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SelectField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FieldSurface(
        label = label,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = HitaTheme.tokens.spacing.xs)
        )
    }
}

@Composable
private fun FormInput(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    singleLine: Boolean = true,
) {
    FieldSurface(label = label, modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(if (minHeight > 0.dp) minHeight else 44.dp)
                .padding(top = HitaTheme.tokens.spacing.xs),
            decorationBox = { inner ->
                Box {
                    if (value.text.isBlank() && hint.isNotBlank()) {
                        Text(
                            text = hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
private fun FieldSurface(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val tokens = HitaTheme.tokens
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(tokens.radius.xl),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.md)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            content()
        }
    }
}

private fun formatDate(date: Calendar): String {
    return String.format(
        Locale.getDefault(),
        "%04d-%02d",
        date.get(Calendar.YEAR),
        date.get(Calendar.MONTH) + 1,
    )
}
