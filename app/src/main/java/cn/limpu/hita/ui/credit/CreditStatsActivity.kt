package cn.limpu.hita.ui.credit

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.ShenzhenCreditGroupProgress
import cn.limpu.hita.data.model.eas.ShenzhenCreditProgress
import cn.limpu.hita.data.model.eas.ShenzhenCreditRequirement
import cn.limpu.hita.data.model.eas.ShenzhenCreditCourseRecord
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.utils.TermNameFormatter
import com.limpu.component.data.DataState
import com.limpu.style.ThemeTools
import com.limpu.style.widgets.PopUpCheckableList
import com.limpu.style.widgets.PopUpFloatPicker
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class CreditStatsActivity : EASActivity<CreditStatsViewModel, ComposeViewBinding>() {
    override val viewModel: CreditStatsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate(savedInstanceState)

    }

    override fun initViewBinding(): ComposeViewBinding = ComposeViewBinding(ComposeView(this))

    override fun initViews() {
        super.initViews()
        UsageAnalyticsClient.record(UsageAnalyticsEvent.CREDIT_SUMMARY_VIEWED)
        viewModel.shenzhenProgress.observe(this) { state ->
            if (state.state == DataState.STATE.NOT_LOGGED_IN) {
                handleSessionExpired(viewModel::retryOfficial)
            } else if (state.state == DataState.STATE.SUCCESS) {
                resetSessionRetryState()
            }
        }
        viewModel.termsLiveData.observe(this) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                viewModel.reconcileTerms(state.data.orEmpty())
            }
        }
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                CreditStatsScreen(
                    viewModel = viewModel,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onEditGoal = ::showGoalPicker,
                    onRetryOfficial = { viewModel.retryOfficial() },
                    onPickTerm = ::showTermPicker
                )
            }
        }
    }

    override fun refresh() {
        viewModel.refresh()
    }

    private fun showGoalPicker(category: CreditCategorySummary) {
        val picker = PopUpFloatPicker()
            .setDialogTitle(R.string.credit_set_goal)
            .setInitialValue(category.goalCredits ?: category.totalCredits)
        picker.setOnDialogConformListener(object : PopUpFloatPicker.OnDialogConformListener {
            override fun onClick(result: Float) {
                if (result <= 0f) {
                    viewModel.removeGoal(category.type)
                } else {
                    viewModel.setGoal(category.type, result)
                }
            }
        })
        picker.show(supportFragmentManager, "goal_picker")
    }

    private fun showTermPicker() {
        val terms = viewModel.termsLiveData.value?.data.orEmpty()
        if (terms.isEmpty()) return
        PopUpCheckableList<TermItem>()
            .setListData(terms.map { TermNameFormatter.fullTermName(it) }, terms)
            .setTitle("选择学期")
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<TermItem> {
                override fun OnConfirm(title: String?, key: TermItem) {
                    viewModel.selectTerm(key)
                }
            })
            .show(supportFragmentManager, "credit_terms")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreditStatsScreen(
    viewModel: CreditStatsViewModel,
    onBack: () -> Unit,
    onEditGoal: (CreditCategorySummary) -> Unit,
    onRetryOfficial: () -> Unit,
    onPickTerm: () -> Unit
) {
    if (viewModel.officialSupported) {
        val officialState by viewModel.shenzhenProgress.observeAsState()
        ShenzhenCreditProgressScreen(
            state = officialState,
            onBack = onBack,
            onRetry = onRetryOfficial,
            selectedTerm = viewModel.selectedTermLiveData.observeAsState().value,
            onPickTerm = onPickTerm
        )
        return
    }
    val tokens = HitaTheme.tokens
    val state by viewModel.creditStats.observeAsState(CreditStatsState())
    val expanded = remember { mutableStateMapOf<TermSubject.TYPE, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.credit_stats_title),
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        if (state.isEmpty) {
            EmptyCreditView()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                CreditSummaryCard(state = state)
                state.categories.forEach { category ->
                    val isExpanded = expanded[category.type] ?: category.expanded
                    CreditCategoryCard(
                        category = category,
                        expanded = isExpanded,
                        onToggleExpanded = {
                            expanded[category.type] = !isExpanded
                        },
                        onEditGoal = { onEditGoal(category) },
                        modifier = Modifier.padding(
                            start = tokens.spacing.lg,
                            top = tokens.spacing.sm,
                            end = tokens.spacing.lg
                        )
                    )
                }
                Text(
                    text = stringResource(R.string.credit_data_from_timetable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .alpha(0.5f)
                        .padding(
                            start = tokens.spacing.xl,
                            top = tokens.spacing.xs,
                            end = tokens.spacing.xl,
                            bottom = tokens.spacing.xl
                        )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShenzhenCreditProgressScreen(
    state: DataState<ShenzhenCreditProgress>?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    selectedTerm: TermItem?,
    onPickTerm: () -> Unit
) {
    val progress = state?.data
    var showAllCourses by remember(progress?.courseRecords?.size) { mutableStateOf(false) }
    val expandedGroups = remember(progress?.groups?.size) { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("培养方案完成度")
                    Row(
                        modifier = Modifier.clickable(onClick = onPickTerm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            selectedTerm?.let(TermNameFormatter::fullTermName)
                                ?: progress?.currentTerm?.takeIf(String::isNotBlank)?.let(::formatOfficialTerm)
                                ?: "选择学期",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_expand),
                            contentDescription = "选择学期",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp).size(12.dp)
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        when {
            state == null || state.state in listOf(
                DataState.STATE.NOTHING,
                DataState.STATE.LOADING
            ) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.state != DataState.STATE.SUCCESS || progress == null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.message ?: "培养方案完成度读取失败",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                    Text("重试")
                }
            }
            else -> {
                val groups = progress.groups.filter { it.depth > 0 || it.name != "无" }
                val visibleCourses = if (showAllCourses) {
                    progress.courseRecords
                } else {
                    progress.courseRecords.take(10)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { OfficialCreditSummaryCard(progress) }

                    if (progress.categories.isNotEmpty()) {
                        item { OfficialSectionTitle("学分类别要求", "${progress.categories.size} 项") }
                        item { OfficialRequirementCards(progress.categories) }
                    }

                    if (groups.isNotEmpty()) {
                        item { OfficialSectionTitle("培养课组", "${groups.size} 项") }
                        items(groups, key = { it.id }) { group ->
                            OfficialGroupCard(
                                group = group,
                                expanded = expandedGroups[group.id] == true,
                                onToggle = {
                                    expandedGroups[group.id] = expandedGroups[group.id] != true
                                }
                            )
                        }
                    }

                    if (progress.courseRecords.isNotEmpty()) {
                        item {
                            OfficialSectionTitle(
                                "课程成绩明细",
                                "${progress.courseRecords.size} 条"
                            )
                        }
                        items(visibleCourses, key = { it.id }) { course ->
                            OfficialCourseRecordRow(course)
                        }
                        if (progress.courseRecords.size > 10) {
                            item {
                                TextButton(
                                    onClick = { showAllCourses = !showAllCourses },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (showAllCourses) "收起课程"
                                        else "查看全部 ${progress.courseRecords.size} 条明细"
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            "数据来源：深圳 Web 教务个人培养方案",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfficialCreditSummaryCard(progress: ShenzhenCreditProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    formatOfficialCredit(progress.completedCredits),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    " / ${formatOfficialCredit(progress.requiredCredits)} 学分",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(progress.completionRatio * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            LinearProgressIndicator(
                progress = { progress.completionRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(top = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OfficialSummaryMetric(
                    "培养要求",
                    formatOfficialCredit(progress.requiredCredits),
                    Modifier.weight(1f)
                )
                OfficialSummaryMetric(
                    "已经完成",
                    formatOfficialCredit(progress.completedCredits),
                    Modifier.weight(1f)
                )
                OfficialSummaryMetric(
                    "仍需完成",
                    formatOfficialCredit(progress.remainingCredits),
                    Modifier.weight(1f),
                    valueColor = if (progress.remainingCredits > 0.0) {
                        MaterialTheme.colorScheme.onSurface
                    } else MaterialTheme.colorScheme.primary
                )
            }
            val courseProgress = listOfNotNull(
                progress.completedCourses?.let { "已完成 $it 门" },
                progress.requiredCourses?.let { "要求 $it 门" },
                progress.remainingCourses?.let { "还需 $it 门" }
            ).joinToString(" · ")
            if (courseProgress.isNotBlank()) {
                Text(
                    courseProgress,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            val rankText = when {
                progress.rank != null && progress.cohortSize != null ->
                    "平均学分绩 ${formatOfficialCredit(progress.averageCreditScore ?: 0.0)} · 专业排名 ${progress.rank}/${progress.cohortSize}"
                progress.averageCreditScore != null ->
                    "平均学分绩 ${formatOfficialCredit(progress.averageCreditScore)}"
                else -> ""
            }
            if (rankText.isNotBlank()) {
                Text(
                    rankText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OfficialSummaryMetric(
    label: String,
    value: String,
    modifier: Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(
            value,
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun OfficialSectionTitle(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(trailing, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun OfficialRequirementCards(requirements: List<ShenzhenCreditRequirement>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        requirements.forEach { requirement ->
            OfficialRequirementCard(requirement)
        }
    }
}

@Composable
private fun OfficialRequirementCard(requirement: ShenzhenCreditRequirement) {
    val completed = formatRequirementCredit(requirement.completedCredits)
    val remaining = formatRequirementCredit(requirement.remainingCredits)
    val metadata = buildList {
        add("方向：${requirement.majorDirection.ifBlank { "无" }}")
        requirement.teachingLanguage.takeIf(String::isNotBlank)?.let { add("语言：$it") }
        requirement.courseNature.takeIf(String::isNotBlank)?.let { add("性质：$it") }
    }.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
            contentDescription = buildString {
                append(requirement.name)
                append("，方向 ${requirement.majorDirection.ifBlank { "无" }}")
                requirement.teachingLanguage.takeIf(String::isNotBlank)?.let {
                    append("，授课语言 $it")
                }
                requirement.courseNature.takeIf(String::isNotBlank)?.let {
                    append("，课程性质 $it")
                }
                append("，要求 ${formatRequirementCredit(requirement.requiredCredits)} 学分")
                append("，完成 $completed 学分")
                if (requirement.includesMooc) {
                    append(
                        "，慕课实修 ${formatRequirementCredit(requirement.earnedMoocCredits)} 学分" +
                            "，计入 ${formatRequirementCredit(requirement.creditedMoocCredits)} 学分"
                    )
                }
                append("，剩余 $remaining 学分")
                append(if (requirement.passed) "，已完成要求" else "，仍需完成")
                requirement.completedHours?.let { append("，完成学时 $it") }
            }
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = requirement.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (requirement.passed) "已完成要求" else "还需 $remaining 学分",
                    color = if (requirement.passed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp, top = 2.dp)
                )
            }
            Text(
                text = metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OfficialRequirementMetric(
                    label = "要求学分",
                    value = formatRequirementCredit(requirement.requiredCredits),
                    modifier = Modifier.weight(1f)
                )
                OfficialRequirementMetric(
                    label = if (requirement.includesMooc) "完成 · 含MOOC" else "完成学分",
                    value = completed,
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                OfficialRequirementMetric(
                    label = "剩余学分",
                    value = remaining,
                    valueColor = if (requirement.passed) {
                        MaterialTheme.colorScheme.onSurface
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            requirement.completedHours?.let { hours ->
                Text(
                    text = "完成学时 $hours",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            if (requirement.includesMooc) {
                Text(
                    text = "MOOC 实修 ${formatRequirementCredit(requirement.earnedMoocCredits)} 学分" +
                        " · 计入 ${formatRequirementCredit(requirement.creditedMoocCredits)} 学分",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OfficialRequirementMetric(
    label: String,
    value: String,
    modifier: Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun OfficialGroupCard(
    group: ShenzhenCreditGroupProgress,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((group.depth - 1).coerceAtLeast(0) * 10).dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = group.courses.isNotEmpty(),
                        role = Role.Button,
                        onClick = onToggle
                    )
                    .semantics {
                        if (group.courses.isNotEmpty()) {
                            stateDescription = if (expanded) "已展开" else "已收起"
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.Medium)
                    val detail = buildList {
                        group.requiredCredits?.let {
                            add(
                                "${formatOfficialCredit(group.completedCredits)} / " +
                                    "${formatOfficialCredit(it)} 学分"
                            )
                        }
                        group.requiredCourses?.let { add("${group.completedCourses} / $it 门") }
                    }.joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Text(
                    if (group.passed) "已完成要求" else group.remainingCredits?.let {
                        "还需 ${formatOfficialCredit(it)} 学分"
                    } ?: "仍需完成",
                    color = if (group.passed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (group.courses.isNotEmpty()) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
                        contentDescription = if (expanded) "收起课组课程" else "展开课组课程",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(22.dp)
                            .rotate(if (expanded) 180f else 0f)
                    )
                }
            }
            if (expanded) {
                group.courses.forEach { course ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(course.courseName, fontSize = 13.sp)
                            Text(
                                listOf(course.courseCode, course.recommendedTerm)
                                    .filter(String::isNotBlank).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            if (course.completed) "已修" else "未修",
                            color = if (course.completed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                        Text(
                            "${formatOfficialCredit(course.credits)} 学分",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfficialCourseRecordRow(course: ShenzhenCreditCourseRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(course.courseName, fontWeight = FontWeight.Medium)
                Text(
                    listOf(course.courseCode, course.term, course.teacher)
                        .filter(String::isNotBlank).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${formatOfficialCredit(course.credits)} 学分",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (course.score.isNotBlank()) {
                    Text(
                        course.score,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

private fun formatOfficialCredit(value: Double): String =
    String.format(Locale.CHINA, if (value % 1.0 == 0.0) "%.0f" else "%.1f", value)

private fun formatRequirementCredit(value: Double): String =
    String.format(Locale.CHINA, "%.1f", value)

private fun formatOfficialTerm(value: String): String {
    val match = Regex("^(\\d{4}-\\d{4})([123])$").find(value) ?: return value
    val term = when (match.groupValues[2]) {
        "1" -> "秋季"
        "2" -> "春季"
        "3" -> "夏季"
        else -> match.groupValues[2]
    }
    return "${match.groupValues[1]} · $term"
}

@Composable
private fun EmptyCreditView() {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = tokens.spacing.xl,
                top = tokens.spacing.xxxxl,
                end = tokens.spacing.xl
            )
            .alpha(0.8f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_empty),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp)
            )
        }
        Text(
            text = stringResource(R.string.credit_no_data),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = tokens.spacing.md)
        )
    }
}

@Composable
private fun CreditSummaryCard(state: CreditStatsState) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(tokens.radius.xl),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = tokens.spacing.lg,
                top = tokens.spacing.md,
                end = tokens.spacing.lg
            )
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryMetric(
                    label = stringResource(R.string.credit_total),
                    value = String.format("%.1f", state.totalCredits),
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.credit_total_subjects),
                    value = state.totalSubjects.toString(),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.padding(vertical = tokens.spacing.md)
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryMetric(
                    label = stringResource(R.string.credit_spa_subtotal),
                    value = String.format("%.1f", state.spaCredits),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    labelSize = 12,
                    valueSize = 14,
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.credit_non_spa_subtotal),
                    value = String.format("%.1f", state.nonSpaCredits),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    labelSize = 12,
                    valueSize = 14,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    labelSize: Int = 14,
    valueSize: Int = 24
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = labelSize.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = valueSize.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = if (valueSize >= 20) 4.dp else 2.dp)
        )
    }
}

@Composable
private fun CreditCategoryCard(
    category: CreditCategorySummary,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEditGoal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(typeColor(category.type))
                )
                Text(
                    text = typeLabel(category.type),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                )
                Text(
                    text = stringResource(R.string.credit_format, category.totalCredits),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onEditGoal,
                    modifier = Modifier
                        .padding(start = tokens.spacing.sm)
                        .size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_edit_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (category.goalCredits != null && category.goalCredits > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = {
                            (category.totalCredits / category.goalCredits).coerceIn(0f, 1f)
                        },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.credit_goal_progress_format,
                            category.totalCredits,
                            category.goalCredits
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = tokens.spacing.sm)
                    )
                }
            }

            Text(
                text = pluralStringResource(
                    R.plurals.credit_subject_count,
                    category.subjectCount,
                    category.subjectCount
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = tokens.spacing.xs)
            )

            if (category.fieldBreakdown.size > 1) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.credit_collapse_detail
                        else R.string.credit_expand_detail
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = tokens.spacing.xs)
                        .clickable(onClick = onToggleExpanded)
                        .padding(tokens.spacing.xs)
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = tokens.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)
                ) {
                    category.fieldBreakdown.forEach { field ->
                        Text(
                            text = stringResource(
                                R.string.credit_field_row,
                                field.fieldName,
                                field.totalCredits
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: TermSubject.TYPE): String {
    return stringResource(
        when (type) {
            TermSubject.TYPE.COM_A -> R.string.credit_type_required
            TermSubject.TYPE.COM_B -> R.string.credit_type_required_check
            TermSubject.TYPE.OPT_A -> R.string.credit_type_limited
            TermSubject.TYPE.OPT_B -> R.string.credit_type_elective
            TermSubject.TYPE.MOOC -> R.string.credit_type_mooc
            else -> R.string.credit_type_unknown
        }
    )
}

private fun typeColor(type: TermSubject.TYPE): Color {
    return when (type) {
        TermSubject.TYPE.COM_A -> Color(0xFF3390EC)
        TermSubject.TYPE.COM_B -> Color(0xFF5C6BC0)
        TermSubject.TYPE.OPT_A -> Color(0xFF26A69A)
        TermSubject.TYPE.OPT_B -> Color(0xFF66BB6A)
        TermSubject.TYPE.MOOC -> Color(0xFFFF7043)
        else -> Color(0xFF9E9E9E)
    }
}
