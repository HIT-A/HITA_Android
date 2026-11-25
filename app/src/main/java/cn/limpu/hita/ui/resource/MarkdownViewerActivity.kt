package cn.limpu.hita.ui.resource

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.LogUtils
import com.limpu.style.ThemeTools
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup

class MarkdownViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url")
        val title = intent.getStringExtra("title").orEmpty()
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            HitaComposeTheme() {
                MarkdownViewerScreen(
                    title = title,
                    url = url,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}

private sealed interface MarkdownUiState {
    data object Loading : MarkdownUiState
    data class Success(val markdown: String) : MarkdownUiState
    data class Error(val message: String) : MarkdownUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownViewerScreen(
    title: String,
    url: String,
    onBack: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val scrollState = rememberScrollState()
    var state by remember(url) { mutableStateOf<MarkdownUiState>(MarkdownUiState.Loading) }

    var attempt by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        state = MarkdownUiState.Loading
        state = loadMarkdown(url)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = stringResource(R.string.today_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        when (val current = state) {
            MarkdownUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = tokens.spacing.xl),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            is MarkdownUiState.Error -> {
                Column(Modifier.padding(tokens.spacing.lg)) {
                    Text(text = current.message, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.stale_data_retry)) }
                }
            }
            is MarkdownUiState.Success -> {
                MarkdownContent(
                    markdown = current.markdown,
                    scrollState = scrollState,
                    contentPadding = PaddingValues(tokens.spacing.lg)
                )
            }
        }
    }
}

@Composable
private fun MarkdownContent(
    markdown: String,
    scrollState: ScrollState,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(JLatexMathPlugin.create(15f))
            .usePlugin(GlideImagesPlugin.create(context))
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            factory = {
                TextView(it).apply {
                    textSize = 15f
                    setTextColor(textColor)
                    movementMethod = LinkMovementMethod.getInstance()
                    linksClickable = true
                }
            },
            update = { textView ->
                textView.setTextColor(textColor)
                markwon.setMarkdown(textView, markdown)
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private suspend fun loadMarkdown(url: String): MarkdownUiState {
    return withContext(Dispatchers.IO) {
        try {
            val response = Jsoup.connect(url)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(15000)
                .header("Accept", "text/plain,text/markdown,*/*")
                .method(Connection.Method.GET)
                .execute()

            if (response.statusCode() >= 400) {
                MarkdownUiState.Error("加载失败: HTTP ${response.statusCode()}")
            } else {
                MarkdownUiState.Success(response.body())
            }
        } catch (e: Exception) {
            LogUtils.e("MarkdownViewer load failed", e)
            MarkdownUiState.Error("加载失败: ${e.message}")
        }
    }
}
