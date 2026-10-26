package cn.limpu.hita.ui.main

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaSoraCloudBackground
import cn.limpu.hita.ui.design.hitaDeepSpaceBackground
import cn.limpu.hita.ui.design.hitaSumiBackground
import com.limpu.style.ThemeTools
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlin.math.roundToInt

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
    val selectedTabId = visibleTabs.getOrNull(selectedTab)
    val timetableWallpaper = wallpaperBitmap.takeIf { selectedTabId == MainTab.TIMETABLE && wallpaperVisible }
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
                    .graphicsLayer {
                        translationX = contentOffset
                        scaleX = contentScale
                        scaleY = contentScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    }
            ) {
                MainTopBar(
                    selectedTabId = selectedTabId,
                    todayTitle = todayTitle,
                    timetableTitle = timetableTitle,
                    timetableName = timetableName,
                    showTimetableName = showTimetableName,
                    timetableOptions = timetableOptions,
                    currentScope = currentScope,
                    onTimetableSelected = onTimetableSelected,
                    themeIcon = themeIcon,
                    wallpaperAlpha = wallpaperAlpha,
                    wallpaperTitleColor = wallpaperTitleColor,
                    onOpenDrawer = onOpenDrawer,
                    onTheme = onTheme,
                    onWallpaper = onWallpaper,
                    onWallpaperLongPress = onWallpaperLongPress,
                    onTimetableSetting = onTimetableSetting,
                    onTimetableSocial = onTimetableSocial,
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
        }


        if (!imeVisible) {
            val tabBarAlpha = if (showTimetableWallpaper) 0.72f else 1f
            val drawerFollow = Modifier.graphicsLayer {
                translationX = contentOffset
                scaleX = contentScale
                scaleY = contentScale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
            }
            if (usesDockTabBar(themeStyle)) {
                DockTabBar(
                    selectedTab = selectedTab,
                    tabs = visibleTabs,
                    showBlogDot = showBlogDot,
                    alpha = tabBarAlpha,
                    themeStyle = themeStyle,
                    hazeState = if (useGlobalHaze) hazeState else null,
                    liquidGlassBackdrop = if (isAppleGlass) liquidGlassBackdropHandle else null,
                    onSelectTab = onSelectTab,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(dockBarChromePadding())
                        .then(drawerFollow)
                )
            } else {
                MainPillTabBar(
                    selectedTab = selectedTab,
                    visibleTabs = visibleTabs,
                    showBlogDot = showBlogDot,
                    alpha = tabBarAlpha,
                    themeStyle = themeStyle,
                    hazeState = if (useGlobalHaze) hazeState else null,
                    liquidGlassBackdrop = if (isAppleGlass) liquidGlassBackdropHandle else null,
                    onSelectTab = onSelectTab,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = systemNavAvoidance)
                        .then(drawerFollow)
                )
            }
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
            onGitHubJixing = onGitHubJixing,
            onGitHubRadiant = onGitHubRadiant,
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
