package cn.limpu.hita.ui.main.timetable.compare

import android.content.Context
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.data.repository.FriendTimetableRepository
import cn.limpu.hita.data.repository.TimetableComparisonRepository
import cn.limpu.hita.feature.timetablecompare.ComparedPeriod
import cn.limpu.hita.feature.timetablecompare.PeriodStatus
import cn.limpu.hita.feature.timetablecompare.TimeSpan
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.hitaCourseColor
import cn.limpu.hita.ui.timetable.friend.FriendTimetableActivity
import cn.limpu.hita.utils.ActivityUtils
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.combine

/**
 * 课表对比独立界面：不复用主课表，返回键/关闭都回到来源页。
 * 网格是我的课程块 + 「冲突」块 + 合并「空闲」框；点块看双方明细。
 */
@AndroidEntryPoint
class TimetableComparisonActivity : HiltBaseActivity<ComposeViewBinding>() {

    private val comparisonViewModel: TimetableComparisonViewModel by viewModels()
    @Inject lateinit var friendTimetableRepository: FriendTimetableRepository
    @Inject lateinit var comparisonRepository: TimetableComparisonRepository

    override fun initViewBinding() = ComposeViewBinding(ComposeView(this))

    override fun initViews() {
        val preselectFriend = intent.getStringExtra(EXTRA_FRIEND_ID)
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                ComparisonScreen(
                    viewModel = comparisonViewModel,
                    friendRepository = friendTimetableRepository,
                    comparisonRepository = comparisonRepository,
                    preselectFriendId = preselectFriend,
                    onClose = { finish() },
                    onManage = { ActivityUtils.startTimetableManager(this) },
                    onImport = { startActivity(FriendTimetableActivity.intent(this, true)) },
                    onFriends = { startActivity(FriendTimetableActivity.intent(this)) },
                )
            }
        }
    }

    companion object {
        const val EXTRA_FRIEND_ID = "friend_share_id"
        fun intent(context: Context, friendShareId: String? = null) =
            Intent(context, TimetableComparisonActivity::class.java).putExtra(EXTRA_FRIEND_ID, friendShareId)
    }
}

