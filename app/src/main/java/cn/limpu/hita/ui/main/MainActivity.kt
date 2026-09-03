package cn.limpu.hita.ui.main

import cn.limpu.hita.BuildConfig
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Observer
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.EasSettingsRepository
import cn.limpu.hita.data.repository.KEY_WALLPAPER_PATH
import cn.limpu.hita.data.repository.TimetableStyleRepository
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsDimensions
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.notice.AppNotice
import cn.limpu.hita.data.notice.AppNoticeCenter
import cn.limpu.hita.ui.about.ActivityAbout
import cn.limpu.hita.ui.about.UserAgreementDialog
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.cyberChamferShape
import cn.limpu.hita.ui.design.hitaCyberFlowEdge
import cn.limpu.hita.ui.design.hitaCyberGlow
import cn.limpu.hita.ui.design.hitaPersonaCardModifier
import cn.limpu.hita.ui.design.hitaPersonaTitleFont
import cn.limpu.hita.ui.design.hitaSoraCloudBackground
import cn.limpu.hita.ui.design.deepSpaceCardShape
import cn.limpu.hita.ui.design.hitaDeepSpaceBackground
import cn.limpu.hita.ui.design.hitaDeepSpaceCardModifier
import cn.limpu.hita.ui.design.hitaSumiBackground
import cn.limpu.hita.ui.design.hitaSumiCardModifier
import cn.limpu.hita.ui.design.hitaSumiTitleFont
import cn.limpu.hita.ui.design.sumiCardShape
import cn.limpu.hita.ui.design.hitaSoraCloudCardModifier
import cn.limpu.hita.ui.design.hitaSoraCloudTopBarModifier
import cn.limpu.hita.ui.design.hitaSoraTitleFont
import cn.limpu.hita.ui.design.personaSlashShape
import cn.limpu.hita.ui.design.soraCloudCardShape
import cn.limpu.hita.ui.design.SoraIndigo
import cn.limpu.hita.ui.design.SoraInk
import cn.limpu.hita.ui.design.SoraPaper
import cn.limpu.hita.ui.design.SoraVermilion
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.ui.event.add.PopupAddEvent
import cn.limpu.hita.ui.main.agent.AgentChatFragment
import cn.limpu.hita.ui.main.navigation.NavigationFragment
import cn.limpu.hita.ui.main.timeline.FragmentTimeLine
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.main.timetable.TimetableFragment
import cn.limpu.hita.ui.main.timetable.panel.FragmentTimetablePanel
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.WallpaperColorAnalyzer
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.style.ThemeTools
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : HiltBaseActivity<ComposeViewBinding>(),
    TimetableFragment.MainPageController, FragmentTimeLine.MainPageController {

    companion object {
        private const val STATE_SELECTED_TAB = "selected_tab"
    }

    @Inject lateinit var localUserRepository: LocalUserRepository
    @Inject lateinit var easRepository: EASRepository
    @Inject lateinit var timetableStyleRepository: TimetableStyleRepository

    protected val viewModel: MainViewModel by viewModels()

    private val autoReimportIntervalMs = 12 * 60 * 60 * 1000L
    private var autoReimportAttempted = false
    private val easLoginTransitionTracker = EasLoginTransitionTracker()
    private var checkedUpdate = false
    private var lastCheckTs: Long = 0

    private var selectedTab by mutableIntStateOf(0)
    private var drawerOpen by mutableStateOf(false)
    private var todayTitle by mutableStateOf("")
    private var timetableTitle by mutableStateOf("")
    private var timetableDisplayName by mutableStateOf("")
    private var showTimetableName by mutableStateOf(false)
    private var timetableOptionList by mutableStateOf<List<Timetable>>(emptyList())
    private var wallpaperBitmap by mutableStateOf<Bitmap?>(null)
    private var wallpaperVisible by mutableStateOf(false)
    private var wallpaperScrimOpacity by mutableIntStateOf(0)
    private var wallpaperTitleColor by mutableStateOf(AndroidColor.WHITE)
    private var wallpaperLabelColor by mutableStateOf(AndroidColor.WHITE)

    private val easTokenObserver = Observer<cn.limpu.hita.data.model.eas.EASToken> {
        refreshDrawerState()
        if (easLoginTransitionTracker.shouldTriggerLoginWork(it)) {
            autoReimportAttempted = false
            maybeAutoReimportTimetable()
        }
    }

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveAvatarLocally(it) }
    }

    private fun showAvatarPicker() {
        MaterialAlertDialogBuilder(this)
            .setTitle("更换头像")
            .setItems(arrayOf("从相册选择", "取消")) { _, which ->
                if (which == 0) pickAvatarLauncher.launch("image/*")
            }
            .show()
    }

    private fun saveAvatarLocally(uri: Uri) {
        localUserRepository.saveLocalAvatar(uri, applicationContext) { success ->
            if (success) {
                refreshDrawerState()
                Toast.makeText(this@MainActivity, "头像更换成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "图片处理失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var drawerState by mutableStateOf(DrawerUserState())

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, selectedTab) ?: selectedTab
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = AndroidColor.TRANSPARENT
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    override fun initViews() {
        todayTitle = getString(R.string.maintab_today)
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                val activeThemeStyle = HitaTheme.preferenceStyle
                val activeThemeIcon = when (HitaTheme.mode) {
                    ThemeTools.MODE.DARK -> R.drawable.ic_moon2
                    ThemeTools.MODE.LIGHT -> R.drawable.ic_sun
                    ThemeTools.MODE.FOLLOW -> R.drawable.ic_moon_auto
                }
                MainScreen(
                    selectedTab = selectedTab,
                    drawerOpen = drawerOpen,
                    todayTitle = todayTitle,
                    timetableTitle = timetableTitle,
                    timetableName = timetableDisplayName,
                    showTimetableName = showTimetableName,
                    timetableOptions = timetableOptionList,
                    onTimetableSelected = ::onSemesterSelected,
                    themeIcon = activeThemeIcon,
                    wallpaperBitmap = wallpaperBitmap,
                    wallpaperVisible = wallpaperVisible,
                    wallpaperScrimOpacity = wallpaperScrimOpacity,
                    wallpaperTitleColor = Color(wallpaperTitleColor),
                    wallpaperLabelColor = Color(wallpaperLabelColor),
                    themeStyle = activeThemeStyle,
                    drawerState = drawerState,
                    onSelectTab = { selectedTab = it },
                    onOpenDrawer = { refreshDrawerState(); drawerOpen = true },
                    onCloseDrawer = { drawerOpen = false },
                    onTheme = {
                        ThemeTools.switchTheme(getThis())
                        WidgetUtils.sendRefreshToAll(this)
                    },
                    onWallpaper = {
                        val hasWallpaper = !timetableStyleRepository.wallpaperPathLiveData.value.isNullOrEmpty()
                        if (hasWallpaper) {
                            showWallpaperOptionsMenu()
                        } else {
                            pickWallpaperLauncher.launch("image/*")
                        }
                    },
                    onWallpaperLongPress = { showWallpaperMenu() },
                    onTimetableSetting = { FragmentTimetablePanel().show(supportFragmentManager, "panel") },
                    onAddEvent = { PopupAddEvent().show(supportFragmentManager, "add_event") },
                    onDrawerHeader = { openDrawerHeader() },
                    onDrawerAvatarClick = { showAvatarPicker() },
                    onThemeStyle = { showThemeStyleMenu() },
                    onDrawerAgreement = { UserAgreementDialog().show(supportFragmentManager, "ua") },
                    onUserFeedback = {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://wj.qq.com/s2/27538298/9fr9/")
                            )
                        )
                    },
                    onDrawerAbout = { ActivityUtils.startActivity(getThis(), ActivityAbout::class.java) },
                    onGitHubUser = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chami537.github.io/"))) },
                    onGitHubProject = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LiPu-jpg"))) },
                    onGitHubMingyu = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/orgs/HIT-A/people/SpeechlessPanda"))) },
                    onGitHubRuannuo = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/orgs/HIT-A/people/StrayRN"))) },
                    fragmentFactory = { position ->
                        when (position) {
                            0 -> FragmentTimeLine()
                            1 -> TimetableFragment()
                            2 -> AgentChatFragment()
                            else -> NavigationFragment()
                        }
                    }
                )
            }
        }

        timetableStyleRepository.wallpaperPathLiveData.observe(this) { loadWallpaper(it) }
        timetableStyleRepository.wallpaperScrimLiveData.observe(this) { opacity ->
            wallpaperScrimOpacity = opacity
        }
        timetableStyleRepository.wallpaperDateColorLiveData.observe(this) { color ->
            wallpaperTitleColor = color
        }
        timetableStyleRepository.wallpaperLabelColorLiveData.observe(this) { color ->
            wallpaperLabelColor = color
        }
        lifecycleScope.launch {
            viewModel.checkUpdateResult.collect { state ->
                if (state.state == DataState.STATE.SUCCESS) {
                    state.data?.let { cr ->
                        if (cr.shouldUpdate) ActivityUtils.showUpdateNotification(cr, this@MainActivity)
                    }
                }
            }
        }
        viewModel.loggedInUserLiveData.observe(this) {
            refreshDrawerState()
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        checkNotices()
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        viewModel.startRefreshUser()
        refreshDrawerState()
        easRepository.observeEasToken().observe(this, easTokenObserver)
        maybeAutoReimportTimetable()
        try {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
            if (System.currentTimeMillis() - lastCheckTs > 5 * 60 * 1000) checkedUpdate = false
            if (!checkedUpdate) {
                if (localUserRepository.getLoggedInUser().isValid()) {
                    checkedUpdate = true
                    lastCheckTs = System.currentTimeMillis()
                }
                viewModel.checkForUpdate(code)
            }
        } catch (e: Exception) {
            LogUtils.e("Failed to get package info for update check", e)
        }
    }

    override fun onStop() {
        easRepository.observeEasToken().removeObserver(easTokenObserver)
        super.onStop()
    }

    private var criticalNoticeHandled = false

    private fun checkNotices() {
        AppNoticeCenter.fetch(this) { fetched ->
            val active = AppNoticeCenter.activeNotices(fetched)
            val critical = active.firstOrNull { it.isCritical }
            if (critical != null && !criticalNoticeHandled) {
                criticalNoticeHandled = true
                showCriticalNotice(critical)
            } else {
                val version = active.firstOrNull { it.isVersionKind }
                version?.let { maybeShowVersionNotice(it) }
            }
        }
    }

    private fun showCriticalNotice(notice: AppNotice) {
        UsageAnalyticsClient.record(
            UsageAnalyticsEvent.NOTICE_SHOWN,
            mapOf(
                UsageAnalyticsDimensions.PRESENTATION to "fullscreen",
                UsageAnalyticsDimensions.KIND to notice.kind,
            )
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(notice.title)
            .setMessage(notice.body)
            .setCancelable(false)
            .setPositiveButton("知道了") { _, _ ->
                UsageAnalyticsClient.record(
                    UsageAnalyticsEvent.NOTICE_ACTION_TAPPED,
                    mapOf(
                        UsageAnalyticsDimensions.PRESENTATION to "fullscreen",
                        UsageAnalyticsDimensions.KIND to notice.kind,
                    )
                )
            }
            .show()
    }

    private fun maybeShowVersionNotice(notice: AppNotice) {
        val minVersion = notice.minAppVersion ?: return
        if (minVersion <= BuildConfig.VERSION_CODE.toLong()) return
        UsageAnalyticsClient.record(
            UsageAnalyticsEvent.NOTICE_SHOWN,
            mapOf(
                UsageAnalyticsDimensions.PRESENTATION to "version_dialog",
                UsageAnalyticsDimensions.KIND to notice.kind,
            )
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(notice.title)
            .setMessage(notice.body)
            .setPositiveButton("查看更新") { _, _ ->
                UsageAnalyticsClient.record(
                    UsageAnalyticsEvent.NOTICE_ACTION_TAPPED,
                    mapOf(
                        UsageAnalyticsDimensions.PRESENTATION to "version_dialog",
                        UsageAnalyticsDimensions.KIND to notice.kind,
                    )
                )
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.UPDATE_URL)))
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun maybeAutoReimportTimetable() {        val settings = EasSettingsRepository(application)
        if (!settings.isAutoReimportEnabled()) return
        val token = easRepository.getEasToken()
        if (!token.isLogin()) return
        if (autoReimportAttempted) return
        val now = System.currentTimeMillis()
        val last = settings.getLastAutoReimportTs()
        if (now - last < autoReimportIntervalMs) return
        autoReimportAttempted = true
        val isUndergrad = token.stutype == cn.limpu.hita.data.model.eas.EASToken.TYPE.UNDERGRAD
        easRepository.startAutoImportCurrentTimetable(isUndergrad) { success ->
            if (success) settings.setLastAutoReimportTs(System.currentTimeMillis())
        }
    }

    private fun refreshDrawerState() {
        val localUser = localUserRepository.getLoggedInUser()
        if (localUser.isValid()) {
            drawerState = DrawerUserState(
                title = localUser.username.orEmpty(),
                subtitle = localUser.nickname.orEmpty(),
                avatar = localUser.avatar,
                loggedInLocalUser = true
            )
            return
        }

        val easToken = easRepository.getEasToken()
        if (easToken.isLogin()) {
            drawerState = DrawerUserState(
                title = easToken.name?.ifBlank { easToken.stuId?.ifBlank { easToken.username } }
                    ?: easToken.stuId?.ifBlank { easToken.username }
                    ?: easToken.username
                    ?: getString(R.string.eas_account_not_logged_in_title),
                subtitle = buildString {
                    val primary = easToken.stuId?.trim().orEmpty()
                    val secondary = listOf(
                        easToken.school,
                        easToken.major,
                        easToken.grade,
                        easToken.className
                    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                        .joinToString(" · ")
                    append(primary)
                    if (secondary.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(secondary)
                    }
                }.ifBlank { easToken.username.orEmpty() },
                avatar = localUser.avatar,
                loggedInLocalUser = false
            )
        } else {
            drawerState = DrawerUserState(
                title = getString(R.string.eas_account_not_logged_in_title),
                subtitle = getString(R.string.eas_account_not_logged_in_subtitle),
                avatar = null,
                loggedInLocalUser = false
            )
        }
    }

    private fun openDrawerHeader() {
        val localUser = localUserRepository.getLoggedInUser()
        val easToken = easRepository.getEasToken()
        if (localUser.isValid() || easToken.isLogin()) {
            val title = if (localUser.isValid()) localUser.nickname.orEmpty()
                else easToken.name ?: easToken.username.orEmpty()
            val items = if (localUser.isValid())
                arrayOf("查看资料", "退出登录")
            else
                arrayOf("退出登录")
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items) { _: DialogInterface, which: Int ->
                    when {
                        localUser.isValid() && which == 0 -> {
                            val userId = localUser.id ?: return@setItems
                            ActivityUtils.startProfileActivity(getThis(), userId, null)
                        }
                        else -> {
                            if (localUser.isValid())
                                localUserRepository.logout(this@MainActivity)
                            easRepository.logout()
                            viewModel.startRefreshUser()
                        }
                    }
                }
                .show()
        } else {
            ActivityUtils.showEasVerifyWindow<Activity>(
                this,
                easRepository,
                directTo = null,
                onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                    override fun onSuccess(window: PopUpLoginEAS) {
                        refreshDrawerState()
                        window.dismiss()
                    }
                    override fun onFailed(window: PopUpLoginEAS) {}
                }
            )
        }
    }

    private fun showThemeStyleMenu() {
        val styles = arrayOf(
            ThemeTools.STYLE.CLASSIC,
            ThemeTools.STYLE.APPLE_GLASS,
            ThemeTools.STYLE.CYBER,
            ThemeTools.STYLE.SORA_CLOUD,
            ThemeTools.STYLE.P5,
            ThemeTools.STYLE.DEEP_SPACE,
            ThemeTools.STYLE.SUMI
        )
        val labels = styles.map { it.displayName() }.toTypedArray()
        val checked = styles.indexOf(ThemeTools.getThemeStyle(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_drawer_menu_theme_style)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = styles[which]
                ThemeTools.setThemeStyle(this, selected)
                WidgetUtils.sendRefreshToAll(this)
                Toast.makeText(
                    this,
                    getString(R.string.theme_style_changed, selected.displayName()),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun showWallpaperMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.timetable_wallpaper)
            .setItems(arrayOf(getString(R.string.wallpaper_remove))) { _, _ ->
                removeWallpaper()
            }
            .show()
    }

    /** 已设置壁纸时点击壁纸入口：提供更换 / 移除选项 */
    private fun showWallpaperOptionsMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.timetable_wallpaper)
            .setItems(
                arrayOf(
                    getString(R.string.wallpaper_change),
                    getString(R.string.wallpaper_remove)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickWallpaperLauncher.launch("image/*")
                    1 -> removeWallpaper()
                }
            }
            .show()
    }

    private fun removeWallpaper() {
        Thread {
            filesDir.listFiles()?.filter {
                it.name.startsWith("timetable_wallpaper")
            }?.forEach { it.delete() }
        }.start()
        timetableStyleRepository.putData(KEY_WALLPAPER_PATH, "")
    }

    private fun loadWallpaper(path: String?) {
        if (path.isNullOrEmpty()) {
            wallpaperBitmap = null
            wallpaperVisible = false
            timetableStyleRepository.wallpaperDateColorLiveData.value = AndroidColor.WHITE
            timetableStyleRepository.wallpaperLabelColorLiveData.value = AndroidColor.WHITE
            return
        }
        val file = File(path.removePrefix("local://"))
        if (!file.exists()) {
            timetableStyleRepository.putData(KEY_WALLPAPER_PATH, "")
            wallpaperBitmap = null
            wallpaperVisible = false
            return
        }
        wallpaperVisible = true
        Glide.with(this)
            .asBitmap()
            .load(file)
            .centerCrop()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    wallpaperBitmap = resource
                    val dateColor = WallpaperColorAnalyzer.sampleRegion(resource, 0f, 0f, 1f, 0.12f)
                    val labelColor = WallpaperColorAnalyzer.sampleRegion(resource, 0f, 0.12f, 0.08f, 0.88f)
                    timetableStyleRepository.wallpaperDateColorLiveData.value = dateColor
                    timetableStyleRepository.wallpaperLabelColorLiveData.value = labelColor
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    wallpaperBitmap = null
                }
            })
    }

    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveWallpaperLocally(it) }
    }

    private fun saveWallpaperLocally(uri: Uri) {
        Thread {
            try {
                val oldPrefix = "timetable_wallpaper"
                filesDir.listFiles()?.filter {
                    it.name.startsWith(oldPrefix)
                }?.forEach { it.delete() }

                val destFile = File(filesDir, "timetable_wallpaper_${System.currentTimeMillis()}")
                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Cannot open wallpaper input stream")
                timetableStyleRepository.putData(KEY_WALLPAPER_PATH, "local://${destFile.absolutePath}")
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.wallpaper_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (drawerOpen) {
                drawerOpen = false
                return
            }
            val intent = Intent(Intent.ACTION_MAIN)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addCategory(Intent.CATEGORY_HOME)
            startActivity(intent)
        }
    }

    override fun setTitleText(string: String) {
        timetableTitle = string
        showTimetableName = true
    }

    override fun setTimetableName(String: String) {
        timetableDisplayName = String
        showTimetableName = true
    }

    override fun setSingleTitle(string: String) {
        timetableTitle = string
        showTimetableName = false
    }

    override fun setTimetableOptions(options: List<Timetable>) {
        timetableOptionList = options
    }

    private fun onSemesterSelected(index: Int) {
        val tt = timetableOptionList.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        val weekMills = 7L * 24 * 3600 * 1000
        val targetMonday = if (now in tt.startTime.time..tt.endTime.time) {
            val weekOffset = ((now - tt.startTime.time) / weekMills).toInt()
            tt.startTime.time + weekOffset * weekMills
        } else {
            tt.startTime.time
        }
        val fragment = supportFragmentManager.findFragmentByTag("main_tab_1") as? TimetableFragment
        fragment?.navigateToWeek(targetMonday)
    }

    override fun setTimelineTitleText(string: String) {
        todayTitle = string
    }
}

