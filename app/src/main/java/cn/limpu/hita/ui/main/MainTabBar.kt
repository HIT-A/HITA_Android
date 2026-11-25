package cn.limpu.hita.ui.main

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaMotion
import cn.limpu.hita.ui.design.LocalHitaReducedMotion
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.cyberChamferShape
import cn.limpu.hita.ui.design.hitaCyberNeonEdge
import cn.limpu.hita.ui.design.hitaCyberGlow
import cn.limpu.hita.ui.design.hitaPersonaCardModifier
import cn.limpu.hita.ui.design.deepSpaceCardShape
import cn.limpu.hita.ui.design.hitaDeepSpaceCardModifier
import cn.limpu.hita.ui.design.hitaSumiCardModifier
import cn.limpu.hita.ui.design.sumiCardShape
import cn.limpu.hita.ui.design.hitaSoraCloudCardModifier
import cn.limpu.hita.ui.design.personaSlashShape
import cn.limpu.hita.ui.design.soraCloudCardShape
import cn.limpu.hita.ui.design.SoraIndigo
import cn.limpu.hita.ui.design.SoraPaper
import cn.limpu.hita.ui.design.SoraVermilion
import com.limpu.style.ThemeTools
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

internal val CapsuleBlue = Color(0xFF3390EC)
internal val ActiveBlue = Color(0xFF0088CC)
private val CapsuleTabWidth = 68.dp
private val CapsuleTabIconSize = 18.dp

@Composable
internal fun MainPillTabBar(
    selectedTab: Int,
    visibleTabs: List<MainTab>,
    showBlogDot: Boolean,
    alpha: Float,
    themeStyle: ThemeTools.STYLE,
    hazeState: HazeState?,
    liquidGlassBackdrop: LiquidGlassBackdropHandle?,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = visibleTabs
    val view = LocalView.current
    val density = LocalDensity.current
    val availableWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 32.dp
    val tabWidth = minOf(if (tabs.size >= 5) 56.dp else CapsuleTabWidth, availableWidth / tabs.size.coerceAtLeast(1))
    val tabWidthPx = with(density) { tabWidth.toPx() }
    val reduced = LocalHitaReducedMotion.current

    val indicatorOffsetPx by animateFloatAsState(
        targetValue = selectedTab * tabWidthPx,
        animationSpec = HitaMotion.feedback(reduced),
        label = "pill_offset"
    )
    val isAppleGlass = themeStyle == ThemeTools.STYLE.APPLE_GLASS
    val isCyber = themeStyle == ThemeTools.STYLE.CYBER
    val isSoraCloud = themeStyle == ThemeTools.STYLE.SORA_CLOUD
    val isPersona = themeStyle == ThemeTools.STYLE.P5
    val isDeepSpace = themeStyle == ThemeTools.STYLE.DEEP_SPACE
    val isSumi = themeStyle == ThemeTools.STYLE.SUMI
    val appleGlassBarShape = RoundedCornerShape(30.dp)
    val barShape = when {
        isCyber -> cyberChamferShape(20.dp)
        isPersona -> personaSlashShape(18.dp)
        isAppleGlass -> appleGlassBarShape
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
    val bodyHeight = pillBarHeight(density.fontScale, rememberPillBarLabelHeight())
    val barHazeStyle = HazeStyle(
        tint = Color.White.copy(alpha = 0.16f),
        blurRadius = 24.dp,
        noiseFactor = 0.05f
    )

    Surface(
        modifier = modifier
            .height(bodyHeight)
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
                        shape = appleGlassBarShape,
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
                    isCyber -> Modifier.hitaCyberNeonEdge(barShape)
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
                    .width(tabWidth)
                    .height(44.dp),
                shape = indicatorShape,
                color = indicatorColor
            ) {}

            Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    val active = index == selectedTab
                    val hasUnread = showBlogDot && tab == MainTab.BLOG
                    val unreadLabel = if (hasUnread) stringResource(R.string.main_info_unread) else null

                    val tint by animateColorAsState(
                        targetValue = if (active) activeColor else MaterialTheme.colorScheme.onSurface,
                        animationSpec = HitaMotion.feedback(reduced),
                        label = "tab_tint_$index"
                    )

                    Column(
                        modifier = Modifier
                            .width(tabWidth)
                            .clip(indicatorShape)
                            .heightIn(min = 48.dp)
                            .selectable(selected = active, role = Role.Tab) {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                onSelectTab(index)
                            }
                            .then(if (unreadLabel != null) Modifier.semantics { stateDescription = unreadLabel } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(CapsuleTabIconSize)
                            )
                            if (hasUnread) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-2).dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                        }
                        Text(
                            text = stringResource(tab.titleRes),
                            color = tint,
                            style = pillBarLabelStyle(),
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
