package cn.limpu.hita.ui.campus

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.campus.CampusCardPage
import cn.limpu.hita.data.model.campus.CampusCardSpending
import cn.limpu.hita.data.model.campus.CampusCardTransaction
import cn.limpu.hita.data.model.campus.CampusCardTransactions
import cn.limpu.hita.data.model.campus.campusCardYuan
import cn.limpu.hita.data.repository.CampusCardLedgerStore
import cn.limpu.hita.data.repository.CampusCardLedger
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.text.DateFormat
import java.util.Date
import cn.limpu.hita.data.source.web.campus.CampusCardHistoryRange
import cn.limpu.hita.data.source.web.campus.CampusCardSessionPolicy
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 校园卡入口（深圳校区）。
 *
 * 数据来自 `dxcdp.hitsz.edu.cn/portal-api`：先用统一身份认证换 `token`，再用
 * `token` 请求头读余额、消费汇总与流水（事实来源 iOS `CampusCardPortalClient`）。
 * 本项目没有 WebVPN 网关实现，因此登录在 WebView 里完成，Cookie 由
 * [CampusCookieJar] 从 CookieManager 读取。
 */
class CampusCardActivity : AppCompatActivity() {

    private val viewModel: CampusCardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 有历史会话（统一认证票据或门户 Cookie）才发请求；否则直接进未登录态。
        viewModel.load(if (CampusServiceAccount.CARD.isSignedOut(this)) null else readCampusCardCookies())
        setContent {
            HitaComposeTheme {
                var showLogin by remember { mutableStateOf(false) }
                var resetting by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val signOut: () -> Unit = {
                    if (!resetting) {
                        resetting = true
                        showLogin = false
                        viewModel.load(null)
                        scope.launch {
                            try { CampusServiceAccount.CARD.signOut(this@CampusCardActivity) }
                            finally { resetting = false }
                        }
                    }
                }
                if (showLogin) {
                    CampusCardLoginScreen(
                        onSignOut = signOut,
                        onCancel = { showLogin = false },
                        onLoggedIn = { ticket ->
                            if (!resetting && !CampusServiceAccount.CARD.isSignedOut(this@CampusCardActivity)) {
                                showLogin = false
                                viewModel.load(readCampusCardCookies(), ticket)
                            }
                        },
                    )
                } else {
                    CampusCardScreen(
                        viewModel = viewModel,
                        onSignIn = {
                            if (!resetting) {
                                resetting = true
                                scope.launch {
                                    try {
                                        // An expired/anonymous business cookie must not immediately close the login page.
                                        CampusServiceAccount.CARD.signOut(this@CampusCardActivity)
                                        CampusServiceAccount.CARD.beginSignIn(this@CampusCardActivity)
                                        showLogin = true
                                    } finally { resetting = false }
                                }
                            }
                        },
                        onSignOut = signOut,
                        resetting = resetting,
                        onBack = { finish() },
                        onReload = {
                            if (!resetting && !CampusServiceAccount.CARD.isSignedOut(this@CampusCardActivity)) {
                                viewModel.update()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 页面的状态持有者。
 *
 * 读取本地流水并观察应用级同步任务；凭据不落盘，筛选和翻页不触发请求。
 */
class CampusCardViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = CampusCardSyncController.get(application)
    private val store = CampusCardLedgerStore(File(application.noBackupFilesDir, "campus-card-history"))
    private var ledger: CampusCardLedger? = null
    private var sessionKey = ""
    private var pageNumber = 1
    private var syncing = false
    private var syncFailed = false
    private var requiresSignIn = false
    private var cookies: CampusCardCookies? = null
    private var job: Job? = null
    private val _historyPeriod = MutableStateFlow(YearMonth.now(ZoneId.of("Asia/Shanghai")))
    internal val historyPeriod = _historyPeriod.asStateFlow()
    private val _historyRange = MutableStateFlow(CampusCardHistoryRange.MONTH)
    internal val historyRange = _historyRange.asStateFlow()
    private val _state = MutableStateFlow<CampusServiceUiState<CampusCardPage>>(CampusServiceUiState.Loading)
    val state: StateFlow<CampusServiceUiState<CampusCardPage>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            controller.state.collect { sync ->
                if (cookies != null && sync.key == sessionKey) {
                    ledger = sync.ledger ?: ledger
                    syncing = sync.running
                    syncFailed = sync.failed
                    requiresSignIn = sync.requiresSignIn
                    if (ledger != null) showLocal()
                    else if (sync.requiresSignIn) _state.value = CampusServiceUiState.RequiresSignIn
                    else if (sync.failed) _state.value = CampusServiceUiState.Failed(
                        getApplication<Application>().getString(R.string.campus_card_initial_sync_failed))
                    else if (sync.running) _state.value = CampusServiceUiState.Loading
                }
            }
        }
    }

    internal fun changeHistoryRange(range: CampusCardHistoryRange) {
        _historyRange.value = range
        pageNumber = 1
        showLocal()
    }

    internal fun changeHistoryPeriod(period: YearMonth) {
        _historyPeriod.value = period
        pageNumber = 1
        showLocal()
    }

    internal fun changePage(page: Int) {
        val data = ledger ?: return
        val total = data.query(historyRange.value, historyPeriod.value, 1).total
        if (page < 1 || page.toLong() > ((total.toLong() + 19) / 20).coerceAtLeast(1)) return
        pageNumber = page
        showLocal()
    }

    private fun showLocal() {
        val data = ledger ?: return
        var rows = data.query(historyRange.value, historyPeriod.value, pageNumber)
        val maxPage = ((rows.total + 19) / 20).coerceAtLeast(1)
        if (pageNumber > maxPage) {
            pageNumber = maxPage
            rows = data.query(historyRange.value, historyPeriod.value, pageNumber)
        }
        _state.value = CampusServiceUiState.Ready(CampusCardPage(
            summary = data.summary.copy(spending = data.spending(Instant.now())), transactions = rows, transactionsFailed = syncFailed,
            transactionsLoading = syncing && data.rows.isEmpty(), transactionsUpdatedAt = data.updatedAt,
            historyPage = pageNumber, summaryCached = true, historySyncing = syncing,
            initialHistoryComplete = data.initialComplete, historySavedCount = data.rows.size,
            historyRemoteTotal = data.remoteTotal, historyYears = data.availableYears(Instant.now()),
            periodSpending = data.periodSpending(historyRange.value, historyPeriod.value),
            requiresSignIn = requiresSignIn,
        ))
    }

    /** Reopening, filtering and paging never trigger network calls when a local ledger exists. */
    internal fun load(session: CampusCardCookies?, portalTicket: String? = null) {
        job?.cancel()
        syncing = false
        syncFailed = false
        requiresSignIn = false
        pageNumber = 1
        cookies = session
        ledger = null
        if (session == null || (!session.hasSession && portalTicket == null)) {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        sessionKey = CampusCardSyncController.key(session)
        _state.value = CampusServiceUiState.Loading
        job = viewModelScope.launch {
            val key = CampusCardSyncController.key(session)
            val cached = store.read(key)
            ensureActive()
            ledger = cached
            val sync = controller.state.value
            if (sync.key == sessionKey) {
                ledger = sync.ledger ?: ledger
                syncing = sync.running
                syncFailed = sync.failed
                requiresSignIn = sync.requiresSignIn
            }
            if (ledger != null) showLocal()
            else if (requiresSignIn && portalTicket == null) _state.value = CampusServiceUiState.RequiresSignIn
            else if (sync.key == key && sync.failed && portalTicket == null) _state.value = CampusServiceUiState.Failed(
                getApplication<Application>().getString(R.string.campus_card_initial_sync_failed))
            if (portalTicket != null || (ledger == null && !requiresSignIn && !syncing && !syncFailed)) {
                controller.start(session, portalTicket)
            }
        }
    }

    internal fun update() {
        val session = cookies ?: return
        controller.start(session)
    }

}

/**
 * 两个 origin 的 Cookie 快照。
 *
 * 请求只能带发给该 origin 的那一段：把统一认证票据转给门户、或反过来，
 * 都会把无关凭证带出去。
 */
internal data class CampusCardCookies(
    val portalHeader: String,
    val casHeader: String,
) {
    fun headerFor(origin: String): String = when (origin) {
        CampusCardSessionPolicy.PORTAL_ORIGIN -> portalHeader
        CampusCardSessionPolicy.CAS_ORIGIN -> casHeader
        else -> ""
    }

    val portalCookieNames: Set<String> get() = CampusCardSessionPolicy.cookieNames(portalHeader)

    /** 统一身份认证票据仍在本机：可以尝试静默换票。 */
    val hasCasTicket: Boolean get() = CampusCardSessionPolicy.hasTicket(casHeader)

    /** 有任何一条会话线索才值得发请求，否则直接提示去登录。 */
    val hasSession: Boolean get() = portalCookieNames.isNotEmpty() || hasCasTicket
}

/** 从 WebView 的 CookieManager 取两个 origin 的会话；先 flush 保证 Set-Cookie 已落盘。 */
internal fun readCampusCardCookies(): CampusCardCookies {
    runCatching { CookieManager.getInstance().flush() }
    return CampusCardCookies(
        portalHeader = CampusCookieJar.header("${CampusCardSessionPolicy.PORTAL_ORIGIN}/portal-api/ssologin"),
        casHeader = CampusCookieJar.header(CampusCardSessionPolicy.CAS_LOGIN_URL),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampusCardScreen(
    viewModel: CampusCardViewModel,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    resetting: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val range by viewModel.historyRange.collectAsState()
    val period by viewModel.historyPeriod.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.extension_campus_card_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = stringResource(R.string.campus_card_back),
                            modifier = Modifier.rotate(180f),
                        )
                    }
                },
                actions = {
                    CampusSignOutButton(enabled = !resetting, onSignOut = onSignOut)
                    val ready = (state as? CampusServiceUiState.Ready)?.value
                    if (ready?.requiresSignIn == true) {
                        TextButton(onClick = onSignIn, enabled = !resetting) {
                            Text(stringResource(R.string.campus_card_login_action))
                        }
                    } else if (state !is CampusServiceUiState.RequiresSignIn) {
                        TextButton(onClick = onReload,
                            enabled = !resetting && state !is CampusServiceUiState.Loading && ready?.historySyncing != true) {
                            if (state is CampusServiceUiState.Loading) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.campus_card_update))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                is CampusServiceUiState.Loading -> LoadingCard()
                is CampusServiceUiState.RequiresSignIn -> SignInCard(onSignIn = onSignIn)
                is CampusServiceUiState.Failed -> FailedCard(reason = current.reason, onRetry = onReload)
                is CampusServiceUiState.Ready -> CampusCardContent(page = current.value, range = range, onRangeChanged = viewModel::changeHistoryRange, onPageChanged = viewModel::changePage, period = period, onPeriodChanged = viewModel::changeHistoryPeriod)
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.campus_card_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.campus_card_initial_guide),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignInCard(onSignIn: () -> Unit) {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.md),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
    ) {
        CampusServiceStateCard(
            title = stringResource(R.string.campus_service_session_title),
            message = stringResource(R.string.campus_card_session_required),
        )
        Button(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg),
        ) {
            Text(stringResource(R.string.campus_card_login_action))
        }
    }
}

@Composable
private fun FailedCard(reason: String, onRetry: () -> Unit) {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.md),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
    ) {
        CampusServiceStateCard(
            title = stringResource(R.string.campus_card_failed_title),
            message = reason,
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg),
        ) {
            Text(stringResource(R.string.campus_card_retry))
        }
    }
}