private fun ThemeTools.STYLE.displayName(): String {
    return when (this) {
        ThemeTools.STYLE.CLASSIC -> "经典搭配"
        ThemeTools.STYLE.APPLE_GLASS -> "玻璃艺术"
        ThemeTools.STYLE.CYBER -> "赛博朋克"
        ThemeTools.STYLE.SORA_CLOUD -> "日映构成"
        ThemeTools.STYLE.P5 -> "波普涂鸦"
        ThemeTools.STYLE.DEEP_SPACE -> "深空星野"
        ThemeTools.STYLE.SUMI -> "水墨宣纸"
    }
}

private data class DrawerUserState(
    val title: String = "",
    val subtitle: String = "",
    val avatar: String? = null,
    val loggedInLocalUser: Boolean = false,
)

private data class MainTabSpec(
    val titleRes: Int,
    val iconRes: Int,
)

@Composable
private fun MainScreen(
    selectedTab: Int,
    drawerOpen: Boolean,
    todayTitle: String,
    timetableTitle: String,
    timetableName: String,
    showTimetableName: Boolean,
    timetableOptions: List<Timetable>,
    onTimetableSelected: (Int) -> Unit,
    themeIcon: Int,
    wallpaperBitmap: Bitmap?,
    wallpaperVisible: Boolean,
    wallpaperScrimOpacity: Int,
    wallpaperTitleColor: Color,
    wallpaperLabelColor: Color,
    themeStyle: ThemeTools.STYLE,
    drawerState: DrawerUserState,
    onSelectTab: (Int) -> Unit,
    onOpenDrawer: () -> Unit,
    onCloseDrawer: () -> Unit,
    onTheme: () -> Unit,
    onWallpaper: () -> Unit,
    onWallpaperLongPress: () -> Unit,
    onTimetableSetting: () -> Unit,
    onAddEvent: () -> Unit,
    onDrawerHeader: () -> Unit,
    onDrawerAvatarClick: () -> Unit,
    onThemeStyle: () -> Unit,
    onDrawerAgreement: () -> Unit,
    onUserFeedback: () -> Unit,
    onDrawerAbout: () -> Unit,
    onGitHubUser: () -> Unit,
    onGitHubProject: () -> Unit,
    onGitHubMingyu: () -> Unit,
    onGitHubRuannuo: () -> Unit,
    fragmentFactory: (Int) -> Fragment,
) {
    val density = LocalDensity.current
    val drawerWidth = 260.dp
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val isAppleGlass = themeStyle == ThemeTools.STYLE.APPLE_GLASS
    val isSoraCloud = themeStyle == ThemeTools.STYLE.SORA_CLOUD
    val isDeepSpace = themeStyle == ThemeTools.STYLE.DEEP_SPACE
    val isSumi = themeStyle == ThemeTools.STYLE.SUMI
    val drawerProgress by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = if (isAppleGlass) {
            tween(
                durationMillis = if (drawerOpen) 240 else 200,
                easing = if (drawerOpen) LinearOutSlowInEasing else FastOutLinearInEasing
            )
        } else {
            spring(dampingRatio = 0.9f, stiffness = 300f)
        },
        label = "drawer"
    )
    val appleGlassContentOffsetPx = with(density) { 10.dp.toPx() }
    val contentScale = if (isAppleGlass) 1f - drawerProgress * 0.008f else 1f - drawerProgress * 0.2f
    val contentOffset = if (isAppleGlass) {
        -appleGlassContentOffsetPx * drawerProgress
    } else {
        -drawerWidthPx * drawerProgress
    }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val threeButtonThresholdPx = with(density) { 32.dp.toPx() }
    val systemNavAvoidance = if (navBottomPx >= threeButtonThresholdPx) {
        with(density) { navBottomPx.toDp() } + 8.dp
    } else {
        12.dp
    }
    val hazeState = remember { HazeState() }
    val liquidGlassBackdrop = rememberLayerBackdrop()
    val liquidGlassBackdropHandle = remember(liquidGlassBackdrop) {
        LiquidGlassBackdropHandle(liquidGlassBackdrop)
    }
    val useGlobalHaze = isAppleGlass
    val timetableWallpaper = wallpaperBitmap.takeIf { selectedTab == 1 && wallpaperVisible }
    val showTimetableWallpaper = timetableWallpaper != null
    val wallpaperTargetAlpha = if (showTimetableWallpaper) 1f else 0f
    val wallpaperAlpha by animateFloatAsState(
        targetValue = wallpaperTargetAlpha,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f),
        label = "wallpaper_alpha"
    )
    var showRewardCode by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (useGlobalHaze) Modifier.haze(hazeState) else Modifier)
                .then(if (isAppleGlass) Modifier.layerBackdrop(liquidGlassBackdrop) else Modifier)
        ) {
            if (isAppleGlass) {
                AppleGlassBackground()
            } else if (isSoraCloud) {
                SoraCloudBackground()
            } else if (isDeepSpace) {
                DeepSpaceBackground()
            } else if (isSumi) {
                SumiBackground()
            }

            // 用户壁纸只属于课表，不应透到“今日 / 助手 / 功能”页的状态栏区域。
            if (timetableWallpaper != null) {
                Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = wallpaperAlpha }) {
                    Image(
                        bitmap = timetableWallpaper.asImageBitmap(),
                        contentDescription = stringResource(R.string.timetable_wallpaper_description),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = wallpaperScrimOpacity / 100f))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                                )
                            )
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 6.dp)
                    .graphicsLayer {
                        translationX = contentOffset
                        scaleX = contentScale
                        scaleY = contentScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    }
            ) {
                MainTopBar(
                    selectedTab = selectedTab,
                    todayTitle = todayTitle,
                    timetableTitle = timetableTitle,
                    timetableName = timetableName,
                    showTimetableName = showTimetableName,
                    timetableOptions = timetableOptions,
                    onTimetableSelected = onTimetableSelected,
                    themeIcon = themeIcon,
                    wallpaperAlpha = wallpaperAlpha,
                    wallpaperTitleColor = wallpaperTitleColor,
                    onOpenDrawer = onOpenDrawer,
                    onTheme = onTheme,
                    onWallpaper = onWallpaper,
                    onWallpaperLongPress = onWallpaperLongPress,
                    onTimetableSetting = onTimetableSetting,
                    onAddEvent = onAddEvent,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    MainFragmentPager(
                        selectedTab = selectedTab,
                        fragmentFactory = fragmentFactory,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (!imeVisible) {
            MainPillTabBar(
                selectedTab = selectedTab,
                alpha = if (showTimetableWallpaper) 0.72f else 1f,
                themeStyle = themeStyle,
                hazeState = if (useGlobalHaze) hazeState else null,
                liquidGlassBackdrop = if (isAppleGlass) liquidGlassBackdropHandle else null,
                onSelectTab = onSelectTab,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = systemNavAvoidance)
                    .graphicsLayer {
                        translationX = contentOffset
                        scaleX = contentScale
                        scaleY = contentScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    }
            )
        }

        if (drawerProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(
                            alpha = (if (isAppleGlass) 0.22f else 0.32f) * drawerProgress
                        )
                    )
                    .pointerInput(drawerOpen) {
                        detectTapGestures { onCloseDrawer() }
                    }
            )
        }

        MainDrawer(
            drawerState = drawerState,
            themeStyle = themeStyle,
            hazeState = if (useGlobalHaze) hazeState else null,
            onHeaderClick = onDrawerHeader,
            onAvatarClick = onDrawerAvatarClick,
            onThemeStyle = onThemeStyle,
            onAgreement = onDrawerAgreement,
            onUserFeedback = onUserFeedback,
            onAbout = onDrawerAbout,
            onGitHubUser = onGitHubUser,
            onGitHubProject = onGitHubProject,
            onGitHubMingyu = onGitHubMingyu,
            onGitHubRuannuo = onGitHubRuannuo,
            onRewardCode = { showRewardCode = true },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(drawerWidth)
                .fillMaxHeight()
                .offset { IntOffset(((1f - drawerProgress) * drawerWidthPx).roundToInt(), 0) }
                .graphicsLayer {
                    alpha = if (isAppleGlass) 0.88f + 0.12f * drawerProgress else 1f
                }
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
private fun AppleGlassBackground() {
    val colors = if (HitaTheme.isDark) {
        listOf(
            Color(0xFF080B10),
            Color(0xFF111823),
            Color(0xFF0C1017),
        )
    } else {
        listOf(
            Color(0xFFF5F9FE),
            Color(0xFFEAF3FC),
            Color(0xFFEFF7FF),
        )
    }
    val atmosphere = if (HitaTheme.isDark) {
        listOf(
            Color.White.copy(alpha = 0.42f),
            Color.Transparent,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        )
    } else {
        listOf(
            Color.White.copy(alpha = 0.20f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.045f),
            Color(0xFF9CCBFA).copy(alpha = 0.16f)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = colors
                )
            )
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = atmosphere
                )
            )
    )
}

