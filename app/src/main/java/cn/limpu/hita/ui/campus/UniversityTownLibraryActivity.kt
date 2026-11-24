package cn.limpu.hita.ui.campus

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.campus.LibrarySmartWallSnapshot
import cn.limpu.hita.data.source.web.campus.LibrarySmartWallClient
import cn.limpu.hita.data.source.web.campus.LibrarySmartWallException
import cn.limpu.hita.data.source.web.campus.LibrarySmartWallSignInRequiredException
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.ActivityUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 大学城图书馆智慧墙：在馆人数与静音仓占用。
 *
 * 两个接口本身免读者登录，但 aTrust 网关在未放行时会拦成登录页，因此页面保留
 * 「去登录」分支：用户在该门户完成登录后，Cookie 会留在 WebView 的 CookieManager 里，
 * 刷新时随请求一起带上。
 */
class UniversityTownLibraryActivity : AppCompatActivity() {

    private val viewModel: LibrarySmartWallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HitaComposeTheme {
                val state by viewModel.state.observeAsState(CampusServiceUiState.Loading)
                val refreshing by viewModel.refreshing.observeAsState(false)
                LibrarySmartWallScreen(
                    state = state,
                    refreshing = refreshing,
                    onBack = { finish() },
                    onRefresh = viewModel::refresh,
                    onSignIn = ::openPortalLogin,
                )
            }
        }
    }

    private fun openPortalLogin() {
        ActivityUtils.startInternalWebActivity(
            this,
            getString(R.string.extension_library_title),
            LibrarySmartWallClient.PORTAL_URL,
        )
    }
}

/** 智慧墙的状态持有者；每次刷新都整体替换快照，不做增量合并。 */
class LibrarySmartWallViewModel : ViewModel() {

    private val client = LibrarySmartWallClient()
    private val _state = MutableLiveData<CampusServiceUiState<LibrarySmartWallSnapshot>>(
        CampusServiceUiState.Loading
    )
    val state: LiveData<CampusServiceUiState<LibrarySmartWallSnapshot>> = _state
    private val _refreshing = MutableLiveData(false)
    val refreshing: LiveData<Boolean> = _refreshing
    private val requests = CampusRefreshGate<String>()
    private var job: Job? = null
    private var displayedKey: String? = null

    init {
        refresh()
    }

    fun refresh() {
        val cookie = CampusCookieJar.header(LibrarySmartWallClient.PORTAL_URL)
        val request = requests.begin(cookie) ?: return
        job?.cancel()
        if (displayedKey != cookie || _state.value !is CampusServiceUiState.Ready) {
            _state.value = CampusServiceUiState.Loading
        }
        _refreshing.value = true
        job = viewModelScope.launch {
            val next = try {
                CampusServiceUiState.Ready(client.fetch(cookieHeader = cookie.ifBlank { null }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LibrarySmartWallSignInRequiredException) {
                CampusServiceUiState.RequiresSignIn
            } catch (error: LibrarySmartWallException) {
                CampusServiceUiState.Failed(error.message.orEmpty())
            } catch (error: Exception) {
                // 兜底：未预期异常也必须落到带原因的失败态，不能让页面停在加载中。
                CampusServiceUiState.Failed(error.message.orEmpty().ifBlank { "图书馆数据加载失败" })
            }
            ensureActive()
            if (!requests.owns(request, cookie)) return@launch
            requests.finish(request)
            displayedKey = cookie
            _refreshing.value = false
            _state.value = next
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySmartWallScreen(
    state: CampusServiceUiState<LibrarySmartWallSnapshot>,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extension_library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = stringResource(R.string.library_back),
                            modifier = Modifier.rotate(180f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                                contentDescription = stringResource(R.string.library_refresh),
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
            when (state) {
                is CampusServiceUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                is CampusServiceUiState.RequiresSignIn -> ActionCard(
                    title = stringResource(R.string.campus_service_session_title),
                    message = stringResource(R.string.campus_library_portal_refresh_hint),
                    action = stringResource(R.string.library_sign_in),
                    onAction = onSignIn,
                )

                is CampusServiceUiState.Failed -> ActionCard(
                    title = stringResource(R.string.library_failed_title),
                    message = state.reason,
                    action = stringResource(R.string.library_retry),
                    onAction = onRefresh,
                )

                is CampusServiceUiState.Ready -> LibrarySmartWallContent(state.value)
            }
        }
    }
}

@Composable
private fun ActionCard(
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
        Button(
            onClick = onAction,
            modifier = Modifier.padding(top = HitaTheme.tokens.spacing.sm),
        ) {
            Text(action)
        }
    }
}

@Composable
private fun LibrarySmartWallContent(snapshot: LibrarySmartWallSnapshot) {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.md)) {
            MetricCard(
                title = stringResource(R.string.library_current_in),
                value = snapshot.peoples.currentInLibrary.toString(),
                unit = stringResource(R.string.library_people_unit),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = stringResource(R.string.library_today_in),
                value = snapshot.peoples.todayVisitors.toString(),
                unit = stringResource(R.string.library_people_unit),
                modifier = Modifier.weight(1f),
            )
        }
        RoomNumsCard(snapshot)
        Text(
            text = stringResource(
                R.string.library_fetched_at,
                FETCHED_AT_FORMAT.format(
                    Instant.ofEpochMilli(snapshot.fetchedAtMillis).atZone(ZoneId.systemDefault())
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MetricCard(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(tokens.radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = tokens.spacing.xs, bottom = tokens.spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun RoomNumsCard(snapshot: LibrarySmartWallSnapshot) {
    val tokens = HitaTheme.tokens
    val occupied = snapshot.rooms.occupied
    val available = snapshot.rooms.available
    val total = occupied + available
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.library_room_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.library_room_occupied, occupied) + " · " +
                    stringResource(R.string.library_room_available, available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 占用率为 0 或总数为 0 时进度条无意义，但仍然展示进度条本身（进度 0）。
            LinearProgressIndicator(
                progress = { if (total > 0) occupied.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
            Text(
                text = stringResource(
                    R.string.library_room_ratio,
                    if (total > 0) Math.round(occupied * 100f / total) else 0,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val FETCHED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
