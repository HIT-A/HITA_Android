package cn.limpu.hita.ui.eas.exam

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
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
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.TermUtils
import com.limpu.style.widgets.PopUpCheckableList
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExamActivity : EASActivity<ExamViewModel, ComposeViewBinding>() {

    override val viewModel: ExamViewModel by viewModels()
    private var isRefreshing by mutableStateOf(false)
    private var importInProgress by mutableStateOf(false)
    private var showEmpty by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cn.limpu.hita.utils.LogUtils.d("ExamActivity: onCreate called", "ExamActivity")
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    private fun bindLiveData() {
        viewModel.termsLiveData.observe(this) { data ->
            if (data.state == DataState.STATE.SUCCESS) {
                if (data.data.isNullOrEmpty()) {
                    isRefreshing = false
                    showEmpty = true
                }
            } else if (data.state == DataState.STATE.NOT_LOGGED_IN) {
                if (!handleSessionExpired {
                        refresh()
                        true
                    }) {
                    isRefreshing = false
                    showEmpty = !viewModel.hasExamMemos()
                }
            } else {
                isRefreshing = false
                showEmpty = true
            }
        }
        viewModel.selectedTermLiveData.observe(this) { term ->
            term?.let {
                isRefreshing = true
                if (viewModel.examInfoLiveData.value?.data.isNullOrEmpty()) {
                    Toast.makeText(this, "正在加载考试数据，请稍候...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.examInfoLiveData.observe(this) {
            cn.limpu.hita.utils.LogUtils.d(
                "ExamActivity: received state=${it.state}, data size=${it.data?.size}",
                "ExamActivity"
            )
            isRefreshing = false
            if (it.state == DataState.STATE.SUCCESS) {
                showEmpty = it.data.isNullOrEmpty()
                resetSessionRetryState()
            } else if (it.state == DataState.STATE.NOT_LOGGED_IN) {
                if (!handleSessionExpired {
                        refresh()
                        true
                    }) {
                    showEmpty = true
                }
            } else {
                showEmpty = true
            }
        }
    }

    override fun refresh() {
        isRefreshing = true
        resetSessionRetryState()
        viewModel.startRefresh()
    }

    override fun initViews() {
        super.initViews()
        UsageAnalyticsClient.record(UsageAnalyticsEvent.EXAMS_VIEWED)
        bindLiveData()
        viewModel.selectedExamTypeLiveData.value = ExamViewModel.ExamType.ALL
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                ExamScreen(
                    viewModel = viewModel,
                    isRefreshing = isRefreshing,
                    importInProgress = importInProgress,
                    showEmpty = showEmpty,
                    getDisplayTermName = ::getDisplayTermName,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { refresh() },
                    onPickTerm = { pickTerm() },
                    onPickExamType = { pickExamType() },
                    onImportAll = { importAllExams() },
                    onAddMemo = { openMemoEditor() },
                    onOpenExam = { exam ->
                        ExamDetailFragment(
                            exam = exam,
                            onEdit = if (exam.isMemo()) {
                                { openMemoEditor(exam) }
                            } else null,
                            onDelete = if (exam.isMemo()) {
                                { confirmDeleteMemo(exam) }
                            } else null
                        ).show(supportFragmentManager, "exam_detail")
                    }
                )
            }
        }
    }

    private fun openMemoEditor(item: ExamItem? = null) {
        val allTerms = viewModel.termsLiveData.value?.data.orEmpty()
        val memoTerms = TermUtils.filterTermsForStudent(
            allTerms,
            easRepository.getEasToken().grade
        ).toMutableList()
        listOfNotNull(item?.termId, viewModel.selectedTermLiveData.value?.id).forEach { termId ->
            allTerms.firstOrNull { it.id == termId }
                ?.takeIf { candidate -> memoTerms.none { it.id == candidate.id } }
                ?.let(memoTerms::add)
        }
        ExamMemoEditorFragment(
            editingItem = item,
            defaultTerm = viewModel.selectedTermLiveData.value,
            availableTerms = memoTerms,
            defaultCampusName = viewModel.defaultMemoCampusName(),
            onSave = { memo ->
                viewModel.saveExamMemo(memo).also { error ->
                    if (error == null) {
                        Toast.makeText(this, "考试备忘录已保存", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ).show(supportFragmentManager, "exam_memo_editor")
    }

    private fun confirmDeleteMemo(item: ExamItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除考试备忘录？")
            .setMessage(item.courseName.orEmpty())
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val error = viewModel.deleteExamMemo(item)
                Toast.makeText(
                    this,
                    error ?: "考试备忘录已删除",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun pickTerm() {
        viewModel.termsLiveData.value?.data?.let { terms ->
            val filteredTerms = TermUtils.filterTermsForStudent(
                terms,
                easRepository.getEasToken().grade
            )
            val names = filteredTerms.map { getDisplayTermName(it) }
            if (names.isEmpty()) return
            PopUpCheckableList<TermItem>()
                .setListData(names, filteredTerms)
                .setTitle(getString(R.string.pick_exam_term))
                .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<TermItem> {
                    override fun OnConfirm(title: String?, key: TermItem) {
                        if (key != viewModel.selectedTermLiveData.value) {
                            viewModel.selectedTermLiveData.value = key
                            refresh()
                        }
                    }
                }).show(supportFragmentManager, "exam_terms")
        }
    }

    private fun pickExamType() {
        if (!viewModel.shouldShowExamTypeFilter()) return
        val names = mutableListOf(
            getString(R.string.exam_type_all),
            getString(R.string.exam_type_midterm),
            getString(R.string.exam_type_final)
        )
        val list = arrayListOf(
            ExamViewModel.ExamType.ALL,
            ExamViewModel.ExamType.MIDTERM,
            ExamViewModel.ExamType.FINAL
        )
        PopUpCheckableList<ExamViewModel.ExamType>()
            .setListData(names, list)
            .setTitle(getString(R.string.pick_exam_type))
            .setOnConfirmListener(object :
                PopUpCheckableList.OnConfirmListener<ExamViewModel.ExamType> {
                override fun OnConfirm(title: String?, key: ExamViewModel.ExamType) {
                    viewModel.selectedExamTypeLiveData.value = key
                }
            }).show(supportFragmentManager, "exam_types")
    }

    private fun getDisplayTermName(term: TermItem): String {
        return TermNameFormatter.fullTermName(term)
    }

    private fun importAllExams() {
        viewModel.examInfoLiveData.value?.let { state ->
            if (state.state != DataState.STATE.SUCCESS) {
                cn.limpu.hita.utils.LogUtils.d("No exam data to import", "ExamActivity")
                return
            }

            val exams = state.data ?: return
            if (exams.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_exams_to_import), Toast.LENGTH_SHORT).show()
                return
            }

            importInProgress = true
            Toast.makeText(this, getString(R.string.importing_exams), Toast.LENGTH_SHORT).show()

            Thread {
                val result = viewModel.importAllExams(exams)
                runOnUiThread {
                    importInProgress = false
                    val success = result.successCount == result.totalCount
                    val partialSuccess = result.successCount > 0 && result.successCount < result.totalCount
                    val allImported = result.successCount == 0 && result.skippedCount > 0

                    when {
                        success -> Toast.makeText(
                            this,
                            getString(R.string.import_exams_success, result.successCount),
                            Toast.LENGTH_SHORT
                        ).show()

                        partialSuccess -> Toast.makeText(
                            this,
                            getString(
                                R.string.import_exams_partial,
                                result.successCount,
                                result.totalCount
                            ),
                            Toast.LENGTH_SHORT
                        ).show()

                        allImported -> Toast.makeText(
                            this,
                            getString(R.string.all_exams_imported),
                            Toast.LENGTH_SHORT
                        ).show()

                        else -> Toast.makeText(this, getString(R.string.upload_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamScreen(
    viewModel: ExamViewModel,
    isRefreshing: Boolean,
    importInProgress: Boolean,
    showEmpty: Boolean,
    getDisplayTermName: (TermItem) -> String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPickTerm: () -> Unit,
    onPickExamType: () -> Unit,
    onImportAll: () -> Unit,
    onAddMemo: () -> Unit,
    onOpenExam: (ExamItem) -> Unit
) {
    val tokens = HitaTheme.tokens
    val selectedTerm by viewModel.selectedTermLiveData.observeAsState()
    val selectedExamType by viewModel.selectedExamTypeLiveData.observeAsState()
    val examState by viewModel.examInfoLiveData.observeAsState()
    val exams = examState?.data.orEmpty()
    val showTypeFilter = remember { viewModel.shouldShowExamTypeFilter() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.label_activity_exam_inquiry),
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
                IconButton(onClick = onAddMemo) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_add_24),
                        contentDescription = "添加考试备忘录",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onRefresh) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )
        ExamHeader(
            termText = selectedTerm?.let(getDisplayTermName).orEmpty(),
            typeText = examTypeText(selectedExamType),
            showTypeFilter = showTypeFilter,
            hasData = exams.isNotEmpty(),
            importInProgress = importInProgress,
            onPickTerm = onPickTerm,
            onPickExamType = onPickExamType,
            onImportAll = onImportAll
        )
        Box(modifier = Modifier.fillMaxSize()) {
            if (showEmpty && exams.isEmpty() && !isRefreshing) {
                EmptyExamView(modifier = Modifier.align(Alignment.TopCenter))
            }
            if (exams.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    LazyColumn(contentPadding = PaddingValues(vertical = tokens.spacing.xs)) {
                        itemsIndexed(exams) { index, exam ->
                            ExamRow(
                                exam = exam,
                                showDivider = index != exams.lastIndex,
                                onClick = { onOpenExam(exam) }
                            )
                        }
                    }
                }
            }
            if (isRefreshing && exams.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ExamHeader(
    termText: String,
    typeText: String,
    showTypeFilter: Boolean,
    hasData: Boolean,
    importInProgress: Boolean,
    onPickTerm: () -> Unit,
    onPickExamType: () -> Unit,
    onImportAll: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.xl, vertical = tokens.spacing.lg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LargeFilterText(
                text = termText.ifBlank { "-" },
                modifier = Modifier.weight(1f),
                onClick = onPickTerm
            )
            if (showTypeFilter) {
                HorizontalDivider(
                    modifier = Modifier
                        .height(40.dp)
                        .padding(horizontal = tokens.spacing.sm),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                LargeFilterText(
                    text = typeText,
                    modifier = Modifier.weight(1f),
                    onClick = onPickExamType
                )
            }
        }
        if (hasData) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.md),
                horizontalArrangement = Arrangement.End
            ) {
                val bgColor by animateColorAsState(
                    targetValue = if (importInProgress)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.primary,
                    animationSpec = tween(350),
                    label = "import_bg"
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(bgColor)
                        .then(
                            if (!importInProgress) Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onImportAll() }
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedVisibility(
                        visible = importInProgress,
                        enter = fadeIn(tween(350)) + scaleIn(
                            initialScale = 0.5f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f)
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.size(tokens.spacing.lg))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.size(tokens.spacing.sm))
                        }
                    }
                    Text(
                        text = stringResource(R.string.import_all_exams),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(
                            start = if (importInProgress) 0.dp else tokens.spacing.lg,
                            end = tokens.spacing.lg,
                            top = 10.dp, bottom = 10.dp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeFilterText(
    text: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 28.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            painter = painterResource(R.drawable.ic_expand),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(18.dp)
        )
    }
}

@Composable
private fun EmptyExamView(modifier: Modifier) {
    val tokens = HitaTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = tokens.spacing.xl,
                top = tokens.spacing.xxxxl,
                end = tokens.spacing.xl
            )
            .alpha(0.8f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Image(
                painter = painterResource(R.drawable.ic_empty),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(tokens.spacing.xl)
            )
        }
        Text(
            text = stringResource(R.string.exam_memo_hint),
            modifier = Modifier.padding(top = tokens.spacing.md),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExamRow(
    exam: ExamItem,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val examType = exam.examType.orEmpty()
    val campusName = exam.campusName.orEmpty()
    val shouldShowType = when {
        campusName.contains("本部") -> true
        examType.isNotEmpty() && examType != "期末" -> true
        else -> false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(
                start = tokens.spacing.lg,
                top = 10.dp,
                end = tokens.spacing.lg,
                bottom = 10.dp
            )
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = exam.courseName ?: "未知课程",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (shouldShowType) {
                    Text(
                        text = examType,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.alpha(0.6f)
                    )
                }
            }
            if (exam.isMemo()) {
                Text(
                    text = "本地备忘录",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = buildString {
                    append(exam.examDate.orEmpty())
                    exam.examTime?.takeIf { it.isNotEmpty() }?.let {
                        append(" ")
                        append(it)
                    }
                },
                modifier = Modifier
                    .padding(top = tokens.spacing.xs)
                    .alpha(0.6f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = exam.examLocation ?: "地点待定",
                modifier = Modifier
                    .padding(top = 2.dp)
                    .alpha(0.6f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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

@Composable
private fun examTypeText(type: ExamViewModel.ExamType?): String {
    return when (type) {
        ExamViewModel.ExamType.MIDTERM -> stringResource(R.string.exam_type_midterm)
        ExamViewModel.ExamType.FINAL -> stringResource(R.string.exam_type_final)
        ExamViewModel.ExamType.ALL,
        null -> stringResource(R.string.exam_type_all)
    }
}