@Composable
private fun SoraCloudBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hitaSoraCloudBackground()
    )
}

@Composable
private fun DeepSpaceBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hitaDeepSpaceBackground()
    )
}

@Composable
private fun SumiBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hitaSumiBackground()
    )
}

@Composable
private fun MainTopBar(
    selectedTab: Int,
    todayTitle: String,
    timetableTitle: String,
    timetableName: String,
    showTimetableName: Boolean,
    timetableOptions: List<Timetable>,
    onTimetableSelected: (Int) -> Unit,
    themeIcon: Int,
    wallpaperAlpha: Float,
    wallpaperTitleColor: Color,
    onOpenDrawer: () -> Unit,
    onTheme: () -> Unit,
    onWallpaper: () -> Unit,
    onWallpaperLongPress: () -> Unit,
    onTimetableSetting: () -> Unit,
    onAddEvent: () -> Unit,
) {
    val isWallpaperTab = wallpaperAlpha > 0.5f
    val isSoraCloud = HitaTheme.preferenceStyle == ThemeTools.STYLE.SORA_CLOUD

    val titleColor by animateColorAsState(
        targetValue = if (isWallpaperTab) wallpaperTitleColor else MaterialTheme.colorScheme.onSurface,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f),
        label = "titleColor"
    )

    // Wallpaper tab: force light status bar icons on dark overlay; restore otherwise
    val view = LocalView.current
    val isDarkTheme = HitaTheme.isDark
    LaunchedEffect(isWallpaperTab, isDarkTheme) {
        val window = (view.context as android.app.Activity).window
        @Suppress("DEPRECATION")
        val ctrl = androidx.core.view.WindowInsetsControllerCompat(window, view)
        if (isWallpaperTab) {
            ctrl.isAppearanceLightStatusBars = false // light icons on dark overlay
        } else {
            ctrl.isAppearanceLightStatusBars = !isDarkTheme
        }
        ctrl.isAppearanceLightNavigationBars = !isDarkTheme
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .then(
                if (isSoraCloud && !isWallpaperTab) {
                    Modifier.hitaSoraCloudTopBarModifier()
                } else {
                    Modifier
                }
            )
            .padding(bottom = 4.dp)
            .padding(start = HitaTheme.tokens.spacing.sm),
        verticalAlignment = Alignment.Bottom
    ) {
        when (selectedTab) {
            0 -> ToolbarTitle(todayTitle, titleColor)
            1 -> TimetableToolbarTitle(
                title = timetableTitle,
                name = timetableName,
                showName = showTimetableName,
                timetableOptions = timetableOptions,
                onTimetableSelected = onTimetableSelected,
                titleColor = titleColor,
                onWallpaper = onWallpaper,
                onWallpaperLongPress = onWallpaperLongPress,
                onTimetableSetting = onTimetableSetting,
                onAddEvent = onAddEvent
            )
            2 -> ToolbarTitle(stringResource(R.string.title_agent), titleColor)
            else -> NavigationToolbar(
                themeIcon = themeIcon,
                onTheme = onTheme,
                onOpenDrawer = onOpenDrawer
            )
        }
    }
}

