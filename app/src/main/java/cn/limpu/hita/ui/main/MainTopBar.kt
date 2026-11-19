package cn.limpu.hita.ui.main

import cn.limpu.hita.ui.main.timetable.WeekOption
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaSoraCloudTopBarModifier
import cn.limpu.hita.ui.design.soraCloudCardShape
import cn.limpu.hita.ui.design.SoraInk
import cn.limpu.hita.ui.design.SoraPaper
import cn.limpu.hita.data.model.timetable.DisplayScope
import com.limpu.style.ThemeTools

@Composable
internal fun MainTopBar(
    selectedTabId: MainTab?,
    todayTitle: String,
    todaySubtitle: String = "",
    timetableTitle: String,
    timetableName: String,
    showTimetableName: Boolean,
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
    onAddEvent: () -> Unit,
    onSystemBars: (Boolean, Boolean) -> Unit,
) {
    val isWallpaperTab = wallpaperAlpha > 0.5f
    val isSoraCloud = HitaTheme.preferenceStyle == ThemeTools.STYLE.SORA_CLOUD

    val titleColor by animateColorAsState(
        targetValue = if (isWallpaperTab) wallpaperTitleColor else MaterialTheme.colorScheme.onSurface,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f),
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
        when (selectedTabId) {
            MainTab.TIMELINE -> TodayToolbarTitle(todayTitle, todaySubtitle, titleColor, todaySecondaryColor, onOpenDrawer)
            MainTab.TIMETABLE -> TimetableToolbarTitle(
                title = timetableTitle,
                name = timetableName,
                showName = showTimetableName,
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
private fun TodayToolbarTitle(heading: String, secondary: String, headingColor: Color, secondaryColor: Color, onOpenDrawer: () -> Unit) {
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
        IconButton(onClick = onOpenDrawer) {
            Icon(painterResource(R.drawable.ic_baseline_account_circle_24),
                stringResource(R.string.today_account), tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp))
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TimetableToolbarTitle(
    title: String,
    name: String,
    showName: Boolean,
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
    onAddEvent: () -> Unit,
) {
    var nameMenuExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var weekMenuExpanded by remember { mutableStateOf(false) }
    // 只在需要时求值：周列表依赖课表学期区间，展开菜单才读一次。
    var cachedWeeks by remember { mutableStateOf<List<WeekOption>>(emptyList()) }
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
            modifier = Modifier
                .widthIn(max = 112.dp)
                .height(56.dp)
                .clickable {
                    // 标题与课表名称芯片共用同一个菜单：点标题即打开显示范围 / 周次选择，
                    // 有可用范围时展开，没有时退回上层的默认处理。
                    if (timetableOptions.isNotEmpty()) nameMenuExpanded = true else onTitleClick()
                },
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
        AnimatedVisibility(visible = showName && name.isNotBlank(), modifier = Modifier.weight(1f)) {
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
                    timetableOptions.forEach { option ->
                        val isCurrent = option.scope == currentScope
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        option.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (option.memberCount > 1) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.timetable_group_member_count, option.memberCount),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                nameMenuExpanded = false
                                onTimetableSelected(option.scope)
                            }
                        )
                    }
                    if (timetableOptions.isNotEmpty()) {
                        androidx.compose.material3.HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "按周跳转",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "按周查看",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                // 先收起范围菜单再开周菜单：两个 DropdownMenu 同时展开会互相遮挡。
                                nameMenuExpanded = false
                                cachedWeeks = weekOptionsProvider()
                                weekMenuExpanded = true
                            }
                        )
                    }
                }
                DropdownMenu(
                    expanded = weekMenuExpanded,
                    onDismissRequest = { weekMenuExpanded = false },
                ) {
                    cachedWeeks.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("第 ${option.week} 周") },
                            onClick = {
                                weekMenuExpanded = false
                                onPickWeek(option.mondayMillis)
                            },
                        )
                    }
                }
            }
        }
        if (!showName || name.isBlank()) Spacer(modifier = Modifier.weight(1f))
        androidx.compose.material3.IconButton(onClick = onTimetableSocial, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_baseline_share_24),
                contentDescription = stringResource(R.string.timetable_social_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.IconButton(
            onClick = onTimetableHistory,
            enabled = currentScope != null,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_history_24),
                contentDescription = stringResource(R.string.timetable_versions_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (currentScope != null) 1f else 0.38f),
            )
        }
        Box {
            val settingsView = LocalView.current
            Box(
                modifier = Modifier.size(48.dp).combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.timetable_settings_action),
                    onLongClickLabel = stringResource(R.string.timetable_wallpaper),
                    onClick = {
                        settingsView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        settingsExpanded = true
                    },
                    onLongClick = {
                        settingsView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onWallpaperLongPress()
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_theme),
                    contentDescription = stringResource(R.string.timetable_settings_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp))
            }
            DropdownMenu(expanded = settingsExpanded, onDismissRequest = { settingsExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.timetable_wallpaper)) },
                    onClick = { settingsExpanded = false; onWallpaper() })
                DropdownMenuItem(text = { Text(stringResource(R.string.wallpaper_remove)) },
                    onClick = { settingsExpanded = false; onWallpaperLongPress() })
                DropdownMenuItem(text = { Text(stringResource(R.string.comparison_display_settings)) },
                    onClick = { settingsExpanded = false; onTimetableSetting() })
            }
        }
        ToolbarIcon(R.drawable.ic_baseline_add_24, onClick = onAddEvent, iconSize = 28.dp, buttonSize = 48.dp)
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
    buttonSize: Dp = 56.dp,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(buttonSize)
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
