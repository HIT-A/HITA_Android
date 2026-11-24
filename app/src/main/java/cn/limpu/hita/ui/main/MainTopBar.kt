package cn.limpu.hita.ui.main

import cn.limpu.hita.ui.main.timetable.WeekOption
import android.widget.ImageView
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.ui.design.HitaMotion
import cn.limpu.hita.ui.design.LocalHitaReducedMotion
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaSoraCloudTopBarModifier
import cn.limpu.hita.data.model.timetable.DisplayScope
import com.limpu.style.ThemeTools

@Composable
internal fun MainTopBar(
    selectedTabId: MainTab?,
    todayTitle: String,
    todaySubtitle: String = "",
    todayAvatar: String? = null,
    timetableTitle: String,
    timetableName: String,
    showTimetableName: Boolean,
    hasWallpaper: Boolean,
    showTeachingVersions: Boolean,
    showDeletionHistory: Boolean,
    timetableOptions: List<DisplayScopeResolver.ScopeOption>,
    currentScope: DisplayScope?,
    onTimetableSelected: (DisplayScope) -> Unit,
    onTimetableTitleClick: () -> Unit = {},
    weekOptionsProvider: () -> List<WeekOption> = { emptyList() },
    onPickWeek: (Long) -> Unit = {},
    themeIcon: Int,
    wallpaperAlpha: Float,
    wallpaperTitleColor: Color,
    onOpenDrawer: () -> Unit,
    onTheme: () -> Unit,
    onWallpaper: () -> Unit,
    onWallpaperLongPress: () -> Unit,
    onTimetableSetting: () -> Unit,
    onTimetableSocial: () -> Unit,
    onTimetableHistory: () -> Unit = {},
    onTimetableVersions: () -> Unit = {},
    onAddEvent: () -> Unit,
    onSystemBars: (Boolean, Boolean) -> Unit,
) {
    val isWallpaperTab = hasWallpaper && selectedTabId == MainTab.TIMETABLE && wallpaperAlpha > 0f
    val isSoraCloud = HitaTheme.preferenceStyle == ThemeTools.STYLE.SORA_CLOUD

    val titleColor by animateColorAsState(
        targetValue = if (isWallpaperTab) wallpaperTitleColor else MaterialTheme.colorScheme.onSurface,
        animationSpec = HitaMotion.feedback(LocalHitaReducedMotion.current || !isWallpaperTab),
        label = "titleColor"
    )

    val isDarkTheme = HitaTheme.isDark
    // 教学周与星期是首页最要紧的时间锚点，做黑体主标题；日期退到次行。
    val todaySecondaryColor = if (isWallpaperTab) wallpaperTitleColor.copy(alpha = 0.82f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(isWallpaperTab, isDarkTheme) {
        onSystemBars(isWallpaperTab, isDarkTheme)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
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
        when (selectedTabId) {
            MainTab.TIMELINE -> TodayToolbarTitle(todayTitle, todaySubtitle, todayAvatar, titleColor, todaySecondaryColor, onOpenDrawer)
            MainTab.TIMETABLE -> TimetableToolbarTitle(
                title = timetableTitle,
                name = timetableName,
                showName = showTimetableName,
                hasWallpaper = hasWallpaper,
                showTeachingVersions = showTeachingVersions,
                showDeletionHistory = showDeletionHistory,
                timetableOptions = timetableOptions,
                currentScope = currentScope,
                onTimetableSelected = onTimetableSelected,
                onTitleClick = onTimetableTitleClick,
                weekOptionsProvider = weekOptionsProvider,
                onPickWeek = onPickWeek,
                titleColor = titleColor,
                onWallpaper = onWallpaper,
                onWallpaperLongPress = onWallpaperLongPress,
                onTimetableSetting = onTimetableSetting,
                onTimetableSocial = onTimetableSocial,
                onTimetableHistory = onTimetableHistory,
                onTimetableVersions = onTimetableVersions,
                onAddEvent = onAddEvent
            )
            MainTab.AGENT -> ToolbarTitle(stringResource(R.string.title_agent), titleColor)
            MainTab.HOMEWORK -> ToolbarTitle(stringResource(R.string.title_homework), titleColor)
            MainTab.BLOG -> ToolbarTitle(stringResource(R.string.title_blog), titleColor)
            else -> NavigationToolbar(
                themeIcon = themeIcon,
                onTheme = onTheme,
                onOpenDrawer = onOpenDrawer
            )
        }
    }
}

@Composable
private fun TodayToolbarTitle(heading: String, secondary: String, avatar: String?, headingColor: Color,
    secondaryColor: Color, onOpenDrawer: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(heading, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                fontFamily = HitaTheme.fonts.display, color = headingColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (secondary.isNotBlank()) {
                Text(secondary, fontSize = 12.sp, lineHeight = 16.sp, color = secondaryColor, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        val accountLabel = stringResource(R.string.today_account)
        IconButton(onClick = onOpenDrawer, modifier = Modifier.semantics { contentDescription = accountLabel }) {
            TodayAvatar(avatar)
        }
    }
}

/** 顶栏头像：与抽屉头部同一套 Glide 加载，没设置头像时用同一个占位图。 */
@Composable
private fun TodayAvatar(avatar: String?) {
    Box(Modifier.size(30.dp).clip(CircleShape)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { image ->
                // 同一张头像不重复下单，否则每次重组都会重新解码并闪一下。
                val source = avatar.orEmpty()
                if (image.tag != source) {
                    image.tag = source
                    if (source.isNotBlank()) {
                        com.limpu.hitauser.util.ImageUtils.loadAvatarInto(image.context, source, image)
                    } else {
                        image.setImageResource(R.drawable.place_holder_avatar)
                    }
                }
            },
        )
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
    hasWallpaper: Boolean,
    showTeachingVersions: Boolean,
    showDeletionHistory: Boolean,
    timetableOptions: List<DisplayScopeResolver.ScopeOption>,
    currentScope: DisplayScope?,
    onTimetableSelected: (DisplayScope) -> Unit,
    weekOptionsProvider: () -> List<WeekOption> = { emptyList() },
    onPickWeek: (Long) -> Unit = {},
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onTitleClick: () -> Unit,
    onWallpaper: () -> Unit,
    onWallpaperLongPress: () -> Unit,
    onTimetableSetting: () -> Unit,
    onTimetableSocial: () -> Unit,
    onTimetableHistory: () -> Unit,
    onTimetableVersions: () -> Unit,
    onAddEvent: () -> Unit,
) {
    var menu by remember(currentScope) { mutableStateOf<TimetableToolbarMenu?>(null) }
    var cachedWeeks by remember(currentScope) { mutableStateOf<List<WeekOption>>(emptyList()) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 380.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    if (currentScope == null) onTitleClick()
                    else {
                        cachedWeeks = weekOptionsProvider()
                        menu = TimetableToolbarMenu.Scope
                    }
                }.padding(vertical = 4.dp)) {
                    Text(
                        title,
                        color = titleColor,
                        fontSize = if (compact) 20.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = HitaTheme.fonts.display,
                        maxLines = 2,
                    )
                    if (showName && name.isNotBlank()) {
                        Text(name, color = titleColor.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                DropdownMenu(expanded = menu == TimetableToolbarMenu.Scope, onDismissRequest = { menu = null }) {
                    if (timetableOptions.size > 1) timetableOptions.forEach { option ->
                        val selected = option.scope == currentScope
                        DropdownMenuItem(
                            text = {
                                Text(option.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            },
                            modifier = Modifier.semantics { this.selected = selected },
                            onClick = { menu = null; onTimetableSelected(option.scope) }
                        )
                    }
                    if (cachedWeeks.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.main_timetable_week_picker)) },
                            onClick = { menu = TimetableToolbarMenu.Week }
                        )
                    }
                }
                DropdownMenu(expanded = menu == TimetableToolbarMenu.Week, onDismissRequest = { menu = null }) {
                    cachedWeeks.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.main_timetable_week_option, option.week)) },
                            onClick = { menu = null; onPickWeek(option.mondayMillis) },
                        )
                    }
                }
            }
            if (!compact) {
                ToolbarIcon(R.drawable.ic_baseline_share_24, R.string.timetable_social_title, onTimetableSocial, buttonSize = 48.dp)
            }
            Box {
                ToolbarIcon(R.drawable.ic_theme, R.string.timetable_settings_action, { menu = TimetableToolbarMenu.Settings }, buttonSize = 48.dp)
                DropdownMenu(expanded = menu == TimetableToolbarMenu.Settings, onDismissRequest = { menu = null }) {
                    if (compact) DropdownMenuItem(text = { Text(stringResource(R.string.timetable_social_title)) },
                        onClick = { menu = null; onTimetableSocial() })
                    if (showTeachingVersions) DropdownMenuItem(text = { Text(stringResource(R.string.timetable_versions_title)) },
                        onClick = { menu = null; onTimetableVersions() })
                    if (showDeletionHistory) DropdownMenuItem(text = { Text(stringResource(R.string.timetable_deletion_history)) },
                        onClick = { menu = null; onTimetableHistory() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.timetable_wallpaper)) },
                        onClick = { menu = null; onWallpaper() })
                    if (hasWallpaper) DropdownMenuItem(text = { Text(stringResource(R.string.wallpaper_remove)) },
                        onClick = { menu = null; onWallpaperLongPress() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.comparison_display_settings)) },
                        onClick = { menu = null; onTimetableSetting() })
                }
            }
            ToolbarIcon(R.drawable.ic_baseline_add_24, R.string.ade_title, onAddEvent, iconSize = 28.dp, buttonSize = 48.dp)
        }
    }
}

private enum class TimetableToolbarMenu { Scope, Week, Settings }

@Composable
private fun NavigationToolbar(
    themeIcon: Int,
    onTheme: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ToolbarTitle(stringResource(R.string.title_navigation))
        Spacer(modifier = Modifier.weight(1f))
        ToolbarIcon(themeIcon, R.string.main_change_appearance, onTheme)
        ToolbarIcon(R.drawable.ic_baseline_menu_24, R.string.today_account, onOpenDrawer)
    }
}

@Composable
private fun ToolbarIcon(
    iconRes: Int,
    labelRes: Int,
    onClick: () -> Unit,
    iconSize: Dp = 24.dp,
    buttonSize: Dp = 56.dp,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(buttonSize)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(labelRes),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }
}
