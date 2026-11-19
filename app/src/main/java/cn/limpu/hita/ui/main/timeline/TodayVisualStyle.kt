package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaStyleCardShape

/** Today's surfaces use the existing theme and course palette; no independent theme preference. */
@Composable
internal fun todayTonalContainer(accent: Color, muted: Boolean = false): Color = lerp(
    MaterialTheme.colorScheme.surface.copy(alpha = 1f), accent,
    if (HitaTheme.isDark) { if (muted) 0.16f else 0.24f } else { if (muted) 0.12f else 0.18f },
)

@Composable
internal fun TodayCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    spacing: Dp = 8.dp,
    edgeTint: Color? = null,
    elevation: Dp = 0.dp,
    opaque: Boolean = false,
    border: BorderStroke? = hitaGlassCardBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = hitaStyleCardShape(24.dp, 8.dp)
    // 玻璃色板的投影只有 5%–8% 黑，压在浅色背景上基本看不见；
    // 需要抬高的卡片自己再加一层可见的柔和投影。
    val lift = if (elevation > 0.dp) Modifier.shadow(
        elevation = elevation * 0.6f,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (HitaTheme.isDark) 0.14f else 0.10f),
        spotColor = Color.Black.copy(alpha = if (HitaTheme.isDark) 0.22f else 0.16f),
    ) else Modifier
    val cardModifier = modifier.fillMaxWidth().then(lift).hitaGlassCardModifier(shape, elevation = elevation, edgeTint = edgeTint)
    // 玻璃色板在浅色下会把卡底压成半透明，叠在页面背景上偏灰；需要白卡时直接做实。
    val colors = if (opaque && !HitaTheme.isDark) CardDefaults.cardColors(containerColor = container.copy(alpha = 1f))
    else hitaGlassCardColors(containerColor = container, glassAlpha = 0.76f)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
    if (onClick == null) Card(modifier = cardModifier, shape = shape, colors = colors,
        border = border, content = { body() })
    else Card(onClick = onClick, modifier = cardModifier, shape = shape, colors = colors,
        border = border, content = { body() })
}

/** Keep the theme font and explicit line-height style; avoid trimming first/last lines by default. */
@Composable
private fun todayTextStyle(size: Int, height: Int): TextStyle {
    val inherited = LocalTextStyle.current
    return inherited.merge(TextStyle(fontSize = size.sp, lineHeight = height.sp,
        lineHeightStyle = inherited.lineHeightStyle ?: LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)))
}

@Composable internal fun todayLabel(): TextStyle = todayTextStyle(12, 16)
@Composable internal fun todayMeta(): TextStyle = todayTextStyle(13, 18)
