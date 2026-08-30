package cn.limpu.hita.ui.eas.score

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.ScoreSummary
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.preference.ScoreReminderStore
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.data.work.ScoreReminderScheduler
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.WeightedScoreCalculator
import cn.limpu.hita.utils.formatCredits
import com.limpu.style.widgets.PopUpCheckableList
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class ScoreInquiryActivity :
    EASActivity<ScoreInquiryViewModel, ComposeViewBinding>() {

    override val viewModel: ScoreInquiryViewModel by viewModels()
    override val autoLaunchWebLoginForSessionRecovery = false
    private lateinit var scoreReminderStore: ScoreReminderStore
    private var scoreQueryInFlight = false
    private var isRefreshing by mutableStateOf(false)
    private var showEmpty by mutableStateOf(false)
    private var scoreReminderEnabled by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableScoreReminder()
        } else {
            scoreReminderEnabled = false
            scoreReminderStore.setEnabled(false)
            Toast.makeText(this, "需要通知权限才能发送成绩提醒", Toast.LENGTH_LONG).show()
        }
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    private fun bindLiveData() {
        viewModel.termsLiveData.observe(this) { data ->
            when (data.state) {
                DataState.STATE.SUCCESS -> {
                    val terms = data.data.orEmpty()
                    if (terms.isNotEmpty()) {
                        viewModel.reconcileTerms(terms)
                    }
                }

                DataState.STATE.NOT_LOGGED_IN -> {
                    if (!handleSessionExpired {
                            scoreQueryInFlight = true
                            isRefreshing = true
                            viewModel.startRefresh()
                            true
                        }) {
                        scoreQueryInFlight = false
                    }
                }

                DataState.STATE.NOTHING -> Unit

                else -> {
                    isRefreshing = false
                    showEmpty = true
                }
            }
        }
        viewModel.selectedTermLiveData.observe(this) { term ->
            if (term != null) {
                isRefreshing = true
            }
        }
        viewModel.scoresLiveData.observe(this) {
            isRefreshing = false
            val latestItems = it.data ?: emptyList()
            when (it.state) {
                DataState.STATE.SUCCESS -> {
                    scoreQueryInFlight = false
                    resetSessionRetryState()
                }

                DataState.STATE.NOT_LOGGED_IN -> {
                    if (!handleSessionExpired { retryCurrentScoreQuery() }) {
                        scoreQueryInFlight = false
                    }
                }

                else -> {
                    scoreQueryInFlight = false
                    resetSessionRetryState()
                }
            }
            showEmpty = latestItems.isEmpty()
        }
        viewModel.localScoreLiveData.observe(this) { }
        viewModel.selectedTestTypeLiveData.observe(this) {
            if (it != null) {
                isRefreshing = true
            }
        }
    }

    override fun initViews() {
        super.initViews()
        UsageAnalyticsClient.record(UsageAnalyticsEvent.SCORES_VIEWED)
        scoreReminderStore = ScoreReminderStore(applicationContext)
        scoreReminderEnabled = scoreReminderStore.isEnabled()
        bindLiveData()
        viewModel.ensureDefaultTestType()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                ScoreInquiryScreen(
                    viewModel = viewModel,
                    isRefreshing = isRefreshing,
                    showEmpty = showEmpty,
                    reminderEnabled = scoreReminderEnabled,
                    getDisplayTermName = ::getDisplayTermName,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = {
                        scoreQueryInFlight = true
                        refresh()
                    },
                    onPickTerm = { pickTerm() },
                    onPickTestType = { pickTestType() },
                    onReminderChange = { updateScoreReminderEnabled(it) },
                    onOpenScore = { score ->
                        ScoreDetailFragment.newInstance(score)
                            .show(supportFragmentManager, "score_detail")
                    }
                )
            }
        }
    }

    private fun pickTerm() {
        viewModel.termsLiveData.value?.data?.let { terms ->
            val names = terms.map { getDisplayTermName(it) }
            if (names.isEmpty()) return
            PopUpCheckableList<TermItem>()
                .setListData(names, terms)
                .setTitle(getString(R.string.pick_quety_term))
                .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<TermItem> {
                    override fun OnConfirm(title: String?, key: TermItem) {
                        scoreQueryInFlight = true
                        viewModel.selectTerm(key)
                    }
                }).show(supportFragmentManager, "terms")
        }
    }

    private fun pickTestType() {
        val names = mutableListOf(
            getString(R.string.test_type_final),
            getString(R.string.test_type_midterm)
        )
        val list = arrayListOf(
            EASService.TestType.NORMAL,
            EASService.TestType.RESIT
        )
        PopUpCheckableList<EASService.TestType>()
            .setListData(names, list)
            .setTitle(getString(R.string.pick_test_type))
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<EASService.TestType> {
                override fun OnConfirm(title: String?, key: EASService.TestType) {
                    scoreQueryInFlight = true
                    viewModel.selectTestType(key)
                }
            }).show(supportFragmentManager, "types")
    }

    private fun retryCurrentScoreQuery(): Boolean {
        if (viewModel.retryCurrentQuery()) {
            scoreQueryInFlight = true
            isRefreshing = true
            return true
        }
        return false
    }

    private fun getDisplayTermName(term: TermItem): String {
        return TermNameFormatter.fullTermName(term)
    }

    override fun refresh() {
        isRefreshing = true
        scoreQueryInFlight = true
        viewModel.startRefresh()
    }

    private fun updateScoreReminderEnabled(enabled: Boolean) {
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    enableScoreReminder()
                } else {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                enableScoreReminder()
            }
        } else {
            scoreReminderEnabled = false
            scoreReminderStore.setEnabled(false)
            ScoreReminderScheduler.cancel(this)
        }
    }

    private fun enableScoreReminder() {
        scoreReminderEnabled = true
        scoreReminderStore.setEnabled(true)
        ScoreReminderScheduler.schedule(this)
        Toast.makeText(this, "成绩提醒已开启", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreInquiryScreen(
    viewModel: ScoreInquiryViewModel,
    isRefreshing: Boolean,
    showEmpty: Boolean,
    reminderEnabled: Boolean,
    getDisplayTermName: (TermItem) -> String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPickTerm: () -> Unit,
    onPickTestType: () -> Unit,
    onReminderChange: (Boolean) -> Unit,
    onOpenScore: (CourseScoreItem) -> Unit
) {
    val tokens = HitaTheme.tokens
    val selectedTerm by viewModel.selectedTermLiveData.observeAsState()
    val selectedTestType by viewModel.selectedTestTypeLiveData.observeAsState()
    val scoresState by viewModel.scoresLiveData.observeAsState()
    val summary by viewModel.scoreSummaryLiveData.observeAsState()
    val localEstimate by viewModel.localScoreLiveData.observeAsState()
    val scores = scoresState?.data.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.label_activity_score_inquiry),
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
        ScoreFilterBar(
            termText = selectedTerm?.let(getDisplayTermName).orEmpty(),
            testTypeText = testTypeText(selectedTestType),
            onPickTerm = onPickTerm,
            onPickTestType = onPickTestType
        )
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = tokens.spacing.xl)
            ) {
                item {
                    ScoreSummaryCard(summary = summary, localEstimate = localEstimate)
                }
                item {
                    ScoreReminderCard(
                        enabled = reminderEnabled,
                        onChange = onReminderChange
                    )
                }
                if (showEmpty && scores.isEmpty() && !isRefreshing) {
                    item {
                        ScoreEmptyView()
                    }
                }
                if (scores.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column {
                                scores.forEachIndexed { index, score ->
                                    ScoreRow(
                                        score = score,
                                        showDivider = index != scores.lastIndex,
                                        onClick = { onOpenScore(score) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (isRefreshing && scores.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ScoreFilterBar(
    termText: String,
    testTypeText: String,
    onPickTerm: () -> Unit,
    onPickTestType: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallFilterText(
                text = termText.ifBlank { "-" },
                modifier = Modifier.weight(1f),
                onClick = onPickTerm
            )
            SmallFilterText(
                text = testTypeText,
                modifier = Modifier.weight(1f),
                onClick = onPickTestType
            )
        }
    }
}

@Composable
private fun SmallFilterText(
    text: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            painter = painterResource(R.drawable.ic_expand),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(12.dp)
        )
    }
}

@Composable
private fun ScoreSummaryCard(
    summary: ScoreSummary?,
    localEstimate: WeightedScoreCalculator.ScoreResult?
) {
    val tokens = HitaTheme.tokens
    val weightedAverageRaw = summary?.weightedAverage?.ifBlank { "-" } ?: "-"
    val weightedAverage = weightedAverageRaw.toDoubleOrNull()
        ?.let { String.format(Locale.ROOT, "%.2f", it) }
        ?: weightedAverageRaw
    val gpaRaw = summary?.gpa?.ifBlank { "-" } ?: "-"
    val gpa = gpaRaw.toDoubleOrNull()
        ?.let { String.format(Locale.ROOT, "%.2f", it) }
        ?: gpaRaw
    val rank = summary?.rank?.ifBlank { "-" } ?: "-"
    val total = summary?.total?.ifBlank { "" } ?: ""
    val rankText = if (total.isNotBlank() && rank.isNotBlank() && rank != "-") "$rank / $total" else rank
    val hasLocalEstimate = localEstimate?.validCourses?.let { it > 0 } == true
    val officialSection = when (summary?.scope) {
        cn.limpu.hita.data.model.eas.ScoreSummaryScope.SELECTED_TERM ->
            stringResource(R.string.score_section_selected_term_official)
        cn.limpu.hita.data.model.eas.ScoreSummaryScope.CUMULATIVE ->
            stringResource(R.string.score_section_cumulative_official)
        else -> stringResource(R.string.score_section_server)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            SectionLabel(text = officialSection)
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryMetric(
                    label = stringResource(R.string.score_summary_gpa),
                    value = weightedAverage,
                    primary = true,
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.score_summary_official_gpa),
                    value = gpa,
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.score_summary_rank),
                    value = rankText,
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = tokens.spacing.md),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
            SectionLabel(text = stringResource(R.string.score_section_local_estimate))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryMetric(
                    label = stringResource(R.string.score_local_estimated_average),
                    value = if (hasLocalEstimate) {
                        String.format(Locale.ROOT, "%.1f", localEstimate?.weightedAverage)
                    } else "-",
                    primary = true,
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.score_local_numeric_credits),
                    value = if (hasLocalEstimate) {
                        formatCredits(localEstimate?.totalCredits ?: 0f)
                    } else "-",
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.score_local_numeric_courses),
                    value = if (hasLocalEstimate) localEstimate?.validCourses.toString() else "-",
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = stringResource(R.string.score_local_estimate_note),
                modifier = Modifier.padding(top = tokens.spacing.sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(bottom = HitaTheme.tokens.spacing.sm)
            .alpha(0.7f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp
    )
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    primary: Boolean,
    modifier: Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 4.dp),
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScoreReminderCard(
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
            .clickable { onChange(!enabled) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.score_reminder_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.score_reminder_subtitle),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                modifier = Modifier.padding(start = tokens.spacing.md)
            )
        }
    }
}

