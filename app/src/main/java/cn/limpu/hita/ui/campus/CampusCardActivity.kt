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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import cn.limpu.hita.data.source.web.campus.CampusCardClient
import cn.limpu.hita.data.source.web.campus.CampusCardNetworkException
import cn.limpu.hita.data.source.web.campus.CampusCardParseException
import cn.limpu.hita.data.source.web.campus.CampusCardSessionPolicy
import cn.limpu.hita.data.source.web.campus.CampusCardSignInRequiredException
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        viewModel.load(readCampusCardCookies())
        setContent {
            HitaComposeTheme {
                var showLogin by remember { mutableStateOf(false) }
                if (showLogin) {
                    CampusCardLoginScreen(
                        onCancel = { showLogin = false },
                        onLoggedIn = {
                            showLogin = false
                            viewModel.load(readCampusCardCookies())
                        },
                    )
                } else {
                    CampusCardScreen(
                        viewModel = viewModel,
                        onSignIn = { showLogin = true },
                        onBack = { finish() },
                        onReload = { viewModel.load(readCampusCardCookies()) },
                    )
                }
            }
        }
    }
}

/**
 * 页面的状态持有者。
 *
 * Cookie 每次由 Activity 传入，不缓存到内存；`token` 只在本次页面存活期间复用。
 * 服务端判定登录失效时立刻丢弃 `token` 并回到未登录态，而不是反复重试。
 */
class CampusCardViewModel(application: Application) : AndroidViewModel(application) {

    private val client = CampusCardClient()

    private val _state = MutableStateFlow<CampusServiceUiState<CampusCardPage>>(
        CampusServiceUiState.Loading,
    )
    val state: StateFlow<CampusServiceUiState<CampusCardPage>> = _state.asStateFlow()

    private var cookies: CampusCardCookies? = null
    private var token: String? = null
    private var job: Job? = null

    /** 用当前浏览器会话加载；完全没有会话线索时直接进入未登录，不发请求。 */
    internal fun load(session: CampusCardCookies?) {
        token = null
        if (session == null || !session.hasSession) {
            cookies = null
            job?.cancel()
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        cookies = session
        fetch()
    }

    private fun fetch() {
        val session = cookies ?: return
        job?.cancel()
        _state.value = CampusServiceUiState.Loading
        job = viewModelScope.launch {
            try {
                val activeToken = token ?: client.acquireToken(session::headerFor).also { token = it }
                val summary = client.fetchSummary(activeToken)
                // 流水是独立请求：失败时保留余额与汇总，只把流水区标为失败。
                var transactions = CampusCardTransactions(items = emptyList(), total = 0)
                var transactionsFailed = false
                try {
                    transactions = client.fetchTransactions(activeToken)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    transactionsFailed = true
                }
                _state.value = CampusServiceUiState.Ready(
                    CampusCardPage(
                        summary = summary,
                        transactions = transactions,
                        transactionsFailed = transactionsFailed,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CampusCardSignInRequiredException) {
                token = null
                _state.value = CampusServiceUiState.RequiresSignIn
            } catch (failure: CampusCardNetworkException) {
                // 余额与流水是两条独立请求的前后两步，失败原因原样展示便于用户判断。
                _state.value = CampusServiceUiState.Failed(failure.reason())
            } catch (failure: CampusCardParseException) {
                _state.value = CampusServiceUiState.Failed(failure.reason())
            } catch (_: Exception) {
                _state.value = CampusServiceUiState.Failed(
                    getApplication<Application>().getString(R.string.campus_card_error_unknown),
                )
            }
        }
    }

    private fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() }
        ?: getApplication<Application>().getString(R.string.campus_card_error_unknown)
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
        portalHeader = CampusCookieJar.header(CampusCardSessionPolicy.PORTAL_ORIGIN),
        casHeader = CampusCookieJar.header(CampusCardSessionPolicy.CAS_ORIGIN),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampusCardScreen(
    viewModel: CampusCardViewModel,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
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
                    IconButton(onClick = onReload) {
                        if (state is CampusServiceUiState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                                contentDescription = stringResource(R.string.campus_card_refresh),
                            )
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
                is CampusServiceUiState.Ready -> CampusCardContent(page = current.value)
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
private fun CampusCardContent(page: CampusCardPage) {
    val tokens = HitaTheme.tokens
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = tokens.spacing.lg,
            end = tokens.spacing.lg,
            top = tokens.spacing.sm,
            bottom = tokens.spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
    ) {
        item { BalanceCard(page) }
        item { SpendingCard(page.summary.spending) }
        item {
            Text(
                text = stringResource(R.string.campus_card_transactions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (page.transactionsFailed) {
            item {
                Text(
                    text = stringResource(R.string.campus_card_transactions_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (page.transactions.items.isEmpty()) {
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
private fun SpendingCard(spending: CampusCardSpending?) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        if (spending == null) {
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
                    label = stringResource(R.string.campus_card_spending_month),
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
    onCancel: () -> Unit,
    onLoggedIn: () -> Unit,
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
                    onLoggedIn()
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
    onSessionReady: () -> Unit,
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
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = !isCampusSecureUrl(request?.url?.toString())

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        !isCampusSecureUrl(url)

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url == null) return
                        onPageFinished()
                        val state = CampusCardSessionPolicy.state(
                            url = url,
                            cookieNames = readCampusCardCookies().portalCookieNames,
                        )
                        if (state == CampusCardSessionPolicy.State.SignedIn) onSessionReady()
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
    return host.endsWith("hitsz.edu.cn") || host.endsWith("hit.edu.cn")
}
