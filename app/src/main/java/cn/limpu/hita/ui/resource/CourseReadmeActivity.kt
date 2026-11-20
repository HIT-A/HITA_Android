package cn.limpu.hita.ui.resource

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.resource.CourseReadmeData
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.ActivityUtils
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.jsoup.Jsoup
import java.net.URI

@AndroidEntryPoint
class CourseReadmeActivity : AppCompatActivity() {
    private val viewModel: CourseReadmeViewModel by viewModels()

    private lateinit var repoName: String
    private lateinit var courseName: String
    private lateinit var courseCode: String
    private lateinit var repoType: String

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate(savedInstanceState)

        repoName = intent.getStringExtra("repoName") ?: ""
        courseName = intent.getStringExtra("courseName") ?: repoName
        courseCode = intent.getStringExtra("courseCode") ?: repoName
        repoType = intent.getStringExtra("repoType") ?: "normal"

        setContent {
            HitaComposeTheme() {
                CourseReadmeScreen(
                    viewModel = viewModel,
                    courseName = courseName,
                    courseCode = courseCode,
                    repoName = repoName,
                    repoType = repoType,
                    preprocessReadme = ::preprocessReadme,
                    resolveReadmeLink = ::resolveReadmeLink,
                    openLink = ::openLink,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.load(repoName)
    }

    private fun preprocessReadme(markdown: String): String {
        val withTables = convertHtmlTables(markdown)
        val startPattern = Regex("\\{\\{[%<]\\s*details\\s+([^%>]+)\\s*[%>]\\}\\}", RegexOption.IGNORE_CASE)
        val endPattern = Regex("\\{\\{[%<]\\s*/details\\s*[%>]\\}\\}", RegexOption.IGNORE_CASE)
        val replacedStart = startPattern.replace(withTables) { match ->
            val attrs = match.groupValues[1]
            val title = Regex("title\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1) ?: getString(R.string.course_resource_open)
            val closed = Regex("closed\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.trim()?.lowercase()
            val openAttr = if (closed == "true") "" else " open"
            "<details$openAttr><summary>$title</summary>"
        }
        return endPattern.replace(replacedStart, "</details>")
    }

    private fun convertHtmlTables(markdown: String): String {
        val tablePattern = Regex("(?is)<table[^>]*>.*?</table>")
        return tablePattern.replace(markdown) { match ->
            runCatching {
                val doc = Jsoup.parse(match.value)
                val table = doc.selectFirst("table") ?: return@replace match.value
                val rows = table.select("tr")
                if (rows.isEmpty()) return@replace match.value
                val cellsList = rows.map { row ->
                    row.select("th,td").map { it.text().trim() }
                }
                val maxCols = cellsList.maxOfOrNull { it.size } ?: 0
                if (maxCols == 0) return@replace match.value
                fun pad(row: List<String>): List<String> {
                    if (row.size >= maxCols) return row
                    return row + List(maxCols - row.size) { "" }
                }
                val header = pad(cellsList.first())
                val headerRow = header.joinToString(" | ")
                val separator = List(maxCols) { "---" }.joinToString(" | ")
                val body = cellsList.drop(1).joinToString("\n") { row ->
                    pad(row).joinToString(" | ")
                }
                listOf(headerRow, separator, body).filter { it.isNotBlank() }.joinToString("\n")
            }.getOrDefault(match.value)
        }
    }

    private fun resolveReadmeLink(link: String, source: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link
        }
        val base = source.trim()
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return runCatching { URI(base).resolve(link).toString() }.getOrDefault(link)
        }
        return link
    }

    private fun openLink(link: String) {
        // 课程资料里的链接常来自第三方 README；只允许白名单 https 目标，
        // 其余提示并保留复制能力，避免任意 scheme 或站外跳转。
        val target = cn.limpu.hita.ui.subject.ExternalLinkPolicy.allowed(link)
        if (target == null) {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(link, link))
            android.widget.Toast.makeText(
                this,
                getString(R.string.external_link_unsupported) + " · " + getString(R.string.external_link_copied),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseReadmeScreen(
    viewModel: CourseReadmeViewModel,
    courseName: String,
    courseCode: String,
    repoName: String,
    repoType: String,
    preprocessReadme: (String) -> String,
    resolveReadmeLink: (String, String) -> String,
    openLink: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tokens = HitaTheme.tokens
    val state by viewModel.readmeLiveData.observeAsState()
    val current = state
    val readmeData = current?.data

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = courseName,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = tokens.spacing.lg,
                    top = tokens.spacing.sm,
                    end = tokens.spacing.lg,
                    bottom = tokens.spacing.sm
                )
        ) {
            Text(
                text = courseCode,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val source = current?.data?.source
            if (!source.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.course_readme_source, source),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = tokens.spacing.xs)
                )
            }
            OutlinedButton(
                onClick = {
                    ActivityUtils.startCourseContributionActivity(
                        context,
                        repoName,
                        courseName,
                        courseCode,
                        repoType
                    )
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.padding(top = tokens.spacing.sm)
            ) {
                Text(text = stringResource(R.string.course_resource_contribute))
            }
        }

        when {
            current == null || current.state == DataState.STATE.LOADING -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            current.state == DataState.STATE.SUCCESS && readmeData != null -> {
                ReadmeContent(
                    data = readmeData,
                    preprocessReadme = preprocessReadme,
                    resolveReadmeLink = resolveReadmeLink,
                    openLink = openLink,
                    contentPadding = PaddingValues(tokens.spacing.lg)
                )
            }

            else -> {
                val rawMessage = current.message?.trim().orEmpty()
                val friendly = if (rawMessage.contains("invalid repo name", ignoreCase = true)) {
                    stringResource(R.string.course_readme_missing)
                } else {
                    rawMessage.ifBlank { stringResource(R.string.course_resource_failed) }
                }
                LaunchedEffect(friendly) {
                    Toast.makeText(context, friendly, Toast.LENGTH_LONG).show()
                }
                Text(
                    text = friendly,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(tokens.spacing.lg)
                )
            }
        }
    }
}

@Composable
private fun ReadmeContent(
    data: CourseReadmeData,
    preprocessReadme: (String) -> String,
    resolveReadmeLink: (String, String) -> String,
    openLink: (String) -> Unit,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val markdown = remember(data.markdown) { preprocessReadme(data.markdown) }
    val markwon = remember(context, data.source) {
        Markwon.builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(JLatexMathPlugin.create(15f))
            .usePlugin(GlideImagesPlugin.create(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _: View, link: String ->
                        openLink(resolveReadmeLink(link, data.source))
                    }
                }
            })
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                    // 可选中：README 里的命令、代码片段与链接可直接长按复制。
                    setTextIsSelectable(true)
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
