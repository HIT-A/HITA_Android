package cn.limpu.hita.ui.eas.training

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlan
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanCategory
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanCourse
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanDetail
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.utils.ActivityUtils
import com.limpu.component.data.DataState
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class ShenzhenTrainingPlanActivity :
    EASActivity<ShenzhenTrainingPlanViewModel, ComposeViewBinding>() {

    override val viewModel: ShenzhenTrainingPlanViewModel by viewModels()

    override fun initViewBinding(): ComposeViewBinding = ComposeViewBinding(ComposeView(this))

    override fun initViews() {
        super.initViews()
        bindState()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                TrainingPlanScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onRefresh = {
                        if (viewModel.selectedPlan.value == null) viewModel.retryPlans()
                        else viewModel.retryDetail()
                    },
                    onConnectWeb = ::connectWebSession
                )
            }
        }
    }

    private fun bindState() {
        viewModel.plans.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    viewModel.reconcilePlans(state.data.orEmpty())
                    resetSessionRetryState()
                }
                DataState.STATE.NOT_LOGGED_IN -> handleSessionExpired(viewModel::retryPlans)
                else -> Unit
            }
        }
        viewModel.detail.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> resetSessionRetryState()
                DataState.STATE.NOT_LOGGED_IN -> handleSessionExpired(viewModel::retryDetail)
                else -> Unit
            }
        }
    }

    override fun refresh() {
        viewModel.refreshPlans()
    }

    private fun connectWebSession() {
        // 直接打开网页登录，不再弹校区/账号选择框。
        launchDirectWebLogin(EASToken.Campus.SHENZHEN, EASToken.TYPE.UNDERGRAD)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainingPlanScreen(
    viewModel: ShenzhenTrainingPlanViewModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onConnectWeb: () -> Unit
) {
    val plansState by viewModel.plans.observeAsState()
    val detailState by viewModel.detail.observeAsState()
    val selectedPlan by viewModel.selectedPlan.observeAsState()
    val plans = plansState?.data.orEmpty()
    val activeState = detailState ?: plansState
    val loading = activeState == null || activeState?.state == DataState.STATE.NOTHING

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TopAppBar(
            title = { Text("培养方案", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                            contentDescription = "刷新"
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        when {
            activeState?.state == DataState.STATE.NOT_LOGGED_IN -> PlanState(
                activeState?.message ?: "请先连接深圳 Web 教务",
                "连接 Web 教务",
                onConnectWeb
            )
            activeState?.state == DataState.STATE.FETCH_FAILED -> PlanState(
                activeState?.message ?: "培养方案加载失败",
                "重试",
                onRefresh
            )
            detailState?.state == DataState.STATE.SUCCESS && detailState?.data != null ->
                TrainingPlanContent(
                    plans = plans,
                    selectedPlan = selectedPlan,
                    detail = requireNotNull(detailState?.data),
                    onPlanSelected = viewModel::selectPlan
                )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun TrainingPlanContent(
    plans: List<ShenzhenTrainingPlan>,
    selectedPlan: ShenzhenTrainingPlan?,
    detail: ShenzhenTrainingPlanDetail,
    onPlanSelected: (ShenzhenTrainingPlan) -> Unit
) {
    val tokens = HitaTheme.tokens
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.lg)
    ) {
        if (plans.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    plans.forEach { plan ->
                        FilterChip(
                            selected = selectedPlan?.id == plan.id,
                            onClick = { onPlanSelected(plan) },
                            label = { Text(plan.majorDirection.ifBlank { plan.name }) }
                        )
                    }
                }
            }
        }
        item { PlanSummary(detail) }
        if (detail.groups.isNotEmpty()) {
            item { GroupRequirements(detail) }
        }
        items(detail.categories, key = { it.id }) { category ->
            CategorySection(category)
        }
    }
}

@Composable
private fun PlanSummary(detail: ShenzhenTrainingPlanDetail) {
    PlanCard {
        Text(detail.plan.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        val metadata = listOf(
            detail.plan.majorName,
            detail.plan.majorDirection,
            detail.plan.schoolName,
            detail.plan.grade.takeIf { it.isNotBlank() }?.let { "$it 级" }.orEmpty(),
            detail.plan.version.takeIf { it.isNotBlank() }?.let { "版本 $it" }.orEmpty()
        ).filter { it.isNotBlank() }.distinct()
        if (metadata.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                metadata.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PlanMetric(
                "课程代码",
                detail.courses.map { it.courseCode.ifBlank { it.courseName } }.distinct().size.toString()
            )
            PlanMetric("方案条目", detail.courses.size.toString())
            PlanMetric(
                "课程组",
                detail.groups.size.takeIf { it > 0 }?.toString() ?: detail.categories.size.toString()
            )
        }
    }
}

@Composable
private fun GroupRequirements(detail: ShenzhenTrainingPlanDetail) {
    val groupsById = detail.groups.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("课程组要求", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        PlanCard(contentPadding = 0.dp) {
            detail.groups.forEachIndexed { index, group ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, fontWeight = FontWeight.SemiBold)
                        groupsById[group.parentId]?.let { parent ->
                            Text(
                                parent.name,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                    val requirement = buildList {
                        group.minimumCredits?.let { add("${formatNumber(it)} 学分") }
                        group.minimumCourses?.let { add("$it 门") }
                        group.minimumHours?.let { add("${formatNumber(it)} 学时") }
                    }.joinToString(" · ")
                    if (requirement.isNotBlank()) {
                        Text(requirement, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
                if (index < detail.groups.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategorySection(category: ShenzhenTrainingPlanCategory) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(category.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                category.group?.let { group ->
                    val requirement = buildList {
                        group.minimumCredits?.let { add("最低 ${formatNumber(it)} 学分") }
                        group.minimumCourses?.let { add("至少 $it 门") }
                        group.minimumHours?.let { add("最低 ${formatNumber(it)} 学时") }
                    }.joinToString(" · ")
                    if (requirement.isNotBlank()) {
                        Text(
                            requirement,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Text(
                "${category.courses.size} 项",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
        PlanCard(contentPadding = 0.dp) {
            category.courses.forEachIndexed { index, course ->
                CourseRow(course)
                if (index < category.courses.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseRow(course: ShenzhenTrainingPlanCourse) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(course.courseName, fontWeight = FontWeight.SemiBold)
                if (course.courseCode.isNotBlank()) {
                    Text(
                        course.courseCode,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                course.credits?.let {
                    Text("${formatNumber(it)} 学分", color = MaterialTheme.colorScheme.primary)
                }
                RequiredLabel(course.required)
            }
        }
        val details = buildList {
            if (course.recommendedTerm.isNotBlank()) add(course.recommendedTerm)
            course.totalHours?.let { add("${formatNumber(it)} 学时") }
            if (course.assessmentMethod.isNotBlank()) add(course.assessmentMethod)
            if (course.offeringCollege.isNotBlank()) add(course.offeringCollege)
        }
        if (details.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                details.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RequiredLabel(required: Boolean?) {
    val label = when (required) {
        true -> "必修"
        false -> "选修"
        null -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PlanMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun PlanCard(
    contentPadding: androidx.compose.ui.unit.Dp = HitaTheme.tokens.spacing.lg,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(HitaTheme.tokens.radius.lg)
    Card(
        modifier = Modifier.fillMaxWidth().hitaGlassCardModifier(shape),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder()
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
private fun PlanState(message: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        PlanCard {
            Text(message)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.CHINA, "%.1f", value)
