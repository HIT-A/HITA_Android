package cn.limpu.hita.ui.main.blog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import cn.limpu.hita.R
import cn.limpu.hita.data.model.blog.BlogArticle
import cn.limpu.hita.data.model.blog.BlogNode
import cn.limpu.hita.data.model.blog.BlogSeriesGrouper
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.notice.CampusNotice
import cn.limpu.hita.data.repository.CampusNoticeSyncError
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.ui.main.MainTab
import cn.limpu.hita.ui.blog.BlogReaderActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.design.hitaUsesMainBackdrop
import cn.limpu.hita.ui.notice.InfoPortalLoginActivity
import cn.limpu.hita.utils.LogUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@AndroidEntryPoint
class BlogFragment : androidx.fragment.app.Fragment() {
    @Inject lateinit var easRepository: EASRepository
    @Inject lateinit var credentialStore: EasCredentialStore

    private val viewModel: BlogViewModel by viewModels()
    private val noticeViewModel: CampusNoticeViewModel by viewModels()
    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            noticeViewModel.refresh(EASToken.Campus.SHENZHEN)
        }
    }

    /** 门户会话过期后先自动重登一次；失败才让用户手点登录。 */
    private val silentLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            noticeViewModel.refresh(EASToken.Campus.SHENZHEN)
        } else {
            LogUtils.d("notice portal silent renewal failed, asking user")
        }
    }

    private fun launchPortalLogin(silent: Boolean) {
        val activity = activity ?: return
        val intent = Intent(activity, InfoPortalLoginActivity::class.java)
        if (silent) intent.putExtra(InfoPortalLoginActivity.EXTRA_SILENT_MODE, true)
        runCatching {
            (if (silent) silentLoginLauncher else loginLauncher).launch(intent)
        }.onFailure { LogUtils.e("cannot launch notice portal login", it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme {
                    val easToken by easRepository.observeEasToken()
                        .observeAsState(easRepository.getEasToken())
                    if (MainTab.showsInfo(easToken)) {
                        if (easToken.campus == EASToken.Campus.SHENZHEN) {
                            InfoTabScreen(
                                campus = EASToken.Campus.SHENZHEN,
                                blogViewModel = viewModel,
                                noticeViewModel = noticeViewModel,
                                onOpenArticle = { article ->
                                    startActivity(
                                        Intent(requireContext(), BlogReaderActivity::class.java)
                                            .putExtra(BlogReaderActivity.EXTRA_GUID, article.guid)
                                    )
                                },
                                onLoginPortal = { launchPortalLogin(silent = false) },
                                onSilentLoginPortal = {
                                    // 没有已保存凭据时静默重登必然失败：CAS 表单页无人填充，
                                    // 只会「打开→勾选免登录→自动退出」。这时直接走交互登录，
                                    // 把页面留在前台等用户输入。
                                    if (credentialStore.get(EASToken.Campus.SHENZHEN) != null) {
                                        launchPortalLogin(silent = true)
                                    } else {
                                        launchPortalLogin(silent = false)
                                    }
                                },
                            )
                        } else {
                            CampusNoticeScreen(
                                viewModel = noticeViewModel,
                                campus = easToken.campus,
                                onLogin = {},
                                refreshOnEnter = true,
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (MainTab.showsBlog(easRepository.getEasToken())) {
            viewModel.markTabOpened()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoTabScreen(
    campus: EASToken.Campus,
    blogViewModel: BlogViewModel,
    noticeViewModel: CampusNoticeViewModel,
    onOpenArticle: (BlogArticle) -> Unit,
    onLoginPortal: () -> Unit,
    onSilentLoginPortal: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val transparentBackdrop = hitaUsesMainBackdrop()
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            noticeViewModel.syncOnPageOpen(campus)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (transparentBackdrop) Modifier
                else Modifier.background(MaterialTheme.colorScheme.background)
            ),
    ) {
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (transparentBackdrop) 0.35f else 1f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                text = { Text(stringResource(R.string.blog_tab_blog)) },
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                text = { Text(stringResource(R.string.blog_tab_notice)) },
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            if (page == 0) {
                BlogScreen(viewModel = blogViewModel, onOpenArticle = onOpenArticle)
            } else {
                CampusNoticeScreen(
                    viewModel = noticeViewModel,
                    campus = campus,
                    onLogin = onLoginPortal,
                    onSilentLogin = onSilentLoginPortal,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CampusNoticeScreen(
    viewModel: CampusNoticeViewModel,
    campus: EASToken.Campus,
    onLogin: () -> Unit,
    refreshOnEnter: Boolean = false,
    onSilentLogin: (() -> Unit)? = null,
) {
    val notices by remember(campus) { viewModel.noticesFor(campus) }.observeAsState(emptyList())
    val refreshing by viewModel.refreshing.observeAsState(false)
    val error by viewModel.syncError.observeAsState(null)
    val lxgw = rememberLxgwFontFamily()
    val context = LocalContext.current
    LaunchedEffect(campus) {
        if (refreshOnEnter) viewModel.syncOnPageOpen(campus)
    }
    // 会话失效时先无感重登一次：能复用已保存凭据就悄悄恢复，失败才露出登录按钮。
    // 每次进入该校区只自动尝试一次，避免失败后无限弹登录窗口。
    val silentRetry = remember(campus) { mutableStateOf(false) }
    LaunchedEffect(error, campus) {
        if (error == CampusNoticeSyncError.NEED_LOGIN && onSilentLogin != null && !silentRetry.value) {
            silentRetry.value = true
            onSilentLogin()
        }
    }
    val failureText = when (error) {
        CampusNoticeSyncError.NEED_LOGIN -> stringResource(R.string.campus_notice_need_login)
        CampusNoticeSyncError.NEED_CAMPUS_NET -> stringResource(R.string.campus_notice_need_campus_net)
        CampusNoticeSyncError.FAILED -> stringResource(R.string.campus_notice_load_failed)
        null -> null
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (failureText != null) {
            SyncFailureCaption(
                text = failureText,
                fontFamily = lxgw,
                actionLabel = when (error) {
                    CampusNoticeSyncError.NEED_LOGIN -> stringResource(R.string.campus_notice_login)
                    null -> null
                    else -> stringResource(R.string.blog_retry)
                },
                onAction = when (error) {
                    CampusNoticeSyncError.NEED_LOGIN -> onLogin
                    null -> null
                    else -> ({ viewModel.refresh(campus) })
                },
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh(campus) },
            modifier = Modifier.weight(1f),
        ) {
            when {
                notices.isEmpty() && refreshing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                notices.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (error == null) {
                            Text(
                                text = stringResource(R.string.campus_notice_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = lxgw,
                                fontSize = 16.sp,
                            )
                        }
                        if (error == CampusNoticeSyncError.NEED_LOGIN) {
                            TextButton(onClick = onLogin) {
                                Text(stringResource(R.string.campus_notice_login), fontFamily = lxgw)
                            }
                        } else {
                            TextButton(onClick = { viewModel.refresh(campus) }) {
                                Text(stringResource(R.string.blog_retry), fontFamily = lxgw)
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(notices, key = { "${it.campus}:${it.id}" }) { notice ->
                            CampusNoticeRow(
                                notice = notice,
                                fontFamily = lxgw,
                                onOpen = { openNoticeLink(context, notice.title, notice.url) },
                                onCopy = { copyNoticeLink(context, notice.title, notice.url) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncFailureCaption(
    text: String,
    fontFamily: FontFamily,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = fontFamily,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(actionLabel, fontFamily = fontFamily, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CampusNoticeRow(
    notice: CampusNotice,
    fontFamily: FontFamily,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    val shape = hitaStyleCardShape(roundedRadius = 12.dp, cyberCut = 8.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hitaGlassCardModifier(shape, elevation = 6.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onCopy),
        shape = shape,
        colors = hitaGlassCardColors(glassAlpha = 0.46f),
        border = hitaGlassCardBorder(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = notice.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = fontFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (notice.pubDateMillis > 0L) {
                Text(
                    text = formatBlogDate(notice.pubDateMillis),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = fontFamily,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

private fun copyNoticeLink(context: Context, label: String, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
    Toast.makeText(context, R.string.useful_links_copied, Toast.LENGTH_SHORT).show()
}

private fun openNoticeLink(context: Context, label: String, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        copyNoticeLink(context, label, url)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlogScreen(
    viewModel: BlogViewModel,
    onOpenArticle: (BlogArticle) -> Unit,
) {
    val articles by viewModel.articles.observeAsState(emptyList())
    val unread by viewModel.unreadGuids.observeAsState(emptySet())
    val refreshing by viewModel.refreshing.observeAsState(false)
    val error by viewModel.syncError.observeAsState(null)
    val nodes = remember(articles) { BlogSeriesGrouper.group(articles) }
    val lxgw = rememberLxgwFontFamily()
    val transparentBackdrop = hitaUsesMainBackdrop()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (transparentBackdrop) Modifier
                else Modifier.background(MaterialTheme.colorScheme.background)
            ),
    ) {
        if (error != null) {
            SyncFailureCaption(
                text = stringResource(R.string.blog_load_failed),
                fontFamily = lxgw,
                actionLabel = stringResource(R.string.blog_retry),
                onAction = { viewModel.refresh() },
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                articles.isEmpty() && refreshing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                articles.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (error == null) {
                            Text(
                                text = stringResource(R.string.blog_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = lxgw,
                                fontSize = 16.sp,
                            )
                        }
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text(stringResource(R.string.blog_retry), fontFamily = lxgw)
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(nodes, key = { it.article.guid }) { node ->
                            BlogNodeCard(
                                node = node,
                                unread = unread,
                                fontFamily = lxgw,
                                onOpenArticle = onOpenArticle,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlogNodeCard(
    node: BlogNode,
    unread: Set<String>,
    fontFamily: FontFamily,
    onOpenArticle: (BlogArticle) -> Unit,
) {
    val shape = hitaStyleCardShape(roundedRadius = 16.dp, cyberCut = 12.dp)
    val hasUnread = node.article.guid in unread || node.children.any { subtreeUnread(it, unread) }
    var userToggled by remember(node.article.guid) { mutableStateOf(false) }
    var expanded by remember(node.article.guid) { mutableStateOf(false) }
    LaunchedEffect(hasUnread, node.article.guid) {
        if (!userToggled && hasUnread && node.isSeries) expanded = true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hitaGlassCardModifier(shape = shape, elevation = 10.dp),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            BlogArticleRow(
                article = node.article,
                unread = node.article.guid in unread,
                fontFamily = fontFamily,
                isSeries = node.isSeries,
                expanded = expanded,
                onToggleSeries = {
                    userToggled = true
                    expanded = !expanded
                },
                onOpen = { onOpenArticle(node.article) },
            )
            AnimatedVisibility(visible = node.isSeries && expanded) {
                Column(modifier = Modifier.padding(top = 8.dp, start = 8.dp)) {
                    node.children.forEach { child ->
                        BlogChildTree(
                            node = child,
                            unread = unread,
                            fontFamily = fontFamily,
                            onOpenArticle = onOpenArticle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlogChildTree(
    node: BlogNode,
    unread: Set<String>,
    fontFamily: FontFamily,
    onOpenArticle: (BlogArticle) -> Unit,
) {
    var expanded by remember(node.article.guid) { mutableStateOf(node.isSeries) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        BlogArticleRow(
            article = node.article,
            unread = node.article.guid in unread,
            fontFamily = fontFamily,
            isSeries = node.isSeries,
            expanded = expanded,
            compact = true,
            onToggleSeries = { expanded = !expanded },
            onOpen = { onOpenArticle(node.article) },
        )
        AnimatedVisibility(visible = node.isSeries && expanded) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                node.children.forEach { child ->
                    BlogChildTree(
                        node = child,
                        unread = unread,
                        fontFamily = fontFamily,
                        onOpenArticle = onOpenArticle,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlogArticleRow(
    article: BlogArticle,
    unread: Boolean,
    fontFamily: FontFamily,
    isSeries: Boolean,
    expanded: Boolean,
    compact: Boolean = false,
    onToggleSeries: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = if (isSeries) onToggleSeries else onOpen)
            .padding(vertical = if (compact) 6.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(10.dp), contentAlignment = Alignment.Center) {
            if (unread) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = fontFamily,
                fontWeight = if (compact) FontWeight.Medium else FontWeight.SemiBold,
                fontSize = if (compact) 15.sp else 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatBlogDate(article.pubDateMillis),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = fontFamily,
                fontSize = 12.sp,
            )
        }
        if (isSeries) {
            Text(
                text = "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
    }
}

@Composable
private fun rememberLxgwFontFamily(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        FontFamily(Typeface.createFromAsset(context.assets, "blog/lxgw-wenkai.ttf"))
    }
}

private fun subtreeUnread(node: BlogNode, unread: Set<String>): Boolean {
    if (node.article.guid in unread) return true
    return node.children.any { subtreeUnread(it, unread) }
}

private fun formatBlogDate(millis: Long): String {
    if (millis <= 0L) return ""
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    format.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return format.format(Date(millis))
}
