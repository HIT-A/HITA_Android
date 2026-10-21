package cn.limpu.hita.ui.about

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.HapticFeedbackConstants
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.text.HtmlCompat
import com.limpu.component.data.DataState
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.DeveloperCredit
import cn.limpu.hita.ui.design.DeveloperCredits
import cn.limpu.hita.data.model.ReleaseHistoryItem
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.LogUtils
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Suppress("DEPRECATION")
@AndroidEntryPoint
class ActivityAbout : AppCompatActivity() {
    private val viewModel: AboutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        super.onCreate(savedInstanceState)

        setContent {
            HitaComposeTheme() {
                AboutScreen(
                    viewModel = viewModel,
                    versionText = currentVersionText(),
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenAgreement = {
                        UserAgreementDialog().show(supportFragmentManager, "a")
                    },
                    onShowUpdate = { result ->
                        ActivityUtils.showUpdateNotificationForce(result, this)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun currentVersionText(): String {
        var packageInfo: PackageInfo? = null
        try {
            packageInfo = packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            LogUtils.e("Failed to get package info for version", e)
        }
        return getString(R.string.version) + packageInfo?.versionName.orEmpty()
    }
}

private enum class UpdateButtonState {
    Idle,
    Loading,
    Success,
    Failed
}

@Composable
private fun AboutScreen(
    viewModel: AboutViewModel,
    versionText: String,
    onBack: () -> Unit,
    onOpenAgreement: () -> Unit,
    onShowUpdate: (com.limpu.hitauser.data.model.CheckUpdateResult) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val tokens = HitaTheme.tokens
    val aboutState by viewModel.aboutPageLiveData.observeAsState()
    val releaseState by viewModel.releaseHistoryLiveData.observeAsState()
    val checkState by viewModel.checkUpdateResult.observeAsState()
    var aboutHtml by remember { mutableStateOf("") }
    var buttonState by remember { mutableStateOf(UpdateButtonState.Idle) }
    var showRewardCode by remember { mutableStateOf(false) }

    LaunchedEffect(aboutState) {
        aboutState?.data?.let { aboutHtml = it }
    }

    LaunchedEffect(checkState) {
        val current = checkState ?: return@LaunchedEffect
        if (current.state == DataState.STATE.NOTHING) return@LaunchedEffect
        if (current.state == DataState.STATE.LOADING) {
            buttonState = UpdateButtonState.Loading
            return@LaunchedEffect
        }
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.CONTEXT_CLICK
            }
        )
        buttonState = if (current.state == DataState.STATE.SUCCESS) {
            UpdateButtonState.Success
        } else {
            UpdateButtonState.Failed
        }
        if (current.state == DataState.STATE.SUCCESS) {
            current.data?.let { result ->
                if (result.shouldUpdate) {
                    onShowUpdate(result)
                } else {
                    val msg = if (BuildConfig.DEBUG && result.downloadCount > 0) {
                        context.getString(R.string.already_up_to_date) +
                            " · 累计下载 ${result.downloadCount} 次"
                    } else {
                        context.getString(R.string.already_up_to_date)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, R.string.check_for_update_failed, Toast.LENGTH_SHORT).show()
        }
        kotlinx.coroutines.delay(900)
        buttonState = UpdateButtonState.Idle
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            AboutHeader(
                versionText = versionText,
                onBack = onBack,
                onOpenAgreement = onOpenAgreement
            )
            Surface(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(
                    topStart = tokens.radius.xl,
                    topEnd = tokens.radius.xl
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-36).dp)
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = tokens.spacing.xl,
                        top = 36.dp,
                        end = tokens.spacing.xl,
                        bottom = 104.dp
                    )
                ) {
                    HtmlText(
                        html = aboutHtml,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.release_history),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = tokens.spacing.xl)
                    )
                    ReleaseHistory(
                        state = releaseState,
                        modifier = Modifier.padding(top = tokens.spacing.md)
                    )
                    Spacer(Modifier.height(40.dp))
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .padding(horizontal = tokens.spacing.xl)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.main_drawer_co_authored),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    DeveloperCredits(
                        credits = listOf(
                            DeveloperCredit(R.string.main_drawer_github_user) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Chami537")))
                            },
                            DeveloperCredit(R.string.main_drawer_github_project) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LiPu-jpg")))
                            },
                            DeveloperCredit(R.string.main_drawer_developer_mingyu) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/orgs/HIT-A/people/SpeechlessPanda")))
                            },
                            DeveloperCredit(R.string.main_drawer_developer_ruannuo) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/orgs/HIT-A/people/StrayRN")))
                            },
                            DeveloperCredit(R.string.main_drawer_developer_jixing) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/purple3899")))
                            },
                            DeveloperCredit(R.string.main_drawer_developer_radiant) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/radiant-abyss")))
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.about_reward_code_entry),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRewardCode = true },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        CheckUpdateButton(
            state = buttonState,
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                buttonState = UpdateButtonState.Loading
                viewModel.checkForUpdate(currentVersionCode(context))
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(tokens.spacing.xl)
        )

        if (showRewardCode) {
            AlertDialog(
                onDismissRequest = { showRewardCode = false },
                title = { Text(stringResource(R.string.about_reward_code_title)) },
                text = {
                    Image(
                        painter = painterResource(R.drawable.limpu_reward_code),
                        contentDescription = stringResource(R.string.about_reward_code_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = { showRewardCode = false }) {
                        Text(stringResource(R.string.about_reward_code_close))
                    }
                }
            )
        }
    }
}

