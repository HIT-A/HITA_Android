package cn.limpu.hita.ui.resource

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.model.resource.UnifiedResourceItem
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.LogUtils
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLEncoder

@AndroidEntryPoint
class UnifiedResourceSearchActivity : AppCompatActivity() {
    private val viewModel: UnifiedResourceSearchViewModel by viewModels()
    private var handleBackInCompose: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        super.onCreate(savedInstanceState)

        val mode = runCatching {
            ActivityUtils.CourseResourceMode.valueOf(
                intent.getStringExtra("mode") ?: ActivityUtils.CourseResourceMode.VIEW.name
            )
        }.getOrDefault(ActivityUtils.CourseResourceMode.VIEW)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleBackInCompose?.invoke() == true) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        setContent {
            HitaComposeTheme() {
                UnifiedResourceSearchScreen(
                    viewModel = viewModel,
                    initialQuery = intent.getStringExtra("query").orEmpty(),
                    initialBrowsePath = intent.getStringExtra("browsePath").orEmpty(),
                    initialBrowseSource = intent.getStringExtra("browseSource")?.let { raw ->
                        runCatching { ResourceSource.valueOf(raw) }.getOrNull()
                    },
                    initialBrowseTitle = intent.getStringExtra("browseTitle").orEmpty(),
                    registerBackHandler = { handleBackInCompose = it },
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenHoaCourse = { item ->
                        if (
                            mode == ActivityUtils.CourseResourceMode.SUBMIT &&
                            item.repoType != "multi-project"
                        ) {
                            ActivityUtils.startCourseContributionActivity(
                                this,
                                item.repoName,
                                item.courseName,
                                item.courseCode,
                                item.repoType,
                            )
                        } else {
                            ActivityUtils.startCourseReadmeActivity(
                                this,
                                item.repoName,
                                item.courseName,
                                item.courseCode,
                                item.repoType,
                            )
                        }
                    },
                    onOpenMarkdown = { url, title ->
                        startActivity(Intent(this, MarkdownViewerActivity::class.java).apply {
                            putExtra("url", url)
                            putExtra("title", title)
                        })
                    },
                    onOpenExternalUrl = { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    onDownload = { url, fileName -> downloadFile(url, fileName) }
                )
            }
        }
    }

    private fun downloadFile(url: String, fileName: String) {
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("正在下载 $fileName")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            dm.enqueue(request)
            Toast.makeText(this, "开始下载: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogUtils.e("Download failed: ${e.message}")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e2: Exception) {
                Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private data class BrowseState(
    val path: String,
    val source: ResourceSource,
    val breadcrumb: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedResourceSearchScreen(
    viewModel: UnifiedResourceSearchViewModel,
    initialQuery: String,
    initialBrowsePath: String,
    initialBrowseSource: ResourceSource?,
    initialBrowseTitle: String,
    registerBackHandler: (() -> Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenHoaCourse: (UnifiedResourceItem.HoaCourse) -> Unit,
    onOpenMarkdown: (String, String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onDownload: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val tokens = HitaTheme.tokens
    val screen by viewModel.state.collectAsState()
    // 浏览模式与列表模式共用一份状态：目录栈非空就是浏览。
    val isBrowseMode = screen.stack.isNotEmpty()
    val browseStack = screen.stack
    val search = screen.search
    val directory = screen.directory
    val searchItems = search?.items.orEmpty()
    val entryItems = directory?.entries.orEmpty()
    var query by remember(initialQuery) {
        val normalized = CourseCodeUtils.normalize(initialQuery) ?: initialQuery
        mutableStateOf(normalized)
    }
    // 搜索历史：只在查询为空时展示，点一条即可回填并重搜。
    val historyStore = remember {
        cn.limpu.hita.data.source.preference.ResourceSearchHistoryStore(
            cn.limpu.hita.data.source.preference.SharedPreferenceStore {
                context.getSharedPreferences("resource_search", android.content.Context.MODE_PRIVATE)
            }
        )
    }
    var history by remember { mutableStateOf(historyStore.list()) }

    fun enterBrowseMode(item: UnifiedResourceItem.ExternalCourse) {
        viewModel.browse(item.path, item.source, item.courseName)
    }

    fun navigateInto(entry: ExternalResourceEntry) {
        if (!entry.isDir) {
            val url = entry.downloadUrl
            if (url.startsWith("https://fireworks.jwyihao.top")) {
                onOpenExternalUrl(url)
                return
            }

            val rawUrl = if (entry.path.isNotBlank()) {
                val repo = when (entry.source) {
                    ResourceSource.HITCS -> "HITLittleZheng/HITCS"
                    ResourceSource.FIREWORKS -> "HIT-Fireworks/fireworks-notes-society"
                }
                val encodedPath = entry.path.split("/").joinToString("/") { segment ->
                    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                }
                "https://raw.githubusercontent.com/$repo/main/$encodedPath"
            } else if (url.isNotBlank() && !url.startsWith("https://fireworks.")) {
                url
            } else {
                return
            }
            val downloadUrl = "https://ghproxy.net/$rawUrl"
            if (entry.name.endsWith(".md", ignoreCase = true)) {
                onOpenMarkdown(downloadUrl, entry.name)
            } else {
                onDownload(downloadUrl, entry.name)
            }
            return
        }
        viewModel.browse(entry.path, entry.source, entry.name)
    }

    fun startSearch() {
        val normalized = CourseCodeUtils.normalize(query.trim()) ?: query.trim()
        query = normalized
        keyboardController?.hide()
        if (isBrowseMode) {
            viewModel.exitBrowse()
        }
        if (normalized.isNotBlank()) {
            historyStore.record(normalized)
            history = historyStore.list()
        }
        viewModel.search(normalized)
    }

    fun handleBack(): Boolean {
        if (viewModel.back()) return true
        if (isBrowseMode) {
            viewModel.exitBrowse()
            return true
        }
        return false
    }

    LaunchedEffect(Unit) {
        registerBackHandler { handleBack() }
        val folder = initialBrowseSource?.takeIf { initialBrowsePath.isNotBlank() }?.let { source ->
            ResourceFolder(
                ResourceFolderKey(source, initialBrowsePath),
                initialBrowseTitle.takeIf(String::isNotBlank) ?: initialBrowsePath.substringAfterLast("/"),
            )
        }
        viewModel.restore(query, folder)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(
                        if (isBrowseMode) {
                            R.string.unified_resource_browse
                        } else {
                            R.string.unified_resource_title
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { if (!handleBack()) onBack() }) {
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

        if (isBrowseMode) {
            Text(
                text = browseStack.lastOrNull()?.breadcrumb.orEmpty(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = tokens.spacing.lg,
                        end = tokens.spacing.lg,
                        bottom = tokens.spacing.sm
                    )
            )
        } else {
            UnifiedSearchBar(
                query = query,
                onQueryChange = { query = it },
                onSearch = { startSearch() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.lg)
            )
            Spacer(modifier = Modifier.height(tokens.spacing.sm))
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                if (isBrowseMode) {
                    items(entryItems, key = { "entry:${it.source}:${it.path}:${it.name}:${it.isDir}" }) { entry ->
                        ExternalEntryCard(
                            entry = entry,
                            onClick = { navigateInto(entry) }
                        )
                    }
                } else {
                    if (query.isBlank() && history.isNotEmpty()) {
                        item(key = "history-header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = tokens.spacing.lg,
                                        end = tokens.spacing.sm,
                                        top = tokens.spacing.sm,
                                        bottom = tokens.spacing.xs,
                                    )
                            ) {
                                Text(
                                    text = "最近搜索",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    historyStore.clear()
                                    history = emptyList()
                                }) {
                                    Text(text = "清空", fontSize = 13.sp)
                                }
                            }
                        }
                        items(history, key = { "history:$it" }) { term ->
                            Text(
                                text = term,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        query = term
                                        startSearch()
                                    }
                                    .padding(
                                        horizontal = tokens.spacing.lg,
                                        vertical = tokens.spacing.md,
                                    )
                            )
                        }
                    }
                    items(
                        searchItems,
                        key = { item ->
                            when (item) {
                                is UnifiedResourceItem.HoaCourse ->
                                    "hoa:${item.repoType}:${item.repoName}:${item.courseCode}:${item.courseName}"
                                is UnifiedResourceItem.ExternalCourse ->
                                    "external:${item.source}:${item.path}:${item.courseName}"
                            }
                        }
                    ) { item ->
                        UnifiedResourceCard(
                            item = item,
                            onClick = {
                                when (item) {
                                    is UnifiedResourceItem.HoaCourse -> onOpenHoaCourse(item)
                                    is UnifiedResourceItem.ExternalCourse -> enterBrowseMode(item)
                                }
                            }
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(tokens.spacing.lg))
                }
            }

            when {
                isBrowseMode -> {
                    val loading = directory?.loading == true
                    // 空态只能来自成功的空结果；失败给可点的重试，不冒充「没有资料」。
                    if (directory?.failed == true && entryItems.isEmpty()) {
                        ResourcePlaceholder(
                            message = stringResource(R.string.external_resource_failed),
                            action = stringResource(R.string.resource_retry),
                            onAction = { browseStack.lastOrNull()?.let { viewModel.browse(it.key.path, it.key.source, it.title) } },
                        )
                    } else if (!loading && entryItems.isEmpty()) {
                        ResourcePlaceholder(message = stringResource(R.string.external_resource_empty))
                    }
                }
                emptyText.isNotBlank() -> {
                    Text(
                        text = emptyText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(tokens.spacing.xl)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourcePlaceholder(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = HitaTheme.tokens
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(tokens.spacing.xl),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun UnifiedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(tokens.radius.xl),
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = tokens.spacing.sm, end = tokens.spacing.md)
        ) {
            IconButton(onClick = onSearch) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_search_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = tokens.spacing.md),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.unified_resource_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun UnifiedResourceCard(
    item: UnifiedResourceItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(tokens.radius.lg),
        modifier = modifier
            .fillMaxWidth()
            .padding(tokens.spacing.sm)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(tokens.spacing.lg)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = tokens.spacing.xs)
                    )
                }
            }
            SourceChip(
                text = item.sourceTag,
                color = colorResource(item.sourceColor),
                modifier = Modifier.padding(start = tokens.spacing.sm)
            )
        }
    }
}

@Composable
private fun SourceChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = CircleShape,
        modifier = modifier
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = tokens.spacing.sm,
                vertical = tokens.spacing.xs
            )
        )
    }
}

@Composable
private fun ExternalEntryCard(
    entry: ExternalResourceEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(tokens.radius.md),
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = tokens.spacing.lg,
                top = tokens.spacing.xs,
                end = tokens.spacing.lg,
                bottom = tokens.spacing.xs
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(tokens.spacing.md)
        ) {
            Icon(
                painter = painterResource(
                    if (entry.isDir) {
                        R.drawable.ic_baseline_menu_24
                    } else {
                        R.drawable.ic_baseline_cloud_download_24
                    }
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = tokens.spacing.md)
            ) {
                Text(
                    text = entry.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.isDir && entry.size > 0) {
                    Text(
                        text = formatFileSize(entry.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
