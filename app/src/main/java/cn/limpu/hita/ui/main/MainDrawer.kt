package cn.limpu.hita.ui.main

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.DeveloperCredit
import cn.limpu.hita.ui.design.DeveloperCredits
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaCyberFlowEdge
import cn.limpu.hita.ui.design.hitaCyberGlow
import cn.limpu.hita.ui.design.hitaPersonaCardModifier
import cn.limpu.hita.ui.design.hitaDeepSpaceCardModifier
import cn.limpu.hita.ui.design.hitaSumiCardModifier
import cn.limpu.hita.ui.design.hitaSoraCloudCardModifier
import cn.limpu.hita.ui.design.SoraIndigo
import cn.limpu.hita.ui.design.SoraPaper
import cn.limpu.hita.ui.design.SoraVermilion
import com.limpu.style.ThemeTools
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

@Composable
internal fun MainDrawer(
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
    onGitHubJixing: () -> Unit,
    onGitHubRadiant: () -> Unit,
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
                    "${stringResource(R.string.main_drawer_menu_theme_style)} · ${stringResource(themeStyle.mainDisplayName())}",
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                DeveloperCredits(
                    credits = listOf(
                        DeveloperCredit(R.string.main_drawer_github_user, onGitHubUser),
                        DeveloperCredit(R.string.main_drawer_github_project, onGitHubProject),
                        DeveloperCredit(R.string.main_drawer_developer_mingyu, onGitHubMingyu),
                        DeveloperCredit(R.string.main_drawer_developer_ruannuo, onGitHubRuannuo),
                        DeveloperCredit(R.string.main_drawer_developer_jixing, onGitHubJixing),
                        DeveloperCredit(R.string.main_drawer_developer_radiant, onGitHubRadiant)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
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