private val weekDayFormat = DateTimeFormatter.ofPattern("M/d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComparisonScreen(
    viewModel: TimetableComparisonViewModel,
    friendRepository: FriendTimetableRepository,
    comparisonRepository: TimetableComparisonRepository,
    preselectFriendId: String?,
    onClose: () -> Unit,
    onManage: () -> Unit,
    onImport: () -> Unit,
    onFriends: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()
    val friends by remember { friendRepository.observeFriends() }.observeAsState(emptyList())
    val personalItems by remember {
        combine(comparisonRepository.observePersonalTimetables().asFlow(),
            comparisonRepository.observeGroups().asFlow(), ::buildPersonalItems)
    }.collectAsState(emptyList())

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetPreselect by rememberSaveable { mutableStateOf(preselectFriendId) }
    var monday by rememberSaveable {
        mutableLongStateOf(comparisonMonday(System.currentTimeMillis(), CAMPUS_ZONE))
    }
    var slotDetails by remember { mutableStateOf<TimeSpan?>(null) }

    // 首次进入且没有生效配置时自动打开配置面板。
    LaunchedEffect(Unit) { if (viewModel.config.value == null) sheetOpen = true }
    LaunchedEffect(monday) { viewModel.changeWeek(monday) }

    // 跨周加载中的过渡帧不画旧周结果。
    val ready = (state as? ComparisonUiState.Ready)?.takeIf {
        it.result.days.firstOrNull()?.date == Instant.ofEpochMilli(monday).atZone(CAMPUS_ZONE).toLocalDate()
    }
    val thisMonday = comparisonMonday(System.currentTimeMillis(), CAMPUS_ZONE)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ready?.let { comparisonTitle(it) } ?: stringResource(R.string.comparison_config_title),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = stringResource(R.string.friend_timetable_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.rotate(180f))
                    }
                },
                actions = {
                    TextButton(onClick = { sheetPreselect = null; sheetOpen = true }) {
                        Text(stringResource(R.string.comparison_adjust))
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Keep navigation available even when this week has no common validity.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { monday -= 7L * 24 * 3600 * 1000 }) {
                    Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(180f))
                }
                Text(
                    "${weekDayFormat.format(Instant.ofEpochMilli(monday).atZone(CAMPUS_ZONE))} – " +
                        weekDayFormat.format(Instant.ofEpochMilli(monday + 6L * 24 * 3600 * 1000).atZone(CAMPUS_ZONE)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (monday != thisMonday) {
                    TextButton(onClick = { monday = thisMonday }) {
                        Text(stringResource(R.string.comparison_back_this_week),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { monday += 7L * 24 * 3600 * 1000 }) {
                    Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            when (val current = state) {
                is ComparisonUiState.Error -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(current.messageResource), color = MaterialTheme.colorScheme.error)
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { sheetPreselect = null; sheetOpen = true }) {
                            Text(stringResource(R.string.comparison_adjust))
                        }
                        if (current.messageResource == R.string.comparison_read_error && config != null) {
                            Button(onClick = viewModel::retry) { Text(stringResource(R.string.blog_retry)) }
                        }
                    }
                }
                else -> {
                    if (ready == null) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (config != null) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.comparison_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp))
                            } else {
                                Text(stringResource(R.string.comparison_pick_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f)) {
                            ComparisonGrid(
                                ready = ready,
                                monday = monday,
                                onSlotDetails = { slotDetails = it },
                            )
                        }
                        // 图例。
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComparisonLegend(hitaCourseColor("对比:空闲", 0),
                                stringResource(R.string.comparison_free_short))
                            ComparisonLegend(hitaCourseColor("对比:忙碌", 0),
                                stringResource(R.string.comparison_conflict_short))
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        val initial = sheetPreselect?.let { fid ->
            (config ?: ComparisonConfig(personalId = "", friendId = ""))
                .copy(friendIds = (config?.selectedFriendIds.orEmpty() + fid).distinct())
        } ?: config
        ComparisonConfigSheet(
            personals = personalItems,
            friends = friends,
            initial = initial,
            loadOwnCourses = viewModel::ownCourseChoices,
            onDismiss = {
                sheetOpen = false
                sheetPreselect = null
                // 从未确认过配置时，关闭面板即退出界面。
                if (viewModel.config.value == null) onClose()
            },
            onConfirm = {
                viewModel.changeWeek(monday)
                viewModel.enable(it)
                sheetOpen = false
                sheetPreselect = null
            },
            onManage = onManage,
            onImport = onImport,
            onFriends = onFriends,
        )
    }

    // 节次/时间段明细。
    val detailReady = ready
    slotDetails?.let { span ->
        if (detailReady != null) {
            val slot = comparisonDetailSlot(detailReady.result, span)
            AlertDialog(
                onDismissRequest = { slotDetails = null },
                title = { Text(range(span)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ComparisonSlotCourseDetails(detailReady, slot)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { slotDetails = null }) {
                        Text(stringResource(R.string.comparison_close_details))
                    }
                },
            )
        } else {
            slotDetails = null
        }
    }
}

/** 图例：色块颜色与网格中的忙碌/空闲卡片一致。 */
@Composable
private fun ComparisonLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).background(color.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
        )
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp))
    }
}

/** 配置面板的我的课表项：组合课表整体出现，成员不单独列出。 */
internal fun buildPersonalItems(
    timetables: List<Timetable>,
    groups: List<TimetableGroupEntity>,
): List<ComparisonPersonalItem> {
    val byGroup = timetables.groupBy { it.groupId }
    val groupItems = groups.mapNotNull { group ->
        val members = byGroup[group.id].orEmpty().sortedBy { it.startTime.time }
        if (members.isEmpty()) null else ComparisonPersonalItem.Group(group.id, group.name,
            members.map(::comparisonPersonalChoice))
    }
    val singles = byGroup[null].orEmpty().map { ComparisonPersonalItem.Single(comparisonPersonalChoice(it)) }
    return groupItems + singles
}
