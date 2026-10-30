package cn.limpu.hita.ui.main

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
    timetableTitle: String,
    timetableName: String,
    showTimetableName: Boolean,
    timetableOptions: List<DisplayScopeResolver.ScopeOption>,
    currentScope: DisplayScope?,
    onTimetableSelected: (Int) -> Unit,
    themeIcon: Int,
    wallpaperAlpha: Float,
    wallpaperTitleColor: Color,
    onOpenDrawer: () -> Unit,
    onTheme: () -> Unit,
    onWallpaper: () -> Unit,
    onWallpaperLongPress: () -> Unit,
    onTimetableSetting: () -> Unit,
    onTimetableSocial: () -> Unit,
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
            MainTab.TIMELINE -> ToolbarTitle(todayTitle, titleColor)
            MainTab.TIMETABLE -> TimetableToolbarTitle(
                title = timetableTitle,
                name = timetableName,
                showName = showTimetableName,
                timetableOptions = timetableOptions,
                currentScope = currentScope,
                onTimetableSelected = onTimetableSelected,
                titleColor = titleColor,
                onWallpaper = onWallpaper,
                onWallpaperLongPress = onWallpaperLongPress,
                onTimetableSetting = onTimetableSetting,
                onTimetableSocial = onTimetableSocial,
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
    onTimetableSelected: (Int) -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onWallpaper: () -> Unit,
    onWallpaperLongPress: () -> Unit,
    onTimetableSetting: () -> Unit,
    onTimetableSocial: () -> Unit,
    onAddEvent: () -> Unit,
) {
    var nameMenuExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
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
                    timetableOptions.forEachIndexed { i, option ->
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
                                onTimetableSelected(i)
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        androidx.compose.material3.IconButton(onClick = onTimetableSocial, modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_baseline_share_24),
                contentDescription = stringResource(R.string.timetable_social_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            val settingsView = LocalView.current
            Box(
                modifier = Modifier.size(56.dp).combinedClickable(
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
