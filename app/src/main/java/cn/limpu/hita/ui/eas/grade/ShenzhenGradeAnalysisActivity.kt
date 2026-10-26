package cn.limpu.hita.ui.eas.grade

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysis
import cn.limpu.hita.data.model.eas.ShenzhenGradeCourse
import cn.limpu.hita.data.model.eas.ShenzhenGradeStatus
import cn.limpu.hita.data.model.eas.ShenzhenHistoricalFailureReport
import cn.limpu.hita.data.model.eas.ShenzhenTeacherFailureRate
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.TermUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpu.component.data.DataState
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class ShenzhenGradeAnalysisActivity :
    EASActivity<ShenzhenGradeAnalysisViewModel, ComposeViewBinding>() {

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TASK_NUMBER = "task_number"
        private const val EXTRA_COURSE_CODE = "course_code"
        private const val EXTRA_COURSE_NAME = "course_name"
        private const val EXTRA_TERM_CODE = "term_code"

        fun intent(context: Context, course: ShenzhenCourseCatalogItem, term: TermItem?): Intent =
            Intent(context, ShenzhenGradeAnalysisActivity::class.java).apply {
                putExtra(EXTRA_TASK_ID, course.taskId)
                putExtra(EXTRA_TASK_NUMBER, course.taskNumber)
                putExtra(EXTRA_COURSE_CODE, course.courseCode)
                putExtra(EXTRA_COURSE_NAME, course.courseName)
                putExtra(EXTRA_TERM_CODE, term?.getCode().orEmpty())
            }
    }

    override val viewModel: ShenzhenGradeAnalysisViewModel by viewModels()
    override val autoLaunchWebLoginForSessionRecovery = false
    private var allTerms: List<TermItem> = emptyList()
    private var externalCourseOpened = false
    private var peerComparisonDialog by mutableStateOf<PeerComparisonDialogState?>(null)

    override fun initViewBinding(): ComposeViewBinding = ComposeViewBinding(ComposeView(this))

    override fun initViews() {
        super.initViews()
        bindState()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                GradeScreen(
                    viewModel = viewModel,
                    onBack = {
                        if (viewModel.selectedCourse.value != null && !externalCourseOpened) {
                            viewModel.closeCourse()
                        } else finish()
                    },
                    onRefresh = {
                        if (viewModel.selectedCourse.value != null) viewModel.retryAnalysis()
                        else refresh()
                    },
                    onSelectTerm = { showTermPicker() },
                    onConnectWeb = { connectWebSession() },
                    peerComparisonDialog = peerComparisonDialog,
                    onCompareTeachers = { showPeerTeacherComparison() },
                    onDismissComparison = { peerComparisonDialog = null },
                    onRetryComparison = { retryPeerTeacherComparison() }
                )
            }
        }
        openExternalCourseIfPresent()
    }

    private fun bindState() {
        viewModel.terms.observe(this) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                allTerms = state.data.orEmpty()
                viewModel.reconcileTerms(allTerms)
                resetSessionRetryState()
            }
        }
        viewModel.courses.observe(this) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                resetSessionRetryState()
            }
        }
        viewModel.analysis.observe(this) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                resetSessionRetryState()
            }
        }
        viewModel.peerTeacherComparison.observe(this) { state ->
            val dialog = peerComparisonDialog ?: return@observe
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    peerComparisonDialog = dialog.copy(
                        loading = false,
                        report = state.data,
                        error = null
                    )
                    resetSessionRetryState()
                }
                DataState.STATE.NOT_LOGGED_IN -> {
                    peerComparisonDialog = dialog.copy(
                        loading = false,
                        error = state.message ?: "请先连接深圳 Web 教务"
                    )
                }
                DataState.STATE.FETCH_FAILED -> {
                    peerComparisonDialog = dialog.copy(
                        loading = false,
                        error = state.message ?: "同课程教师数据查询失败"
                    )
                }
                DataState.STATE.NOTHING -> Unit
                else -> {
                    peerComparisonDialog = dialog.copy(
                        loading = false,
                        error = state.message ?: "同课程教师数据暂不可用"
                    )
                }
            }
        }
    }

    override fun refresh() {
        if (viewModel.selectedCourse.value != null) viewModel.retryAnalysis()
        else viewModel.refreshTerms()
    }

    private fun openExternalCourseIfPresent() {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (taskId.isBlank()) return
        externalCourseOpened = true
        viewModel.openCourse(
            ShenzhenGradeCourse(
                taskId = taskId,
                taskNumber = intent.getStringExtra(EXTRA_TASK_NUMBER).orEmpty(),
                courseCode = intent.getStringExtra(EXTRA_COURSE_CODE).orEmpty(),
                courseName = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty().ifBlank { "个人成绩明细" },
                termCode = intent.getStringExtra(EXTRA_TERM_CODE).orEmpty()
            )
        )
    }

    private fun showTermPicker() {
        if (allTerms.isEmpty()) return
        val names = allTerms.map(TermNameFormatter::fullTermName).toTypedArray()
        val selected = allTerms.indexOfFirst { it.yearCode == viewModel.selectedTerm.value?.yearCode && it.termCode == viewModel.selectedTerm.value?.termCode }
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择学期")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                viewModel.selectTerm(allTerms[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun connectWebSession() {
        ActivityUtils.showEasVerifyWindow<Activity>(
            this,
            easRepository,
            preferredCampus = EASToken.Campus.SHENZHEN,
            onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                override fun onSuccess(window: PopUpLoginEAS) {
                    window.dismiss()
                    refresh()
                }

                override fun onFailed(window: PopUpLoginEAS) {
                    Toast.makeText(
                        this@ShenzhenGradeAnalysisActivity,
                        "需要深圳 Web 教务会话",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showPeerTeacherComparison() {
        val course = viewModel.selectedCourse.value ?: return
        peerComparisonDialog = PeerComparisonDialogState(
            courseName = course.courseName,
            loading = true
        )
        if (!viewModel.loadPeerTeacherComparison()) {
            peerComparisonDialog = peerComparisonDialog?.copy(
                loading = false,
                error = "请先选择课程与学期"
            )
        }
    }

    private fun retryPeerTeacherComparison() {
        peerComparisonDialog = peerComparisonDialog?.copy(loading = true, error = null)
        viewModel.retryPeerTeacherComparison()
    }
}

private data class PeerComparisonDialogState(
    val courseName: String,
    val loading: Boolean = false,
    val report: ShenzhenHistoricalFailureReport? = null,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeScreen(
    viewModel: ShenzhenGradeAnalysisViewModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTerm: () -> Unit,
    onConnectWeb: () -> Unit,
    peerComparisonDialog: PeerComparisonDialogState?,
    onCompareTeachers: () -> Unit,
    onDismissComparison: () -> Unit,
    onRetryComparison: () -> Unit
) {
    val selectedCourse by viewModel.selectedCourse.observeAsState()
    val selectedTermDisplay by viewModel.selectedTermDisplay.observeAsState()
    val selectedTerm = selectedTermDisplay?.toTermItem()
    val termsState by viewModel.terms.observeAsState()
    val coursesState by viewModel.courses.observeAsState()
    val analysisState by viewModel.analysis.observeAsState()
    val activeState = if (selectedCourse != null) analysisState else coursesState ?: termsState
    val loading = activeState == null || activeState?.state == DataState.STATE.NOTHING

    peerComparisonDialog?.let { state ->
        PeerTeacherComparisonDialog(
            state = state,
            onDismiss = onDismissComparison,
            onRetry = onRetryComparison
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
    ) {
        TopAppBar(
            title = {
                Text(
                    selectedCourse?.courseName ?: "个人成绩明细",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = "返回",
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            actions = {
                IconButton(onClick = onRefresh) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(
                        painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                        contentDescription = "刷新"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        when {
            activeState?.state == DataState.STATE.NOT_LOGGED_IN -> StateCard(
                message = activeState?.message ?: "请先连接深圳 Web 教务",
                action = "连接 Web 教务",
                onAction = onConnectWeb
            )
            activeState?.state == DataState.STATE.FETCH_FAILED -> StateCard(
                message = activeState?.message ?: "数据加载失败",
                action = "重试",
                onAction = onRefresh
            )
            selectedCourse != null && analysisState?.state == DataState.STATE.SUCCESS ->
                AnalysisContent(
                    analysis = requireNotNull(analysisState?.data)
                )
            selectedCourse == null &&
                termsState?.state == DataState.STATE.SUCCESS &&
                selectedTerm == null -> StateCard(
                    message = "没有可用学期",
                    action = "刷新",
                    onAction = onRefresh
                )
            selectedCourse == null && coursesState?.state == DataState.STATE.SUCCESS ->
                CourseList(
                    term = selectedTerm,
                    courses = coursesState?.data.orEmpty(),
                    onSelectTerm = onSelectTerm,
                    onCourseClick = viewModel::openCourse
                )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun CourseList(
    term: TermItem?,
    courses: List<ShenzhenGradeCourse>,
    onSelectTerm: () -> Unit,
    onCourseClick: (ShenzhenGradeCourse) -> Unit
) {
    val tokens = HitaTheme.tokens
    val scoredCourses = courses.mapNotNull { course ->
        val credits = course.credits?.takeIf { it > 0.0 } ?: return@mapNotNull null
        val score = course.myScore?.takeIf { it in 0.0..100.0 } ?: return@mapNotNull null
        credits to score
    }
    val includedCredits = scoredCourses.sumOf { it.first }
    val weightedAverage = scoredCourses
        .takeIf { it.isNotEmpty() }
        ?.let { items ->
            items.sumOf { (credits, score) -> credits * score } / items.sumOf { it.first }
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
    ) {
        item {
            GradeCard {
                Text("成绩汇总", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("加权均分", weightedAverage?.let(::format) ?: "--")
                    Metric("课程数", courses.size.toString())
                    Metric("计入学分", format(includedCredits))
                }
                if (scoredCourses.isEmpty() && courses.isNotEmpty()) {
                    Text(
                        "教务暂未返回个人数字成绩，暂时无法计算加权均分和计入学分。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onSelectTerm, modifier = Modifier.fillMaxWidth()) {
                Text(term?.let(TermNameFormatter::fullTermName) ?: "选择学期")
            }
            Text(
                "已公布课程可查看分项得分；提前可见和已选课程会保留在列表中。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = tokens.spacing.sm)
            )
        }
        if (courses.isEmpty()) {
            item { StateCard("该学期没有可分析的课程", null, null) }
        }
        items(courses, key = { "${it.taskId}-${it.courseName}" }) { course ->
            val canOpenDetails = course.recordId.isNotBlank()
            val shape = RoundedCornerShape(tokens.radius.lg)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (canOpenDetails) 1f else .72f)
                    .hitaGlassCardModifier(shape)
                    .clickable(enabled = canOpenDetails) { onCourseClick(course) },
                shape = shape,
                colors = hitaGlassCardColors(),
                border = hitaGlassCardBorder()
            ) {
                Column(Modifier.padding(tokens.spacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            course.courseName,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        StatusPill(course.status)
                    }
                    Text(
                        listOf(course.courseCode, course.teacher).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    course.myScore?.let {
                        Text("我的成绩 ${format(it)}", color = MaterialTheme.colorScheme.primary)
                    }
                    if (canOpenDetails) {
                        Text(
                            "查看分项",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                        )
                    } else {
                        Text(
                            if (course.status == ShenzhenGradeStatus.EARLY) {
                                "分项将在教务正式公布成绩后开放"
                            } else {
                                "暂时没有可查看的成绩分项"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: ShenzhenGradeStatus) {
    val (text, color) = when (status) {
        ShenzhenGradeStatus.PUBLISHED -> "已公布" to MaterialTheme.colorScheme.primary
        ShenzhenGradeStatus.EARLY -> "提前可见" to MaterialTheme.colorScheme.tertiary
        ShenzhenGradeStatus.SELECTED -> "已选" to MaterialTheme.colorScheme.secondary
    }
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}

@Composable
private fun AnalysisContent(
    analysis: ShenzhenGradeAnalysis
) {
    PersonalGradeContent(analysis)
}

@Composable
private fun PersonalGradeContent(analysis: ShenzhenGradeAnalysis) {
    val tokens = HitaTheme.tokens
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
    ) {
        item {
            GradeCard {
                Text("个人分项成绩", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "以下数据来自教务系统，可用于核对各分项得分及最终折算结果。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                analysis.myScore?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "按分项权重折算 ${format(it)} 分",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        items(analysis.myComponents) { component ->
            GradeCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(component.name, fontWeight = FontWeight.SemiBold)
                    Text("占比 ${format(component.weight)}%")
                }
                Text("${component.score?.let(::format) ?: "--"} / ${format(component.fullScore)}")
            }
        }
        item {
            Text(
                "折算规则：每项先按满分换算为百分制，再乘以该项权重；总权重不等于 100% 时归一化。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun OverviewTab(
    analysis: ShenzhenGradeAnalysis,
    onCompareTeachers: () -> Unit
) {
    val tokens = HitaTheme.tokens
    LazyColumn(
        contentPadding = PaddingValues(tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
    ) {
        item {
            OutlinedButton(onClick = onCompareTeachers, modifier = Modifier.fillMaxWidth()) {
                Text("查看同学期各教师成绩对比")
            }
        }
        item {
            GradeCard {
                Text("班级统计", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("人数", analysis.students.size.toString())
                    Metric("平均", format(analysis.mean))
                    Metric("中位数", format(analysis.median))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("最高", format(analysis.maximum))
                    Metric("最低", format(analysis.minimum))
                    Metric("标准差", format(analysis.standardDeviation))
                }
                Spacer(Modifier.height(12.dp))
                Text("挂科 ${analysis.failCount} 人（${format(analysis.failRate)}%）")
                if (analysis.excludedIncompleteStudentCount > 0) {
                    Text(
                        "${analysis.excludedIncompleteStudentCount} 人含空白分项，按 0 分计入",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        item {
            GradeCard {
                Text("我的位置", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (analysis.myScore == null) {
                    Text("暂时无法在匿名成绩中定位你的记录")
                } else {
                    Text(
                        "${format(analysis.myScore)} 分 · 第 ${analysis.myRank}/${analysis.students.size} 名 · ${format(analysis.percentile ?: 0.0)} 百分位",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (analysis.identityMatchCount > 1) {
                        Text(
                            "有 ${analysis.identityMatchCount} 条同分记录，个人分项定位可能不唯一。",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        item {
            GradeCard {
                Text("分数分布", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val max = analysis.bands.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                analysis.bands.forEach { band ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(band.label, modifier = Modifier.width(58.dp), fontSize = 12.sp)
                        LinearProgressIndicator(
                            progress = { band.count.toFloat() / max },
                            modifier = Modifier.weight(1f).height(9.dp),
                        )
                        Text("${band.count}", modifier = Modifier.width(32.dp), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                }
            }
        }
    }
}

@Composable
private fun ComponentsTab(analysis: ShenzhenGradeAnalysis) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (analysis.myComponents.isEmpty()) {
            item { StateCard("无法定位个人分项；你仍可查看概览和匿名全班成绩。", null, null) }
        }
        items(analysis.myComponents) { component ->
            GradeCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(component.name, fontWeight = FontWeight.SemiBold)
                    Text("占比 ${format(component.weight)}%")
                }
                Text("${component.score?.let(::format) ?: "--"} / ${format(component.fullScore)}")
            }
        }
        item {
            Text(
                "总评按各分项折算到百分制后加权；总权重不等于 100% 时会归一化。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun StudentScoresTab(analysis: ShenzhenGradeAnalysis) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text(
                "仅展示匿名序号，按总评分从高到低排序。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        items(analysis.students.withIndex().toList(), key = { it.value.anonymousId }) { indexed ->
            val isMe = indexed.value.anonymousId == analysis.myStudentId
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                        else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#${indexed.index + 1}", modifier = Modifier.width(56.dp))
                Text(if (isMe) "我的记录" else "匿名学生", modifier = Modifier.weight(1f))
                Text(format(indexed.value.total), fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
    }
}

@Composable
private fun GradeCard(content: @Composable ColumnScope.() -> Unit) {
    val tokens = HitaTheme.tokens
    val shape = RoundedCornerShape(tokens.radius.lg)
    Card(
        modifier = Modifier.fillMaxWidth().hitaGlassCardModifier(shape),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder()
    ) {
        Column(Modifier.padding(tokens.spacing.lg), content = content)
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun StateCard(message: String, action: String?, onAction: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        GradeCard {
            Text(message)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
            }
        }
    }
}

@Composable
private fun PeerTeacherComparisonDialog(
    state: PeerComparisonDialogState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val report = state.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("同学期各教师成绩对比")
                Text(
                    state.courseName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            when {
                state.loading -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                state.error != null -> Column {
                    Text(state.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                        Text("重试")
                    }
                }
                report == null -> Text("尚未生成查询结果")
                report.teacherRates.isEmpty() -> Text(
                    if (report.matchedClassCount == 0) {
                        "${report.targetTerm.name} 没有找到其他同名教学班。"
                    } else {
                        "找到 ${report.matchedClassCount} 个教学班，但没有完整成绩可供统计。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column {
                    Text(
                        "${report.targetTerm.name} · ${report.analyzedClassCount} 个教学班",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(report.teacherRates, key = { it.teacher }) { rate ->
                            PeerTeacherComparisonRow(rate)
                        }
                    }
                    if (report.skippedClassCount > 0) {
                        Text(
                            "另有 ${report.skippedClassCount} 个教学班未返回完整成绩",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Text(
                        "含空白分项的学生按 0 分计入所有指标",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PeerTeacherComparisonRow(rate: ShenzhenTeacherFailureRate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rate.teacher, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    String.format(Locale.ROOT, "%.2f%%", rate.failureRate),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "${rate.failCount} / ${rate.studentCount} 人未及格 · ${rate.classCount} 个教学班",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                String.format(
                    Locale.ROOT,
                    "平均 %.2f · 前20%%平均 %.2f",
                    rate.averageScore,
                    rate.top20AverageScore
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (rate.excludedIncompleteStudentCount > 0) {
                Text(
                    "${rate.excludedIncompleteStudentCount} 人含空白分项，按 0 分计入",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun format(value: Double): String = String.format(Locale.CHINA, "%.1f", value)