@Composable
private fun AboutHeader(
    versionText: String,
    onBack: () -> Unit,
    onOpenAgreement: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        colorResource(R.color.primary),
                        colorResource(R.color.color_fade)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = tokens.spacing.xl)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.rotate(180f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = tokens.spacing.xl)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                shape = CircleShape,
                modifier = Modifier.size(100.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(tokens.spacing.sm)
                    )
                }
            }
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = tokens.spacing.xl)
            )
            Text(
                text = versionText,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = tokens.spacing.sm)
            )
            Surface(
                color = Color.White.copy(alpha = 0.33f),
                shape = RoundedCornerShape(tokens.radius.full),
                modifier = Modifier
                    .padding(top = tokens.spacing.md)
                    .clickable(onClick = onOpenAgreement)
            ) {
                Text(
                    text = stringResource(R.string.name_ua_and_pp),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(
                        horizontal = tokens.spacing.sm,
                        vertical = tokens.spacing.xs
                    )
                )
            }
        }
    }
}

@Composable
private fun HtmlText(
    html: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                textSize = 16f
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(color.toArgbCompat())
            textView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    )
}

@Composable
private fun ReleaseHistory(
    state: DataState<List<ReleaseHistoryItem>>?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .build()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            state == null || state.state == DataState.STATE.LOADING -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(HitaTheme.tokens.spacing.lg)
                )
            }
            state.state != DataState.STATE.SUCCESS -> {
                Text(
                    text = stringResource(R.string.release_history_failed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            state.data.orEmpty().isEmpty() -> {
                Text(
                    text = stringResource(R.string.release_history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            else -> {
                state.data.orEmpty().forEach { item ->
                    ReleaseHistoryCard(item = item, markwon = markwon)
                }
            }
        }
    }
}

@Composable
private fun ReleaseHistoryCard(
    item: ReleaseHistoryItem,
    markwon: Markwon,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    val contentColor = MaterialTheme.colorScheme.onSurface
    var expanded by remember(item.releaseName) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(tokens.radius.sm),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = tokens.spacing.sm)
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 12.dp
            )
        ) {
            Text(
                text = buildString {
                    append(item.releaseName)
                    if (item.prerelease) append(" · 预发布")
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (expanded) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    factory = { context ->
                        TextView(context).apply {
                            textSize = 14f
                            movementMethod = LinkMovementMethod.getInstance()
                        }
                    },
                    update = { textView ->
                        textView.setTextColor(contentColor.toArgbCompat())
                        markwon.setMarkdown(textView, item.markdown)
                    }
                )
            }
        }
    }
}

@Composable
private fun CheckUpdateButton(
    state: UpdateButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = state != UpdateButtonState.Loading,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier.widthIn(min = 160.dp).heightIn(min = 48.dp)
    ) {
        when (state) {
            UpdateButtonState.Loading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(22.dp)
                )
            }
            UpdateButtonState.Success -> {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_done_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            UpdateButtonState.Failed -> {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_error_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            UpdateButtonState.Idle -> {
                Text(
                    text = stringResource(R.string.check_for_update),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 16.sp,
                    maxLines = 1
                )
            }
        }
    }
}

private fun currentVersionCode(context: android.content.Context): Long {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return PackageInfoCompat.getLongVersionCode(packageInfo)
}

private fun Color.toArgbCompat(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}
