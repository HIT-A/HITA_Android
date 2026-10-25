package cn.limpu.hita.ui.eas.classroom

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import android.os.Bundle
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import cn.limpu.hita.data.analytics.UsageAnalyticsDimensions
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.ui.eas.classroom.detail.EmptyClassroomDetailFragment
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.TimeTools
import com.limpu.style.widgets.PopUpCheckableList
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class EmptyClassroomActivity :
    EASActivity<EmptyClassroomViewModel, ComposeViewBinding>() {

    override val viewModel: EmptyClassroomViewModel by viewModels()
    private var classroomQueryInFlight = false
    private var isRefreshing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    private fun bindLiveData() {
        viewModel.termsLiveData.observe(this) { data ->
            when (data.state) {
                DataState.STATE.SUCCESS -> {
                    val terms = data.data.orEmpty()
                    if (terms.isNotEmpty()) {
                        viewModel.selectedTermLiveData.value =
                            terms.firstOrNull { it.isCurrent } ?: terms.first()
                    }
                }

                DataState.STATE.NOT_LOGGED_IN -> {
                    if (classroomQueryInFlight) {
                        if (!handleSessionExpired { retryCurrentClassroomQuery() }) {
                            classroomQueryInFlight = false
                            isRefreshing = false
                        }
                    }
                }

                else -> {
                    isRefreshing = false
                }
            }
        }
        viewModel.buildingsLiveData.observe(this) { data ->
            when (data.state) {
                DataState.STATE.SUCCESS -> {
                    if (!data.data.isNullOrEmpty()) {
                        viewModel.selectedBuildingLiveData.value = data.data?.get(0)
                    }
                }

                DataState.STATE.NOT_LOGGED_IN -> {
                    if (classroomQueryInFlight) {
                        if (!handleSessionExpired { retryCurrentClassroomQuery() }) {
                            classroomQueryInFlight = false
                            isRefreshing = false
                        }
                    }
                }

                else -> {
                    isRefreshing = false
                }
            }
        }
        viewModel.selectedBuildingLiveData.observe(this) {
            isRefreshing = true
        }
        viewModel.selectedTermLiveData.observe(this) {
            isRefreshing = true
        }
        viewModel.selectedWeekLiveData.observe(this) {
            isRefreshing = true
        }
        viewModel.classroomLiveData.observe(this) {
            isRefreshing = false
            when (it.state) {
                DataState.STATE.SUCCESS -> {
                    classroomQueryInFlight = false
                    resetSessionRetryState()
                }

                DataState.STATE.NOT_LOGGED_IN -> {
                    if (classroomQueryInFlight) {
                        if (!handleSessionExpired { retryCurrentClassroomQuery() }) {
                            classroomQueryInFlight = false
                        }
                    }
                }

                else -> {
                    classroomQueryInFlight = false
                    resetSessionRetryState()
                }
            }
        }
    }

    override fun refresh() {
        isRefreshing = true
        classroomQueryInFlight = true
        viewModel.startRefresh()
    }

    override fun initViews() {
        super.initViews()
        bindLiveData()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                EmptyClassroomScreen(
                    viewModel = viewModel,
                    isRefreshing = isRefreshing,
                    getDisplayTermName = ::getDisplayTermName,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { refresh() },
                    onPickTerm = { pickTerm() },
                    onPickBuilding = { pickBuilding() },
                    onPickWeek = { pickWeek() },
                    onOpenDetail = { classroom ->
                        val term = viewModel.selectedTermLiveData.value ?: return@EmptyClassroomScreen
                        val week = viewModel.selectedWeekLiveData.value ?: return@EmptyClassroomScreen
                        val structure = viewModel.timetableStructureLiveData.value?.data
                            ?: return@EmptyClassroomScreen
                        EmptyClassroomDetailFragment(
                            term,
                            week,
                            classroom,
                            structure
                        ).show(supportFragmentManager, "detail")
                    }
                )
            }
        }
    }

    private fun pickTerm() {
        viewModel.termsLiveData.value?.data?.let { terms ->
            val filteredTerms = cn.limpu.hita.utils.TermUtils.filterTermsForStudent(
                terms,
                easRepository.getEasToken().grade
            )
            val names = filteredTerms.map { getDisplayTermName(it) }
            if (names.isEmpty()) return
            PopUpCheckableList<TermItem>()
                .setListData(names, filteredTerms)
                .setTitle(getString(R.string.pick_quety_term))
                .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<TermItem> {
                    override fun OnConfirm(title: String?, key: TermItem) {
                        classroomQueryInFlight = true
                        viewModel.selectedTermLiveData.value = key
                    }
                }).show(supportFragmentManager, "terms")
        }
    }

    private fun pickBuilding() {
        viewModel.buildingsLiveData.value?.data?.let { buildings ->
            val names = buildings.mapNotNull { it.name }
            if (names.isEmpty()) return
            PopUpCheckableList<BuildingItem>()
                .setListData(names, buildings)
                .setTitle(getString(R.string.pick_query_building))
                .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<BuildingItem> {
                    override fun OnConfirm(title: String?, key: BuildingItem) {
                        classroomQueryInFlight = true
                        viewModel.selectedBuildingLiveData.value = key
                    }
                }).show(supportFragmentManager, "building")
        }
    }

    private fun pickWeek() {
        val names = (1..20).map { it.toString() }
        PopUpCheckableList<Int>()
            .setListData(names, (1..20).toList())
            .setTitle(getString(R.string.pick_query_week))
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<Int> {
                override fun OnConfirm(title: String?, key: Int) {
                    classroomQueryInFlight = true
                    viewModel.selectedWeekLiveData.value = key
                }
            }).show(supportFragmentManager, "weeks")
    }

    private fun retryCurrentClassroomQuery(): Boolean {
        val started = viewModel.retryCurrentQuery()
        if (started) {
            classroomQueryInFlight = true
            isRefreshing = true
        } else {
            refresh()
        }
        return true
    }

    private fun getDisplayTermName(term: TermItem): String {
        return TermNameFormatter.fullTermName(term)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyClassroomScreen(
    viewModel: EmptyClassroomViewModel,
    isRefreshing: Boolean,
    getDisplayTermName: (TermItem) -> String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPickTerm: () -> Unit,
    onPickBuilding: () -> Unit,
    onPickWeek: () -> Unit,
    onOpenDetail: (ClassroomItem) -> Unit
) {
    val tokens = HitaTheme.tokens
    val selectedTerm by viewModel.selectedTermLiveData.observeAsState()
    val selectedBuilding by viewModel.selectedBuildingLiveData.observeAsState()
    val selectedWeek by viewModel.selectedWeekLiveData.observeAsState()
    val classroomState by viewModel.classroomLiveData.observeAsState()
    val structureState by viewModel.timetableStructureLiveData.observeAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.label_activity_empty_classroom),
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
        FilterBar(
            building = selectedBuilding?.name.orEmpty(),
            term = selectedTerm?.let(getDisplayTermName).orEmpty(),
            week = selectedWeek,
            onPickBuilding = onPickBuilding,
            onPickTerm = onPickTerm,
            onPickWeek = onPickWeek
        )
        val classrooms = classroomState?.data.orEmpty()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(tokens.spacing.sm)
            ) {
                items(classrooms, key = { it.id.ifBlank { it.name } }) { classroom ->
                    EmptyClassroomCard(
                        classroom = classroom,
                        state = getClassroomState(classroom, structureState?.data),
                        onClick = { onOpenDetail(classroom) }
                    )
                }
            }
            if (isRefreshing && classrooms.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun FilterBar(
    building: String,
    term: String,
    week: Int?,
    onPickBuilding: () -> Unit,
    onPickTerm: () -> Unit,
    onPickWeek: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.sm),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChipText(
                text = building.ifBlank { stringResource(R.string.load_failed) },
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
                onClick = onPickBuilding
            )
            FilterChipText(
                text = term.ifBlank { stringResource(R.string.load_failed) },
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                onClick = onPickTerm
            )
            FilterChipText(
                text = week?.let { stringResource(R.string.week_title, it) }
                    ?: stringResource(R.string.load_failed),
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
                onClick = onPickWeek
            )
        }
    }
    Spacer(modifier = Modifier.height(tokens.spacing.sm))
}

