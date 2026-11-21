package cn.limpu.hita.ui.campus

import android.app.Application
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.campus.LabCourseProgress
import cn.limpu.hita.data.model.campus.LabSessionItem
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.LabCenterCacheStore
import cn.limpu.hita.data.source.web.campus.LabCenterClient
import cn.limpu.hita.data.source.web.campus.LabCenterException
import cn.limpu.hita.data.source.web.campus.LabCenterSessionPolicy
import cn.limpu.hita.data.source.web.campus.LabCenterSessionExpiredException
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaStaleBanner
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.StaleDataBanner
import cn.limpu.hita.ui.design.formatCachedAt
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 深圳实验中心入口：本学期实验课场次与已上进度。
 *
 * 数据来自 `lab.hitsz.edu.cn` 的 GvSun LIMS 个人课表，会话是站点自己的 JSP Cookie；
 * 本部已有的 eelab 抓取属于另一套（JWT + 不同主机），无法复用。
 */
class LabCenterActivity : AppCompatActivity() {

    private val viewModel: LabCenterViewModel by viewModels()
    private var awaitingLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HitaComposeTheme {
                val state by viewModel.state.observeAsState(CampusServiceUiState.Loading)
                val notice by viewModel.notice.observeAsState()
                val refreshing by viewModel.refreshing.observeAsState(false)
                LabCenterScreen(
                    state = state,
                    notice = notice,
                    refreshing = refreshing,
                    onBack = { finish() },
                    onRefresh = viewModel::refresh,
                    onSignIn = { awaitingLogin = true; LabCenterLoginActivity.start(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 登录页返回时 Cookie 已经写入；直接重取，避免用户再点一次刷新。
        if (awaitingLogin) {
            awaitingLogin = false
            viewModel.refresh()
        }
    }
}

/** 有旧数据可看时，刷新失败只需要一条提示，不必把整页换成错误。 */
sealed interface LabCenterStaleReason {
    data object RefreshFailed : LabCenterStaleReason
    data object SignInExpired : LabCenterStaleReason
}

/** 页面顶部的「数据是旧的」提示；[cachedAtMillis] 是这份数据的拉取时间。 */
data class LabCenterNotice(
    val reason: LabCenterStaleReason,
    val cachedAtMillis: Long,
)

/** 实验中心课表的状态持有者。 */
class LabCenterViewModel(app: Application) : AndroidViewModel(app) {

    private val client = LabCenterClient()
    private val cacheStore = LabCenterCacheStore(app)
    private val easPreferences = EasPreferenceSource(app)
    private val _state = MutableLiveData<CampusServiceUiState<LabCourseProgress>>(
        CampusServiceUiState.Loading
    )
    val state: LiveData<CampusServiceUiState<LabCourseProgress>> = _state
    private val _notice = MutableLiveData<LabCenterNotice?>()
    val notice: LiveData<LabCenterNotice?> = _notice
    private val _refreshing = MutableLiveData(false)
    val refreshing: LiveData<Boolean> = _refreshing
    private var generation = 0L

    init {
        refresh()
    }

    fun refresh() {
        val ownerKey = LabCenterCacheStore.ownerKey(easPreferences.getEasToken())
        val cached = cacheStore.read(ownerKey)
        // 有上次结果就先渲染，后台再去刷新：整学期课表实测要 10 秒以上，每次进页面空等不划算。
        _state.value = if (cached != null) CampusServiceUiState.Ready(cached) else CampusServiceUiState.Loading
        _refreshing.value = true
        val requestGeneration = ++generation
        viewModelScope.launch {
            var notice: LabCenterNotice? = null
            val next = try {
                val cookie = CampusCookieJar.header(LabCenterSessionPolicy.SCHEDULE_URL)
                val progress = client.fetch(cookie)
                cacheStore.save(ownerKey, progress)
                CampusServiceUiState.Ready(progress)
            } catch (_: LabCenterSessionExpiredException) {
                notice = cached?.let { LabCenterNotice(LabCenterStaleReason.SignInExpired, it.fetchedAtMillis) }
                if (cached != null) CampusServiceUiState.Ready(cached) else CampusServiceUiState.RequiresSignIn
            } catch (error: LabCenterException) {
                notice = cached?.let { LabCenterNotice(LabCenterStaleReason.RefreshFailed, it.fetchedAtMillis) }
                if (cached != null) CampusServiceUiState.Ready(cached) else CampusServiceUiState.Failed(error.message.orEmpty())
            } catch (error: Exception) {
                notice = cached?.let { LabCenterNotice(LabCenterStaleReason.RefreshFailed, it.fetchedAtMillis) }
                if (cached != null) {
                    CampusServiceUiState.Ready(cached)
                } else {
                    CampusServiceUiState.Failed(error.message.orEmpty().ifBlank { "实验中心课表加载失败" })
                }
            }
            if (requestGeneration != generation) return@launch
            _state.value = next
            _notice.value = notice
            _refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabCenterScreen(
    state: CampusServiceUiState<LabCourseProgress>,
    notice: LabCenterNotice?,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extension_lab_center_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = stringResource(R.string.lab_center_back),
                            modifier = Modifier.rotate(180f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                                contentDescription = stringResource(R.string.lab_center_refresh),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            if (notice != null) {
                // 旧数据继续可看，只用一条提示说明它不是最新的；会话失效时给「去登录」。
                if (notice.reason == LabCenterStaleReason.SignInExpired) {
                    HitaStaleBanner(
                        message = stringResource(
                            R.string.lab_center_stale_sign_in,
                            formatCachedAt(notice.cachedAtMillis),
                        ),
                        onAction = onSignIn,
                        actionLabel = stringResource(R.string.lab_center_sign_in),
                    )
                } else {
                    StaleDataBanner(cachedAtMillis = notice.cachedAtMillis, onRetry = onRefresh)
                }
            }
            when (state) {
                is CampusServiceUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                is CampusServiceUiState.RequiresSignIn -> LabCenterActionCard(
                    title = stringResource(R.string.campus_service_session_title),
                    message = stringResource(R.string.lab_center_sign_in_required),
                    action = stringResource(R.string.lab_center_sign_in),
                    onAction = onSignIn,
                )

                is CampusServiceUiState.Failed -> LabCenterActionCard(
                    title = stringResource(R.string.lab_center_failed_title),
                    message = state.reason,
                    action = stringResource(R.string.lab_center_retry),
                    onAction = onRefresh,
                )

                is CampusServiceUiState.Ready -> LabCenterContent(state.value)
            }
        }
    }
}

@Composable
private fun LabCenterActionCard(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = HitaTheme.tokens.spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CampusServiceStateCard(title = title, message = message)
        Button(onClick = onAction, modifier = Modifier.padding(top = HitaTheme.tokens.spacing.sm)) {
            Text(action)
        }
    }
}

@Composable
private fun LabCenterContent(progress: LabCourseProgress) {
    val tokens = HitaTheme.tokens
    val currentWeek = progress.currentWeek
    val todayWeekday = remember { LocalDate.now().dayOfWeek.value }
    val completed = currentWeek?.let { progress.completedCount(it, todayWeekday) }
    val total = progress.totalCount
    val weekdayNames = stringArrayResource(R.array.dow2)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = tokens.spacing.lg,
            end = tokens.spacing.lg,
            bottom = tokens.spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
        item {
            ProgressCard(
                termName = progress.termName,
                currentWeek = currentWeek,
                completed = completed,
                total = total,
            )
        }
        if (total == 0) {
            item {
                CampusServiceStateCard(
                    title = stringResource(R.string.lab_center_empty_title),
                    message = stringResource(R.string.lab_center_empty_message),
                )
            }
        } else {
            items(progress.sessions, key = { it.id }) { session ->
                SessionRow(session = session, weekdayName = weekdayName(session.weekday, weekdayNames))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ProgressCard(
    termName: String?,
    currentWeek: Int?,
    completed: Int?,
    total: Int,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        ) {
            Text(
                text = termName ?: stringResource(R.string.lab_center_term_unknown),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (currentWeek != null) {
                    stringResource(R.string.lab_center_current_week, currentWeek)
                } else {
                    stringResource(R.string.lab_center_week_unknown)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // 没有教学周就没有「已上」：宁可只报总数，也不拿 0 冒充进度。
                text = if (completed != null) {
                    stringResource(R.string.lab_center_progress, completed, total)
                } else {
                    stringResource(R.string.lab_center_total_only, total)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = {
                    if (completed != null && total > 0) completed.toFloat() / total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
            Text(
                text = stringResource(R.string.lab_center_excludes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionRow(session: LabSessionItem, weekdayName: String) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.padding(end = tokens.spacing.md)) {
            Text(
                text = stringResource(R.string.lab_center_week_number, session.week),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = weekdayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.courseName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sections = stringResource(
                R.string.lab_center_sections,
                session.startSection,
                session.endSection,
            )
            Text(
                text = session.room?.let { "$sections · $it" } ?: sections,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun weekdayName(weekday: Int, names: Array<String>): String =
    names.getOrNull(weekday - 1) ?: weekday.toString()
