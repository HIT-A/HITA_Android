package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
internal fun todayTonalContainer(accent: Color): Color = lerp(
    MaterialTheme.colorScheme.surface.copy(alpha = 1f), accent,
    if (HitaTheme.isDark) 0.18f else 0.11f,
)

@Composable
internal fun TodayCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = hitaStyleCardShape(24.dp, 8.dp)
    val cardModifier = modifier.fillMaxWidth().hitaGlassCardModifier(shape, elevation = 0.dp)
    val colors = hitaGlassCardColors(containerColor = container, glassAlpha = 0.76f)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
    if (onClick == null) Card(modifier = cardModifier, shape = shape, colors = colors,
        border = hitaGlassCardBorder(), content = { body() })
    else Card(onClick = onClick, modifier = cardModifier, shape = shape, colors = colors,
        border = hitaGlassCardBorder(), content = { body() })
}

/** Keep the theme font and explicit line-height style; avoid trimming first/last lines by default. */
@Composable
private fun todayTextStyle(size: Int, height: Int): TextStyle {
    val inherited = LocalTextStyle.current
    return inherited.merge(TextStyle(fontSize = size.sp, lineHeight = height.sp,
        lineHeightStyle = inherited.lineHeightStyle ?: LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)))
}

@Composable internal fun todayCaption(): TextStyle = todayTextStyle(11, 16)
@Composable internal fun todayLabel(): TextStyle = todayTextStyle(12, 16)
@Composable internal fun todayMeta(): TextStyle = todayTextStyle(13, 18)
@Composable internal fun todayBody(): TextStyle = todayTextStyle(14, 20)