@Composable
private fun FilterChipText(
    text: String,
    modifier: Modifier,
    horizontalAlignment: Alignment.Horizontal,
    onClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(tokens.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp, horizontalAlignment),
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
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun EmptyClassroomCard(
    classroom: ClassroomItem,
    state: String,
    onClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val isFree = state == "空闲"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.sm)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(tokens.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = classroom.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isFree) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            ) {
                Text(
                    text = state,
                    modifier = Modifier.padding(
                        horizontal = tokens.spacing.md,
                        vertical = tokens.spacing.xs
                    ),
                    color = if (isFree) MaterialTheme.colorScheme.primary else Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun getClassroomState(
    data: ClassroomItem,
    scheduleStructure: MutableList<cn.limpu.hita.data.model.timetable.TimePeriodInDay>?
): String {
    scheduleStructure?.let {
        val current = TimeTools.getCurrentScheduleNumber(it)
        val currentDow = TimeTools.getDow(System.currentTimeMillis())
        val occupiedNumbers = data.scheduleList
            .filter { je ->
                val dow = je.optInt("XQJ")
                val occupied = je.optString("JYBJ").isNotBlank() || je.optString("PKBJ").isNotBlank()
                dow == currentDow && occupied
            }
            .map { je -> je.optInt("XJ") * 10 }
            .toSet()
        return when {
            current in occupiedNumbers -> "被占"
            current % 10 == 5 && (current + 5) in occupiedNumbers -> "将占"
            current % 10 == 0 && (current + 10) in occupiedNumbers -> "将占"
            else -> "空闲"
        }
    }
    return "未知"
}