@Composable
private fun ScoreEmptyView() {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = tokens.spacing.xl,
                top = tokens.spacing.xxxxl,
                end = tokens.spacing.xl,
                bottom = tokens.spacing.xl
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
            text = stringResource(R.string.no_info),
            modifier = Modifier.padding(top = tokens.spacing.md),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScoreRow(
    score: CourseScoreItem,
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
                text = score.courseName ?: "-",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = tokens.spacing.lg),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = score.credits.takeIf { it > 0 }?.let { "${formatCredits(it)}学分" }.orEmpty(),
                modifier = Modifier.padding(end = tokens.spacing.sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = score.finalScoresText?.takeIf { it.isNotBlank() }
                        ?: score.finalScores.takeIf { it >= 0 }?.toString()
                        ?: "--",
                    modifier = Modifier.padding(
                        horizontal = tokens.spacing.sm,
                        vertical = 6.dp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
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
private fun testTypeText(type: EASService.TestType?): String {
    return when (type) {
        EASService.TestType.RESIT -> stringResource(R.string.test_type_midterm)
        EASService.TestType.RETAKE -> stringResource(R.string.test_type_retake)
        EASService.TestType.NORMAL,
        EASService.TestType.ALL,
        null -> stringResource(R.string.test_type_final)
    }
}