@Composable
private fun CampusCardContent(
    page: CampusCardPage,
    range: CampusCardHistoryRange,
    onRangeChanged: (CampusCardHistoryRange) -> Unit,
    onPageChanged: (Int) -> Unit,
    period: YearMonth,
    onPeriodChanged: (YearMonth) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = tokens.spacing.lg,
            end = tokens.spacing.lg,
            top = tokens.spacing.sm,
            bottom = tokens.spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
    ) {
        if (page.requiresSignIn) {
            item(key = "reauth") {
                Text(stringResource(R.string.campus_card_cached_sign_in_required))
            }
        }
        item(key = "balance") { BalanceCard(page) }
        if (!page.initialHistoryComplete) {
            item { Text(stringResource(R.string.campus_card_initial_guide), style = MaterialTheme.typography.bodyMedium) }
            item { Text(stringResource(if (page.historySyncing) R.string.campus_card_sync_progress else R.string.campus_card_sync_paused,
                page.historySavedCount, page.historyRemoteTotal), style = MaterialTheme.typography.bodySmall) }
        } else if (page.historySyncing) {
            item { Text(stringResource(R.string.campus_card_incremental_sync)) }
        }
        if (page.historySyncing) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        item(key = "spending") {
            if (page.spendingLoading) Text(stringResource(R.string.campus_card_spending_loading))
            else {
                val currentMonth = YearMonth.now(ZoneId.of("Asia/Shanghai"))
                val historical = range == CampusCardHistoryRange.YEAR ||
                    (range == CampusCardHistoryRange.MONTH && period != currentMonth)
                val label = if (range == CampusCardHistoryRange.YEAR)
                    stringResource(R.string.campus_card_selected_year, period.year)
                    else stringResource(R.string.campus_card_year_month, period.year, period.monthValue)
                SpendingCard(page.summary.spending,
                    periodLabel = label.takeIf { historical },
                    periodAmount = page.periodSpending,
                    currentMonthLabel = stringResource(R.string.campus_card_year_month, currentMonth.year, currentMonth.monthValue))
            }
        }
        item {
            Text(
                text = stringResource(R.string.campus_card_transactions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item(key = "range") {
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                FilterChip(selected = range == CampusCardHistoryRange.MONTH,
                    onClick = { onRangeChanged(CampusCardHistoryRange.MONTH) },
                    label = { Text(stringResource(R.string.campus_card_range_month)) })
                FilterChip(selected = range == CampusCardHistoryRange.YEAR,
                    onClick = { onRangeChanged(CampusCardHistoryRange.YEAR) },
                    label = { Text(stringResource(R.string.campus_card_range_year)) })
                FilterChip(selected = range == CampusCardHistoryRange.ALL,
                    onClick = { onRangeChanged(CampusCardHistoryRange.ALL) },
                    label = { Text(stringResource(R.string.campus_card_range_all)) })
            }
        }
        if (range != CampusCardHistoryRange.ALL) {
            item(key = "period") { CampusCardPeriodSelector(range, period, page.historyYears, onPeriodChanged) }
        }
        if (page.transactionsFailed && !page.requiresSignIn) {
            item {
                Text(
                    text = stringResource(R.string.campus_card_transactions_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        page.transactionsUpdatedAt?.let { updatedAt ->
            item { Text(stringResource(R.string.campus_card_history_updated,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatedAt))),
                style = MaterialTheme.typography.bodySmall) }
        }
        item(key = "pagination") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = page.historyPage > 1,
                    onClick = { onPageChanged(page.historyPage - 1) }) {
                    Text(stringResource(R.string.campus_card_previous_page))
                }
                Text(stringResource(R.string.campus_card_history_page, page.historyPage,
                    ((page.transactions.total.toLong() + 19) / 20).coerceAtLeast(page.historyPage.toLong()).toInt()))
                OutlinedButton(enabled = page.historyPage.toLong() * 20 < page.transactions.total,
                    onClick = { onPageChanged(page.historyPage + 1) }) {
                    Text(stringResource(R.string.campus_card_next_page))
                }
            }
        }
        if (page.transactionsLoading && page.transactions.items.isEmpty()) {
            // An uncached page has no rows until its first response.
        } else if (page.transactions.items.isEmpty() && !page.transactionsFailed && page.initialHistoryComplete) {
            item {
                Text(
                    text = stringResource(R.string.campus_card_transactions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(page.transactions.items, key = { it.id }) { transaction ->
                TransactionRow(transaction)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Text(
                    text = stringResource(
                        R.string.campus_card_transactions_total,
                        page.transactions.total,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CampusCardPeriodSelector(
    range: CampusCardHistoryRange,
    period: YearMonth,
    years: List<Int>,
    onChanged: (YearMonth) -> Unit,
) {
    var yearMenu by remember { mutableStateOf(false) }
    var monthMenu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.sm)) {
        Box {
            OutlinedButton(onClick = { yearMenu = true }) {
                Text(stringResource(R.string.campus_card_selected_year, period.year))
            }
            DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                (years + period.year).distinct().sortedDescending().forEach { year ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.campus_card_selected_year, year)) },
                        onClick = { yearMenu = false; onChanged(period.withYear(year)) })
                }
            }
        }
        if (range == CampusCardHistoryRange.MONTH) Box {
            OutlinedButton(onClick = { monthMenu = true }) {
                Text(stringResource(R.string.campus_card_selected_month, period.monthValue))
            }
            DropdownMenu(expanded = monthMenu, onDismissRequest = { monthMenu = false }) {
                (1..12).forEach { month ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.campus_card_selected_month, month)) },
                        onClick = { monthMenu = false; onChanged(period.withMonth(month)) })
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(page: CampusCardPage) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.campus_card_balance_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                // 接口单位是分；展示固定两位小数，避免本地化分节符影响读数。
                text = campusCardYuan(page.summary.balanceCents),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val status = page.summary.statusText
            if (status != null) {
                Text(
                    text = stringResource(R.string.campus_card_status_value, status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            val processedAt = page.summary.processedAtText
            if (processedAt != null) {
                Text(
                    text = stringResource(R.string.campus_card_processed_at_value, processedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SpendingCard(spending: CampusCardSpending?, periodLabel: String?, periodAmount: String?, currentMonthLabel: String) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        if (periodLabel != null) {
            SpendingCell(label = periodLabel, value = periodAmount,
                modifier = Modifier.fillMaxWidth().padding(tokens.spacing.lg))
        } else if (spending == null) {
            Text(
                text = stringResource(R.string.campus_card_spending_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(tokens.spacing.lg),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(tokens.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.md),
            ) {
                SpendingCell(
                    label = stringResource(R.string.campus_card_spending_yesterday),
                    value = spending.yesterdayText,
                    modifier = Modifier.weight(1f),
                )
                SpendingCell(
                    label = stringResource(R.string.campus_card_spending_week),
                    value = spending.weekText,
                    modifier = Modifier.weight(1f),
                )
                SpendingCell(
                    label = currentMonthLabel,
                    value = spending.monthText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SpendingCell(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TransactionRow(transaction: CampusCardTransaction) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.merchant.ifBlank { transaction.method },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(transaction.occurredAtText, transaction.method)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = campusCardYuan(transaction.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 统一身份认证登录页（WebView）。
 *
 * 登录态判定全部交给纯函数 [CampusCardSessionPolicy]，这里只负责浏览器外壳：
 * 只放行校园 https 域名、证书错误即取消、页面落定后读 Cookie 判定是否完成。
 */
@Composable
private fun CampusCardLoginScreen(
    onSignOut: () -> Unit,
    onCancel: () -> Unit,
    onLoggedIn: (String?) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var incomplete by remember { mutableStateOf(false) }
    val tokens = HitaTheme.tokens

    BackHandler { onCancel() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.campus_card_login_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.campus_card_login_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CampusSignOutButton(onSignOut = onSignOut)
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.campus_card_login_cancel))
            }
        }
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (failed) {
            Text(
                text = stringResource(R.string.campus_card_login_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = tokens.spacing.lg),
            )
        }
        if (incomplete) {
            Text(
                text = stringResource(R.string.campus_card_login_incomplete),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = tokens.spacing.lg),
            )
        }
        Button(
            onClick = {
                failed = false
                incomplete = false
                // 页面上的“完成”按钮是兜底：门户页面已落定但 Cookie 还没被读到。
                if (readCampusCardCookies().hasSession) {
                    onLoggedIn(null)
                } else {
                    incomplete = true
                }
            },
            modifier = Modifier.padding(horizontal = tokens.spacing.lg),
        ) {
            Text(stringResource(R.string.campus_card_login_confirm))
        }
        CampusCardLoginWebView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onPageFinished = { loading = false },
            onError = { failed = true },
            onSessionReady = onLoggedIn,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CampusCardLoginWebView(
    modifier: Modifier,
    onPageFinished: () -> Unit,
    onError: () -> Unit,
    onSessionReady: (String?) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.destroy()
            }
            webView = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            WebView(context).apply {
                setBackgroundColor(Color.WHITE)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    @Suppress("DEPRECATION")
                    saveFormData = false
                }
                manager.setAcceptThirdPartyCookies(this, false)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) = Unit
                }
                webViewClient = object : WebViewClient() {
                    private var delivered = false

                    private fun captureTicket(url: String?): Boolean {
                        if (webView == null) return false
                        val ticket = CampusCardSessionPolicy.portalTicket(url.orEmpty()) ?: return false
                        if (!delivered) {
                            delivered = true
                            onSessionReady(ticket)
                        }
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        // Capture before the SPA consumes the one-time ticket.
                        if (captureTicket(url)) view?.stopLoading()
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        captureTicket(url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = captureTicket(request?.url?.toString()) || !isCampusSecureUrl(request?.url?.toString())

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        captureTicket(url) || !isCampusSecureUrl(url)

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url == null) return
                        onPageFinished()
                        captureTicket(url)
                        // A route/session cookie alone is not proof of successful authentication.
                        // Without a ticket keep the browser open; the explicit completion button remains available.
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) onError()
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?,
                    ) {
                        // 证书错误必须取消：统一身份认证凭据不能交给中间人。
                        handler?.cancel()
                        onError()
                    }
                }
                webView = this
                loadUrl(CampusCardSessionPolicy.CAS_LOGIN_URL)
            }
        },
    )
}

/** 登录页只放行 https，且必须落在校园域名内（`ids` 与门户之间的跳转都在其中）。 */
private fun isCampusSecureUrl(url: String?): Boolean {
    val uri = runCatching { java.net.URI(url.orEmpty()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "hitsz.edu.cn" || host.endsWith(".hitsz.edu.cn") ||
        host == "hit.edu.cn" || host.endsWith(".hit.edu.cn")
}
