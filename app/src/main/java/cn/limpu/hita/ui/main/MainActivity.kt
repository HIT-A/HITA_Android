package cn.limpu.hita.ui.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.BlogRepository
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableStyleRepository
import cn.limpu.hita.ui.about.ActivityAbout
import cn.limpu.hita.ui.about.UserAgreementDialog
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.ui.event.add.PopupAddEvent
import cn.limpu.hita.ui.main.agent.AgentChatFragment
import cn.limpu.hita.ui.main.blog.BlogFragment
import cn.limpu.hita.ui.main.navigation.NavigationFragment
import cn.limpu.hita.ui.main.timeline.FragmentTimeLine
import cn.limpu.hita.ui.main.timetable.TimetableFragment
import cn.limpu.hita.ui.main.timetable.panel.FragmentTimetablePanel
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.LogUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : HiltBaseActivity<MainViewBinding>(),
    TimetableFragment.MainPageController, FragmentTimeLine.MainPageController {

    @Inject lateinit var localUserRepository: LocalUserRepository
    @Inject lateinit var easRepository: EASRepository
    @Inject lateinit var timetableStyleRepository: TimetableStyleRepository
    @Inject lateinit var blogRepository: BlogRepository
    @Inject lateinit var scopeResolver: DisplayScopeResolver
    private val viewModel: MainViewModel by viewModels()
    private var savedState: Bundle? = null
    private var hostId = 0L
    private lateinit var tabHost: MainTabHost
    private lateinit var wallpaperLoader: WallpaperLoader
    private lateinit var noticePresenter: MainNoticePresenter
    private lateinit var updatePresenter: MainUpdatePresenter
    private var wallpaper by mutableStateOf(MainWallpaperState())
    private val tokenObserver = Observer<EASToken> { viewModel.accountChanged(it) }

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.saveAvatar(uri)
    }
    private val pickWallpaper = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.saveWallpaper(uri)
    }

    override fun initViewBinding() = MainViewBinding(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        savedState = savedInstanceState
        super.onCreate(savedInstanceState)
        savedState = null
        @Suppress("DEPRECATION")
        window.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = android.graphics.Color.TRANSPARENT
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun initViews() {
        viewModel.initialize(savedState?.getString(STATE_SELECTED_TAB_NAME), savedState?.getInt(STATE_SELECTED_TAB) ?: 0)
        hostId = viewModel.attachHost()
        tabHost = MainTabHost(supportFragmentManager, savedState, factory = { tab ->
            when (tab) {
                MainTab.TIMELINE -> FragmentTimeLine()
                MainTab.TIMETABLE -> TimetableFragment()
                MainTab.AGENT -> AgentChatFragment()
                MainTab.BLOG -> BlogFragment()
                MainTab.HOMEWORK -> cn.limpu.hita.ui.main.homework.HomeworkFragment()
                MainTab.NAVIGATION -> NavigationFragment()
            }
        })
        binding.parkTabContainers(tabHost.createView(this))
        noticePresenter = MainNoticePresenter(this, viewModel::noticeFinished)
        updatePresenter = MainUpdatePresenter(this, viewModel::updateFinished)
        wallpaperLoader = WallpaperLoader(this) { bitmap, visible, title, label ->
            wallpaper = wallpaper.copy(bitmap = bitmap, visible = visible, titleColor = title, labelColor = label)
            timetableStyleRepository.wallpaperDateColorLiveData.value = title
            timetableStyleRepository.wallpaperLabelColorLiveData.value = label
        }
        val actions = MainActions(
            selectTab = viewModel::selectTab,
            openDrawer = { viewModel.setDrawer(true) }, closeDrawer = { viewModel.setDrawer(false) },
            selectTimetable = { index ->
                viewModel.selectScope(index)?.let { monday ->
                    (tabHost.find(MainTab.TIMETABLE) as? TimetableFragment)?.navigateToWeek(monday)
                }
            },
            theme = { ThemeTools.switchTheme(this); WidgetUtils.sendRefreshToAll(this) },
            wallpaper = ::showWallpaperOptions, wallpaperLongPress = ::showWallpaperRemove,
            timetableSetting = { FragmentTimetablePanel().show(supportFragmentManager, "panel") },
            timetableSocial = { (tabHost.find(MainTab.TIMETABLE) as? TimetableFragment)?.openTimetableSocialSheet() },
            addEvent = { PopupAddEvent().show(supportFragmentManager, "add_event") },
            drawerHeader = ::openDrawerHeader, drawerAvatar = ::showAvatarPicker,
            themeStyle = ::showThemeStyleMenu,
            agreement = { UserAgreementDialog().show(supportFragmentManager, "ua") },
            userFeedback = { openUrl("https://wj.qq.com/s2/27538298/9fr9/") },
            about = { ActivityUtils.startActivity(this, ActivityAbout::class.java) },
            githubUser = { openUrl("https://chami537.github.io/") },
            githubProject = { openUrl("https://github.com/LiPu-jpg") },
            githubMingyu = { openUrl("https://github.com/orgs/HIT-A/people/SpeechlessPanda") },
            githubRuannuo = { openUrl("https://github.com/orgs/HIT-A/people/StrayRN") },
            githubJixing = { openUrl("https://github.com/purple3899") },
            githubRadiant = { openUrl("https://github.com/radiant-abyss") },
            systemBars = { hasWallpaper, dark ->
                WindowInsetsControllerCompat(window, binding.root).apply {
                    isAppearanceLightStatusBars = !hasWallpaper && !dark
                    isAppearanceLightNavigationBars = !dark
                }
            },
        )
        binding.composeView.setContent {
            HitaComposeTheme {
                val state by viewModel.state.collectAsState()
                MainScreen(
                    state = state, wallpaper = wallpaper, actions = actions,
                    themeIcon = when (HitaTheme.mode) {
                        ThemeTools.MODE.DARK -> R.drawable.ic_moon2
                        ThemeTools.MODE.LIGHT -> R.drawable.ic_sun
                        ThemeTools.MODE.FOLLOW -> R.drawable.ic_moon_auto
                    },
                    themeStyle = HitaTheme.preferenceStyle,
                    tabContent = { selected, visible, modifier -> MainFragmentPager(tabHost, selected, visible, modifier) },
                )
            }
        }
        scopeResolver.options.observe(this, viewModel::setScopes)
        scopeResolver.resolved.observe(this) { viewModel.setScope(it?.scope) }
        blogRepository.tabUnseenLiveData.observe(this) { viewModel.setBlogDot(it == true) }
        timetableStyleRepository.wallpaperPathLiveData.observe(this) {
            viewModel.validateWallpaperPath(it)
            wallpaperLoader.load(it)
        }
        timetableStyleRepository.wallpaperScrimLiveData.observe(this) { wallpaper = wallpaper.copy(scrimOpacity = it) }
        timetableStyleRepository.wallpaperDateColorLiveData.observe(this) { wallpaper = wallpaper.copy(titleColor = it) }
        timetableStyleRepository.wallpaperLabelColorLiveData.observe(this) { wallpaper = wallpaper.copy(labelColor = it) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.effects.collect { effect ->
                    if (viewModel.accepts(effect, hostId) && !isFinishing && !isDestroyed) {
                        when (val value = effect.value) {
                            MainEffect.SessionExpired -> { showSessionExpired(); viewModel.sessionExpiredPresented() }
                            is MainEffect.Notice -> noticePresenter.show(value.selection)
                            is MainEffect.Update -> updatePresenter.show(value.result)
                            is MainEffect.Message -> Toast.makeText(this@MainActivity, value.stringId, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.drawerOpen) viewModel.setDrawer(false)
                else startActivity(Intent(Intent.ACTION_MAIN).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    addCategory(Intent.CATEGORY_HOME)
                })
            }
        })
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        easRepository.observeEasToken().observe(this, tokenObserver)
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            viewModel.start(hostId, code)
        } catch (error: Exception) {
            LogUtils.e("Failed to get package info for update check", error)
        }
    }

    override fun onPostResume() { super.onPostResume(); tabHost.resume() }
    override fun onStop() { easRepository.observeEasToken().removeObserver(tokenObserver); super.onStop() }
    override fun onDestroy() {
        viewModel.detachHost(hostId)
        wallpaperLoader.close()
        noticePresenter.close()
        updatePresenter.close()
        tabHost.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val navigation = viewModel.state.value.navigation
        outState.putInt(STATE_SELECTED_TAB, navigation.selectedIndex)
        outState.putString(STATE_SELECTED_TAB_NAME, navigation.selectedTab.name)
        tabHost.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun setTitleText(string: String) = viewModel.setTimetableTitle(string, true)
    override fun setTimetableName(String: String) = viewModel.setTimetableName(String)
    override fun setSingleTitle(string: String) = viewModel.setTimetableTitle(string, false)
    override fun setTimelineTitleText(string: String) = viewModel.setTimelineTitle(string)

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun showAvatarPicker() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.main_avatar_change)
            .setItems(arrayOf(getString(R.string.main_avatar_pick), getString(R.string.main_action_cancel))) { _, index ->
                if (index == 0) pickAvatar.launch("image/*")
            }.show()
    }

    private fun openDrawerHeader() {
        val user = localUserRepository.getLoggedInUser()
        val token = easRepository.getEasToken()
        if (!user.isValid() && !token.isLogin()) { showEasLoginSheet(); return }
        val profile = user.isValid()
        val title = if (profile) user.nickname.orEmpty() else token.name ?: token.username.orEmpty()
        val items = if (profile) arrayOf(getString(R.string.main_view_profile), getString(R.string.eas_relogin_entry), getString(R.string.main_logout))
            else arrayOf(getString(R.string.eas_relogin_entry), getString(R.string.main_logout))
        MaterialAlertDialogBuilder(this).setTitle(title).setItems(items) { _, index ->
            when {
                profile && index == 0 -> user.id?.let { ActivityUtils.startProfileActivity(this, it, null) }
                index == (if (profile) 1 else 0) -> showEasLoginSheet()
                else -> viewModel.logout()
            }
        }.show()
    }

    private fun showEasLoginSheet() {
        val succeeded = viewModel::reloginSucceeded
        ActivityUtils.showEasVerifyWindow<Activity>(this, easRepository, directTo = null,
            onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                override fun onSuccess(window: PopUpLoginEAS) { succeeded(); window.dismiss() }
                override fun onFailed(window: PopUpLoginEAS) { window.dismiss() }
            })
    }

    private fun showSessionExpired() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.eas_session_expired_title)
            .setMessage(R.string.eas_session_expired_message)
            .setPositiveButton(R.string.eas_relogin_now) { _, _ -> showEasLoginSheet() }
            .setNegativeButton(R.string.eas_relogin_later, null).show()
    }

    private fun showWallpaperOptions() {
        if (timetableStyleRepository.wallpaperPath().isEmpty()) { pickWallpaper.launch("image/*"); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.timetable_wallpaper)
            .setItems(arrayOf(getString(R.string.wallpaper_change), getString(R.string.wallpaper_remove))) { _, index ->
                if (index == 0) pickWallpaper.launch("image/*") else viewModel.removeWallpaper()
            }.show()
    }

    private fun showWallpaperRemove() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.timetable_wallpaper)
            .setItems(arrayOf(getString(R.string.wallpaper_remove))) { _, _ -> viewModel.removeWallpaper() }.show()
    }

    private fun showThemeStyleMenu() {
        val styles = listOf(ThemeTools.STYLE.CLASSIC, ThemeTools.STYLE.APPLE_GLASS, ThemeTools.STYLE.CYBER,
            ThemeTools.STYLE.SORA_CLOUD, ThemeTools.STYLE.P5, ThemeTools.STYLE.DEEP_SPACE, ThemeTools.STYLE.SUMI)
        val labels = styles.map { getString(it.mainDisplayName()) }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.main_drawer_menu_theme_style)
            .setSingleChoiceItems(labels, styles.indexOf(ThemeTools.getThemeStyle(this)).coerceAtLeast(0)) { dialog, index ->
                ThemeTools.setThemeStyle(this, styles[index])
                WidgetUtils.sendRefreshToAll(this)
                Toast.makeText(this, getString(R.string.theme_style_changed, labels[index]), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }.show()
    }

    companion object {
        private const val STATE_SELECTED_TAB = "selected_tab"
        private const val STATE_SELECTED_TAB_NAME = "selected_tab_name"
    }
}

internal fun ThemeTools.STYLE.mainDisplayName(): Int = when (this) {
    ThemeTools.STYLE.CLASSIC -> R.string.main_style_classic
    ThemeTools.STYLE.APPLE_GLASS -> R.string.main_style_glass
    ThemeTools.STYLE.CYBER -> R.string.main_style_cyber
    ThemeTools.STYLE.SORA_CLOUD -> R.string.main_style_sora
    ThemeTools.STYLE.P5 -> R.string.main_style_persona
    ThemeTools.STYLE.DEEP_SPACE -> R.string.main_style_deep_space
    ThemeTools.STYLE.SUMI -> R.string.main_style_sumi
}

/** Keep the reflective JVM entry point and CornerBasedShape contract used by existing callers. */
internal fun Modifier.liquidGlassSurface(
    backdropHandle: LiquidGlassBackdropHandle,
    shape: CornerBasedShape,
    blurRadius: Float,
    refractionHeight: Float,
    refractionAmount: Float,
): Modifier = mainLiquidGlassSurface(backdropHandle, shape, blurRadius, refractionHeight, refractionAmount)