@Composable
private fun ToolbarTitle(title: String, textColor: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        modifier = Modifier
            .height(56.dp)
            .padding(start = HitaTheme.tokens.spacing.sm),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = title,
            color = textColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = HitaTheme.fonts.display,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TimetableToolbarTitle(
    title: String,
    name: String,
    showName: Boolean,
    timetableOptions: List<Timetable>,
    onTimetableSelected: (Int) -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onWallpaper: () -> Unit,
    onWallpaperLongPress: () -> Unit,
    onTimetableSetting: () -> Unit,
    onAddEvent: () -> Unit,
) {
    var nameMenuExpanded by remember { mutableStateOf(false) }
    val isAppleGlass = HitaTheme.preferenceStyle == ThemeTools.STYLE.APPLE_GLASS
    val isSoraCloud = HitaTheme.preferenceStyle == ThemeTools.STYLE.SORA_CLOUD
    val nameChipShape = if (isSoraCloud) soraCloudCardShape(10.dp) else RoundedCornerShape(12.dp)
    val nameChipColor = when {
        isAppleGlass -> if (HitaTheme.isDark) {
            Color(0xFF182330).copy(alpha = 0.72f)
        } else {
            Color(0xFFE5F1FC).copy(alpha = 0.88f)
        }
        isSoraCloud -> if (HitaTheme.isDark) {
            Color(0xFF343028)
        } else {
            SoraPaper
        }
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.height(56.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = HitaTheme.fonts.display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(visible = showName && name.isNotBlank()) {
            Box {
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = HitaTheme.tokens.spacing.md)
                        .clip(nameChipShape)
                        .background(nameChipColor)
                        .then(
                            if (isAppleGlass) {
                                Modifier.border(
                                    width = 0.5.dp,
                                    color = Color.White.copy(
                                        alpha = if (HitaTheme.isDark) 0.20f else 0.72f
                                    ),
                                    shape = nameChipShape
                                )
                            } else if (isSoraCloud) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = if (HitaTheme.isDark) Color(0xFFF4ECD9) else SoraInk,
                                    shape = nameChipShape
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable { nameMenuExpanded = true }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
                DropdownMenu(
                    expanded = nameMenuExpanded && timetableOptions.isNotEmpty(),
                    onDismissRequest = { nameMenuExpanded = false }
                ) {
                    timetableOptions.forEachIndexed { i, tt ->
                        DropdownMenuItem(
                            text = { Text(tt.name.orEmpty()) },
                            onClick = {
                                nameMenuExpanded = false
                                onTimetableSelected(i)
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        ToolbarIcon(R.drawable.ic_wallpaper, onClick = onWallpaper, onLongClick = onWallpaperLongPress)
        ToolbarIcon(R.drawable.ic_theme, onClick = onTimetableSetting)
        ToolbarIcon(R.drawable.ic_baseline_add_24, onClick = onAddEvent, iconSize = 28.dp)
    }
}

@Composable
private fun NavigationToolbar(
    themeIcon: Int,
    onTheme: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ToolbarTitle(stringResource(R.string.title_navigation))
        Spacer(modifier = Modifier.weight(1f))
        ToolbarIcon(themeIcon, onClick = onTheme)
        ToolbarIcon(R.drawable.ic_baseline_menu_24, onClick = onOpenDrawer)
    }
}

@Composable
private fun ToolbarIcon(
    iconRes: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    iconSize: Dp = 24.dp,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(56.dp)
            .pointerInput(iconRes) {
                detectTapGestures(
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onLongClick?.invoke()
                    },
                    onTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun MainFragmentPager(
    selectedTab: Int,
    fragmentFactory: (Int) -> Fragment,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val containerIds = remember { IntArray(4) { View.generateViewId() } }
    val createdTabs = remember { mutableStateListOf(0) }
    val committedTags = remember { mutableSetOf<String>() }

    LaunchedEffect(selectedTab) {
        if (!createdTabs.contains(selectedTab)) {
            createdTabs.add(selectedTab)
        }
    }

    Box(modifier = modifier) {
        createdTabs.forEach { index ->
            val targetAlpha = if (index == selectedTab) 1f else 0f
            val alpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = if (targetAlpha > 0f) spring(dampingRatio = 0.9f, stiffness = 300f) else snap(),
                label = "tab_alpha_$index"
            )

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha },
                factory = { ctx ->
                    FragmentContainerView(ctx).apply {
                        id = containerIds[index]
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { container ->
                    val selected = index == selectedTab
                    val tag = "main_tab_$index"
                    container.alpha = alpha
                    container.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    container.isEnabled = selected
                    if (selected) {
                        container.bringToFront()
                        val f = activity.supportFragmentManager.findFragmentByTag(tag)
                        (f as? FragmentTimeLine)?.onTabActivated()
                    }
                    if (tag !in committedTags) {
                        val existing = activity.supportFragmentManager.findFragmentByTag(tag)
                        if (existing != null) {
                            activity.supportFragmentManager.beginTransaction()
                                .remove(existing)
                                .commitNowAllowingStateLoss()
                        }
                        activity.supportFragmentManager.beginTransaction()
                            .replace(containerIds[index], fragmentFactory(index), tag)
                            .commitNowAllowingStateLoss()
                        committedTags.add(tag)
                    }
                }
            )
        }
    }
}

private val CapsuleBlue = Color(0xFF3390EC)
private val ActiveBlue = Color(0xFF0088CC)
private val CapsuleTabWidth = 68.dp
private val CapsuleTabIconSize = 18.dp

private class LiquidGlassBackdropHandle(val value: Any)

private fun Modifier.liquidGlassSurface(
    backdropHandle: LiquidGlassBackdropHandle,
    shape: Shape,
    blurRadius: Float,
    refractionHeight: Float,
    refractionAmount: Float,
): Modifier {
    val backdrop = backdropHandle.value as com.kyant.backdrop.backdrops.LayerBackdrop
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(blurRadius)
            vibrancy()
            colorControls(brightness = 0.05f, contrast = 1.08f, saturation = 1.16f)
            lens(
                refractionHeight = refractionHeight,
                refractionAmount = refractionAmount,
                depthEffect = true,
                chromaticAberration = true
            )
        },
        highlight = {
            Highlight(
                width = 1.2.dp,
                blurRadius = 7.dp,
                alpha = 0.92f,
                style = HighlightStyle.Default(intensity = 0.7f, angle = 315f, falloff = 1.35f)
            )
        },
        shadow = {
            Shadow(
                radius = 30.dp,
                offset = DpOffset(0.dp, 9.dp),
                color = Color.Black.copy(alpha = 0.13f)
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 18.dp,
                offset = DpOffset(0.dp, 8.dp),
                color = Color.White.copy(alpha = 0.20f),
                alpha = 0.82f
            )
        }
    )
}

@Composable
private fun MainPillTabBar(
    selectedTab: Int,
    alpha: Float,
    themeStyle: ThemeTools.STYLE,
    hazeState: HazeState?,
    liquidGlassBackdrop: LiquidGlassBackdropHandle?,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember {
        listOf(
            MainTabSpec(R.string.title_timeline, R.drawable.ic_nav_today),
            MainTabSpec(R.string.title_timetable, R.drawable.ic_nav_timetable),
            MainTabSpec(R.string.title_agent, R.drawable.ic_baseline_toys_24),
            MainTabSpec(R.string.title_navigation, R.drawable.ic_nav_navigation),
        )
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val tabWidthPx = with(density) { CapsuleTabWidth.toPx() }

    val indicatorOffsetPx by animateFloatAsState(
        targetValue = selectedTab * tabWidthPx,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f),
        label = "pill_offset"
    )
    val isAppleGlass = themeStyle == ThemeTools.STYLE.APPLE_GLASS
    val isCyber = themeStyle == ThemeTools.STYLE.CYBER
    val isSoraCloud = themeStyle == ThemeTools.STYLE.SORA_CLOUD
    val isPersona = themeStyle == ThemeTools.STYLE.P5
    val isDeepSpace = themeStyle == ThemeTools.STYLE.DEEP_SPACE
    val isSumi = themeStyle == ThemeTools.STYLE.SUMI
    val barShape = when {
        isCyber -> cyberChamferShape(20.dp)
        isPersona -> personaSlashShape(18.dp)
        isAppleGlass -> RoundedCornerShape(30.dp)
        isSoraCloud -> soraCloudCardShape(18.dp)
        isDeepSpace -> deepSpaceCardShape(28.dp)
        isSumi -> sumiCardShape(8.dp)
        else -> RoundedCornerShape(28.dp)
    }
    val indicatorShape = when {
        isCyber -> cyberChamferShape(12.dp)
        isPersona -> personaSlashShape(10.dp)
        isSoraCloud -> soraCloudCardShape(10.dp)
        isDeepSpace -> deepSpaceCardShape(20.dp)
        isSumi -> sumiCardShape(6.dp)
        else -> RoundedCornerShape(22.dp)
    }
    val indicatorColor = when {
        isPersona -> MaterialTheme.colorScheme.primary
        isCyber -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        isAppleGlass -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        isSoraCloud -> SoraVermilion
        isDeepSpace -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        isSumi -> MaterialTheme.colorScheme.secondary
        else -> CapsuleBlue.copy(alpha = 0.12f)
    }
    val activeColor = when {
        isPersona -> MaterialTheme.colorScheme.onPrimary
        isSoraCloud -> SoraPaper
        isAppleGlass || isCyber || isDeepSpace -> MaterialTheme.colorScheme.primary
        isSumi -> Color(0xFFF7F2E7)
        else -> ActiveBlue
    }
    val barHazeStyle = HazeStyle(
        tint = Color.White.copy(alpha = 0.16f),
        blurRadius = 24.dp,
        noiseFactor = 0.05f
    )

    Surface(
        modifier = modifier
            .alpha(alpha)
            .then(
                if (isCyber) {
                    Modifier.hitaCyberGlow(barShape, elevation = 16.dp, alpha = 0.32f)
                } else {
                    Modifier
                }
            )
            .then(
                if (isAppleGlass && liquidGlassBackdrop != null) {
                    Modifier.liquidGlassSurface(
                        backdropHandle = liquidGlassBackdrop,
                        shape = barShape,
                        blurRadius = 22f,
                        refractionHeight = 26f,
                        refractionAmount = 40f
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (isAppleGlass && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, shape = barShape, style = barHazeStyle)
                } else {
                    Modifier
                }
            )
            .then(
                when {
                    isCyber -> Modifier.hitaCyberFlowEdge(barShape)
                    isPersona -> Modifier.hitaPersonaCardModifier(barShape)
                    isDeepSpace -> Modifier.hitaDeepSpaceCardModifier(barShape)
                    isSumi -> Modifier.hitaSumiCardModifier(barShape)
                    isAppleGlass -> Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.52f),
                        shape = barShape
                    )
                    isSoraCloud -> Modifier.hitaSoraCloudCardModifier(
                        shape = barShape,
                        elevation = 8.dp,
                        highlightTint = SoraIndigo,
                        reflectionTint = SoraVermilion,
                    )
                    else -> Modifier
                }
            ),
        shape = barShape,
        color = when {
            isAppleGlass -> MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)
            isSoraCloud -> if (HitaTheme.isDark) Color(0xFF25221D) else SoraPaper
            else -> MaterialTheme.colorScheme.surface
        },
        shadowElevation = when {
            isPersona -> 0.dp
            isAppleGlass -> 14.dp
            isSoraCloud -> 0.dp
            isDeepSpace -> 4.dp
            isSumi -> 2.dp
            else -> 6.dp
        }
    ) {
        Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Surface(
                modifier = Modifier
                    .graphicsLayer { translationX = indicatorOffsetPx }
                    .width(CapsuleTabWidth)
                    .height(44.dp),
                shape = indicatorShape,
                color = indicatorColor
            ) {}

            Row(verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    val active = index == selectedTab

                    val tint by animateColorAsState(
                        targetValue = if (active) activeColor else MaterialTheme.colorScheme.onSurface,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f),
                        label = "tab_tint_$index"
                    )

                    Column(
                        modifier = Modifier
                            .width(CapsuleTabWidth)
                            .clip(indicatorShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                onSelectTab(index)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(CapsuleTabIconSize)
                        )
                        Text(
                            text = stringResource(tab.titleRes),
                            color = tint,
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainDrawer(
    drawerState: DrawerUserState,
    themeStyle: ThemeTools.STYLE,
    hazeState: HazeState?,
    onHeaderClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onThemeStyle: () -> Unit,
    onAgreement: () -> Unit,
    onUserFeedback: () -> Unit,
    onAbout: () -> Unit,
    onGitHubUser: () -> Unit,
    onGitHubProject: () -> Unit,
    onGitHubMingyu: () -> Unit,
    onGitHubRuannuo: () -> Unit,
    onRewardCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAppleGlass = themeStyle == ThemeTools.STYLE.APPLE_GLASS
    val isCyber = themeStyle == ThemeTools.STYLE.CYBER
    val isSoraCloud = themeStyle == ThemeTools.STYLE.SORA_CLOUD
    val isPersona = themeStyle == ThemeTools.STYLE.P5
    val isDeepSpace = themeStyle == ThemeTools.STYLE.DEEP_SPACE
    val isSumi = themeStyle == ThemeTools.STYLE.SUMI
    val drawerShape = when {
        isCyber -> CutCornerShape(topStart = 28.dp, bottomStart = 28.dp)
        isPersona -> CutCornerShape(topStart = 30.dp, bottomStart = 14.dp)
        isAppleGlass -> RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp)
        isSoraCloud -> CutCornerShape(topStart = 42.dp, bottomStart = 14.dp, bottomEnd = 22.dp)
        isDeepSpace -> RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp)
        isSumi -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
        else -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
    }
    val drawerHazeStyle = HazeStyle(
        tint = if (HitaTheme.isDark) {
            Color(0xFF111820).copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.60f)
        },
        blurRadius = 20.dp,
        noiseFactor = 0.03f
    )
    Surface(
        modifier = modifier
            .then(
                if (isCyber) {
                    Modifier.hitaCyberGlow(drawerShape, elevation = 18.dp, alpha = 0.30f)
                } else {
                    Modifier
                }
            )
            .then(
                if (isAppleGlass && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, shape = drawerShape, style = drawerHazeStyle)
                } else {
                    Modifier
                }
            )
            .then(
                when {
                    isCyber -> Modifier.hitaCyberFlowEdge(drawerShape)
                    isPersona -> Modifier.hitaPersonaCardModifier(drawerShape)
                    isDeepSpace -> Modifier.hitaDeepSpaceCardModifier(drawerShape)
                    isSumi -> Modifier.hitaSumiCardModifier(drawerShape)
                    isAppleGlass -> Modifier.border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = if (HitaTheme.isDark) 0.20f else 0.38f),
                        shape = drawerShape
                    )
                    isSoraCloud -> Modifier.hitaSoraCloudCardModifier(
                        shape = drawerShape,
                        elevation = 10.dp,
                        highlightTint = SoraIndigo,
                        reflectionTint = SoraVermilion,
                    )
                    else -> Modifier
                }
            ),
        shape = drawerShape,
        shadowElevation = when {
            isPersona -> 0.dp
            isAppleGlass -> 12.dp
            isSoraCloud -> 0.dp
            isDeepSpace -> 4.dp
            isSumi -> 2.dp
            else -> 16.dp
        },
        color = when {
            isAppleGlass -> if (HitaTheme.isDark) {
                Color(0xFF121923).copy(alpha = 0.84f)
            } else {
                Color(0xFFF7FAFF).copy(alpha = 0.84f)
            }
            isSoraCloud -> if (HitaTheme.isDark) Color(0xFF25221D) else SoraPaper
            else -> MaterialTheme.colorScheme.surface
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isSoraCloud) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(7.dp)
                        .fillMaxHeight()
                        .background(SoraVermilion)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 18.dp, end = 14.dp)
                        .size(width = 34.dp, height = 10.dp)
                        .background(if (HitaTheme.isDark) Color(0xFFC69A43) else MaterialTheme.colorScheme.tertiary)
                )
            }
            if (isSumi) {
                // 朱砂印章：白文「哈」，东方落款
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 18.dp, end = 16.dp)
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "哈",
                        color = Color(0xFFF7F2E7),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxHeight(),
            ) {
                DrawerHeader(drawerState = drawerState, onClick = onHeaderClick, onAvatarClick = onAvatarClick)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(horizontal = HitaTheme.tokens.spacing.xl)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAppleGlass) 0.45f else 0.8f))
                )
                DrawerItem(
                    R.drawable.ic_baseline_color_lens_24,
                    "${stringResource(R.string.main_drawer_menu_theme_style)} · ${themeStyle.displayName()}",
                    onThemeStyle
                )
                DrawerItem(R.drawable.ic_info, stringResource(R.string.name_ua_and_pp), onAgreement)
                DrawerItem(
                    R.drawable.ic_baseline_edit_24,
                    stringResource(R.string.main_drawer_menu_report),
                    onUserFeedback
                )
                DrawerItem(R.drawable.logo, stringResource(R.string.main_drawer_menu_about), onAbout)
                Spacer(Modifier.weight(1f))
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(horizontal = HitaTheme.tokens.spacing.xl)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.main_drawer_github_user),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onGitHubUser)
                    )
                    Text(
                        text = " | ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        fontSize = 13.sp
                    )
                    Text(
                        text = stringResource(R.string.main_drawer_github_project),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onGitHubProject)
                    )
                    Text(
                        text = " | ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        fontSize = 13.sp
                    )
                    Text(
                        text = stringResource(R.string.main_drawer_developer_mingyu),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onGitHubMingyu)
                    )
                    Text(
                        text = " | ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        fontSize = 13.sp
                    )
                    Text(
                        text = stringResource(R.string.main_drawer_developer_ruannuo),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onGitHubRuannuo)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.about_reward_code_entry),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRewardCode),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DrawerHeader(drawerState: DrawerUserState, onClick: () -> Unit, onAvatarClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 78.dp, bottom = HitaTheme.tokens.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { image ->
                    val avatar = drawerState.avatar
                    if (!avatar.isNullOrBlank()) {
                        com.limpu.hitauser.util.ImageUtils.loadAvatarInto(image.context, avatar, image)
                    } else {
                        image.setImageResource(R.drawable.place_holder_avatar)
                    }
                }
            )
        }
        Text(
            text = drawerState.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = HitaTheme.fonts.display,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = HitaTheme.tokens.spacing.lg,
                top = HitaTheme.tokens.spacing.lg,
                end = HitaTheme.tokens.spacing.lg
            )
        )
        Text(
            text = drawerState.subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.6f)
                .padding(
                    start = HitaTheme.tokens.spacing.lg,
                    top = HitaTheme.tokens.spacing.sm,
                    end = HitaTheme.tokens.spacing.lg
                ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DrawerItem(icon: Int, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
