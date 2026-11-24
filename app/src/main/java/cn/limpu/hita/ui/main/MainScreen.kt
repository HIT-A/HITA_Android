package cn.limpu.hita.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaMotion
import cn.limpu.hita.ui.design.LocalHitaReducedMotion
import com.limpu.style.ThemeTools
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

@Composable
internal fun MainScreen(
    state: MainUiState,
    wallpaper: MainWallpaperState,
    actions: MainActions,
    themeIcon: Int,
    themeStyle: ThemeTools.STYLE,
    tabContent: @Composable (MainTab, List<MainTab>, Modifier) -> Unit,
) {
    val selectedTab = state.navigation.selectedIndex
    val visibleTabs = state.navigation.visibleTabs
    val showBlogDot = state.blogDotVisible
    val drawerOpen = state.drawerOpen
    val todayTitle = state.todayTitle
    val todaySubtitle = state.todaySubtitle
    val timetableTitle = state.timetableTitle
    val timetableName = state.timetableName
    val showTimetableName = state.showTimetableName
    val timetableOptions = state.scopeOptions
    val currentScope = state.currentScope
    val drawerState = state.drawer
    val wallpaperBitmap = wallpaper.bitmap
    val wallpaperVisible = wallpaper.visible
    val wallpaperScrimOpacity = wallpaper.scrimOpacity
    val wallpaperTitleColor = Color(wallpaper.titleColor)
    val wallpaperLabelColor = Color(wallpaper.labelColor)
    val onTimetableSelected = actions.selectTimetable
    val onTimetableTitleClick = actions.timetableTitleClick
    val weekOptionsProvider = actions.weekOptions
    val onPickWeek = actions.pickWeek
    val onSelectTab = actions.selectTab
    val onOpenDrawer = actions.openDrawer
    val onCloseDrawer = actions.closeDrawer
    val onTheme = actions.theme
    val onWallpaper = actions.wallpaper
    val onWallpaperLongPress = actions.wallpaperLongPress
    val onTimetableSetting = actions.timetableSetting
    val onTimetableSocial = actions.timetableSocial
    val onAddEvent = actions.addEvent
    val onDrawerHeader = actions.drawerHeader
    val onDrawerAvatarClick = actions.drawerAvatar
    val onThemeStyle = actions.themeStyle
    val onDrawerAgreement = actions.agreement
    val onUserFeedback = actions.userFeedback
    val onDrawerAbout = actions.about
    val onGitHubUser = actions.githubUser
    val onGitHubProject = actions.githubProject
    val onGitHubMingyu = actions.githubMingyu
    val onGitHubRuannuo = actions.githubRuannuo
    val onGitHubJixing = actions.githubJixing
    val onGitHubRadiant = actions.githubRadiant
    val density = LocalDensity.current
    val drawerWidth = 260.dp
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val isAppleGlass = themeStyle == ThemeTools.STYLE.APPLE_GLASS
    val reduced = LocalHitaReducedMotion.current
    BackHandler(enabled = drawerOpen, onBack = onCloseDrawer)
    val drawerProgress by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = HitaMotion.reveal(reduced, entering = drawerOpen),
        label = "drawer"
    )
    val drawerVisible by remember { derivedStateOf { drawerProgress > 0f } }
    val backgroundSemantics = if (drawerOpen) Modifier.clearAndSetSemantics {} else Modifier
    val closeDrawerLabel = stringResource(R.string.main_close_drawer)
    val drawerPaneLabel = stringResource(R.string.today_account)
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val systemNavAvoidance = pillBarBottomPadding(rememberMainNavigationBottom())
    val hazeState = remember { HazeState() }
    val liquidGlassBackdrop = rememberLayerBackdrop()
    val liquidGlassBackdropHandle = remember(liquidGlassBackdrop) {
        LiquidGlassBackdropHandle(liquidGlassBackdrop)
    }
    val useGlobalHaze = isAppleGlass && (drawerOpen || drawerVisible)
    val selectedTabId = visibleTabs.getOrNull(selectedTab)
    val timetableWallpaper = wallpaperBitmap.takeIf { selectedTabId == MainTab.TIMETABLE && wallpaperVisible }
    val showTimetableWallpaper = timetableWallpaper != null
    val wallpaperTargetAlpha = if (showTimetableWallpaper) 1f else 0f
    val wallpaperAlpha by animateFloatAsState(
        targetValue = wallpaperTargetAlpha,
        animationSpec = HitaMotion.feedback(reduced || !showTimetableWallpaper),
        label = "wallpaper_alpha"
    )
    var showRewardCode by remember { mutableStateOf(false) }

    MainThemeBackground(
        captureModifier = Modifier
            .then(if (useGlobalHaze) Modifier.haze(hazeState) else Modifier)
            .then(if (isAppleGlass) Modifier.layerBackdrop(liquidGlassBackdrop) else Modifier),
        page = {
            // 用户壁纸只属于课表，不应透到“今日 / 助手 / 资讯 / 功能”页的状态栏区域。
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
                    .then(backgroundSemantics)
            ) {
                MainTopBar(
                    selectedTabId = selectedTabId,
                    todayTitle = todayTitle,
                    todaySubtitle = todaySubtitle,
                    todayAvatar = drawerState.avatar,
                    timetableTitle = timetableTitle,
                    timetableName = timetableName,
                    showTimetableName = showTimetableName,
                    hasWallpaper = wallpaperVisible && wallpaperBitmap != null,
                    showTeachingVersions = state.showTeachingVersions,
                    showDeletionHistory = state.showDeletionHistory,
                    timetableOptions = timetableOptions,
                    currentScope = currentScope,
                    onTimetableSelected = onTimetableSelected,
                    onTimetableTitleClick = onTimetableTitleClick,
                    weekOptionsProvider = weekOptionsProvider,
                    onPickWeek = onPickWeek,
                    themeIcon = themeIcon,
                    wallpaperAlpha = wallpaperAlpha,
                    wallpaperTitleColor = wallpaperTitleColor,
                    onOpenDrawer = onOpenDrawer,
                    onTheme = onTheme,
                    onWallpaper = onWallpaper,
                    onWallpaperLongPress = onWallpaperLongPress,
                    onTimetableSetting = onTimetableSetting,
                    onTimetableSocial = onTimetableSocial,
                    onTimetableHistory = actions.timetableHistory,
                    onTimetableVersions = actions.timetableVersions,
                    onAddEvent = onAddEvent,
                    onSystemBars = actions.systemBars,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    tabContent(state.navigation.selectedTab, visibleTabs, Modifier.fillMaxSize())
                }
            }
        },
    ) {
        if (!imeVisible) {
            val tabBarAlpha = if (showTimetableWallpaper) 0.72f else 1f
            val tabBarSemantics = backgroundSemantics
            if (usesDockTabBar(themeStyle)) {
                DockTabBar(
                    selectedTab = selectedTab,
                    tabs = visibleTabs,
                    showBlogDot = showBlogDot,
                    alpha = tabBarAlpha,
                    themeStyle = themeStyle,
                    hazeState = null,
                    liquidGlassBackdrop = if (isAppleGlass) liquidGlassBackdropHandle else null,
                    onSelectTab = onSelectTab,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(dockBarChromePadding())
                        .then(tabBarSemantics)
                )
            } else {
                MainPillTabBar(
                    selectedTab = selectedTab,
                    visibleTabs = visibleTabs,
                    showBlogDot = showBlogDot,
                    alpha = tabBarAlpha,
                    themeStyle = themeStyle,
                    hazeState = null,
                    liquidGlassBackdrop = if (isAppleGlass) liquidGlassBackdropHandle else null,
                    onSelectTab = onSelectTab,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = systemNavAvoidance)
                        .then(tabBarSemantics)
                )
            }
        }

        if (drawerOpen || drawerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = drawerProgress * 0.28f }
                    .background(Color.Black)
                    .clickable(enabled = drawerOpen, role = Role.Button, onClick = onCloseDrawer)
                    .semantics { contentDescription = closeDrawerLabel }
            )
        }

        if (drawerOpen || drawerVisible) {
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
                onGitHubJixing = onGitHubJixing,
                onGitHubRadiant = onGitHubRadiant,
                onRewardCode = { showRewardCode = true },
                noticeEntryVisible = state.noticeEntryVisible,
                onNotices = actions.notices,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = (1f - drawerProgress) * drawerWidthPx }
                    .then(if (drawerOpen) Modifier.semantics { paneTitle = drawerPaneLabel } else Modifier.clearAndSetSemantics {}),
            )
        }

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
