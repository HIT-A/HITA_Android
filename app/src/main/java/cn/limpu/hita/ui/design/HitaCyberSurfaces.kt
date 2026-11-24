package cn.limpu.hita.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.limpu.style.ThemeTools
import kotlin.math.hypot

/**
 * 赛博风格（CYBER）的组件级造型工具。
 *
 * 与毛玻璃（Apple Glass）只改表面质感不同，赛博风格改变组件形态：
 * 对角切角、霓虹描边、彩色光晕。所有函数在非赛博风格下均为无操作，
 * 组件侧只需要把圆角 Shape 换成 [hitaStyleCardShape]，并按需叠加
 * [hitaCyberGlow] / [hitaCyberBorder] 即可完成风格适配。
 */

/** 当前是否为赛博风格 */
@Composable
fun hitaIsCyber(): Boolean = HitaTheme.preferenceStyle == ThemeTools.STYLE.CYBER

/** 赛博切角：左上 + 右下对角斜切，其余保持直角 */
fun cyberChamferShape(cut: Dp): Shape = CutCornerShape(topStart = cut, bottomEnd = cut)

/** 按当前风格返回卡片形状：赛博 → 对角切角；其他 → 圆角 */
@Composable
fun hitaStyleCardShape(roundedRadius: Dp, cyberCut: Dp): Shape {
    return when {
        hitaIsCyber() -> cyberChamferShape(cyberCut)
        hitaIsPersona() -> personaSlashShape(cyberCut)
        hitaIsSoraCloud() -> soraCloudCardShape(cyberCut)
        hitaIsDeepSpace() -> deepSpaceCardShape(roundedRadius + 6.dp)
        else -> RoundedCornerShape(roundedRadius)
    }
}

/** 赛博霓虹描边；其他风格返回 null（可直接传给 Card 的 border 参数） */
@Composable
fun hitaCyberBorder(
    width: Dp = 1.dp,
    alpha: Float = 0.55f,
    color: Color = MaterialTheme.colorScheme.primary,
): BorderStroke? {
    return if (hitaIsCyber()) BorderStroke(width, color.copy(alpha = alpha)) else null
}

/** 赛博霓虹光晕（彩色阴影）；其他风格无操作 */
@Composable
fun Modifier.hitaCyberGlow(
    shape: Shape,
    elevation: Dp = 14.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    alpha: Float = 0.38f,
): Modifier {
    return if (hitaIsCyber()) {
        this.shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = color.copy(alpha = alpha),
            spotColor = color.copy(alpha = alpha)
        )
    } else {
        this
    }
}

/** 缓存静态霓虹，不为无状态的装饰持续请求帧。 */
@Composable
internal fun Modifier.hitaCyberNeonEdge(
    shape: Shape,
    width: Dp = 1.2.dp,
    color1: Color = MaterialTheme.colorScheme.primary,
    color2: Color = MaterialTheme.colorScheme.secondary,
    color3: Color = MaterialTheme.colorScheme.tertiary,
): Modifier {
    if (!hitaIsCyber()) return this
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val periodPx = 360.dp.toPx()
        val diagLen = hypot(size.width, size.height)
        val ux = if (diagLen > 0f) size.width / diagLen else 1f
        val uy = if (diagLen > 0f) size.height / diagLen else 0f
        val colors = listOf(color1, color2, color3, color1)
        val edgeBrush = Brush.linearGradient(
            colors = colors,
            start = Offset.Zero,
            end = Offset(ux * periodPx, uy * periodPx),
            tileMode = TileMode.Repeated
        )
        val haloStroke = Stroke(width = width.toPx() * 3.2f)
        val midStroke = Stroke(width = width.toPx() * 1.8f)
        val coreStroke = Stroke(width = width.toPx())
        onDrawWithContent {
            drawContent()
            drawOutline(outline = outline, brush = edgeBrush, alpha = 0.16f, style = haloStroke)
            drawOutline(outline = outline, brush = edgeBrush, alpha = 0.32f, style = midStroke)
            drawOutline(outline = outline, brush = edgeBrush, alpha = 0.95f, style = coreStroke)
        }
    }
}
