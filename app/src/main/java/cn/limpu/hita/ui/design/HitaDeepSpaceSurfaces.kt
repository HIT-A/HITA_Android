package cn.limpu.hita.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.limpu.style.ThemeTools
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 深空（星河）风格的组件级造型工具。
 *
 * 视觉语言：静谧的夜空、银河星带、星云微光、星砂金点缀。
 * 刻意避开科幻/科技元素：没有霓虹蓝光、没有切角、没有扫描线，
 * 只有夜色、星光与柔和的星辉。所有函数在非深空风格下均为无操作。
 */

/** 当前是否为深空风格 */
@Composable
fun hitaIsDeepSpace(): Boolean = HitaTheme.preferenceStyle == ThemeTools.STYLE.DEEP_SPACE

/** 深空大圆角：星球般的柔和弧度 */
fun deepSpaceCardShape(radius: Dp): Shape = RoundedCornerShape(radius)

/**
 * 深空星野卡片：柔和星光辉光 + 卡片内星点 + 极细金边。
 * 没有硬线、没有强对比，光都是「远」的。
 *
 * @param glowTint 辉光/边缘色（默认星砂金；课程卡传课程色）
 */
@Composable
fun Modifier.hitaDeepSpaceCardModifier(
    shape: Shape,
    elevation: Dp = 10.dp,
    glowTint: Color? = null,
    /**
     * 阴影单独用的轮廓。默认与 [shape] 一致；传平台友好的圆角矩形可在
     * API 26~28 上保住投影（系统 HWUI 不认通用路径轮廓）。issue #19
     */
    shadowShape: Shape? = null,
): Modifier {
    if (!hitaIsDeepSpace()) return this
    val isDark = HitaTheme.isDark
    val scheme = MaterialTheme.colorScheme
    val glow = glowTint ?: scheme.primary
    val nebula = scheme.secondary
    val rose = scheme.tertiary
    val shadowElevation = min(elevation.value * 0.5f, 6f).dp
    return this
        .shadow(
            elevation = shadowElevation,
            shape = shadowShape ?: shape,
            clip = false,
            ambientColor = glow.copy(alpha = if (isDark) 0.10f else 0.06f),
            spotColor = glow.copy(alpha = if (isDark) 0.14f else 0.09f)
        )
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val big = max(size.width, size.height)
            // 星云微光：左上暗紫、右下暗玫瑰。issue #20：必须画在 drawContent()
            // 之后，否则会被卡片容器底色整片盖住；alpha 提到可见阈值以上
            //（此前暗色 0.10f 时提亮仅约 15/255，低于视觉阈值）。
            val nebulaBrush = Brush.radialGradient(
                colors = listOf(nebula.copy(alpha = if (isDark) 0.26f else 0.14f), Color.Transparent),
                center = Offset(size.width * 0.12f, size.height * 0.08f),
                radius = big * 0.75f
            )
            val roseBrush = Brush.radialGradient(
                colors = listOf(rose.copy(alpha = if (isDark) 0.22f else 0.12f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.95f),
                radius = big * 0.70f
            )
            // 卡片内星点（固定种子，9 颗）
            val rnd = Random(42)
            val stars = List(9) {
                Triple(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat() * 0.9f)
            }
            val starColor = (if (isDark) Color.White else glow).copy(alpha = if (isDark) 0.55f else 0.38f)
            val edgeStroke = Stroke(width = 0.8.dp.toPx())
            val edgeColor = glow.copy(alpha = if (isDark) 0.32f else 0.38f)
            onDrawWithContent {
                // 容器底色先画，星云叠在之上（issue #20：此前在底色之下，不可见）
                drawContent()
                drawOutline(outline = outline, brush = nebulaBrush)
                drawOutline(outline = outline, brush = roseBrush)
                stars.forEach { (fx, fy, r) ->
                    drawCircle(starColor, radius = r.dp.toPx(), center = Offset(size.width * fx, size.height * fy))
                }
                drawOutline(outline = outline, brush = SolidColor(edgeColor), style = edgeStroke)
            }
        }
}

/**
 * 深空星野背景：银河星带、星云、行星与星座作为静态饰面。
 */
@Composable
fun Modifier.hitaDeepSpaceBackground(): Modifier {
    if (!hitaIsDeepSpace()) return this
    val isDark = HitaTheme.isDark
    val scheme = MaterialTheme.colorScheme
    return this.drawWithCache {
        val w = size.width
        val h = size.height
        val nebula1 = Brush.radialGradient(
            colors = listOf(scheme.secondary.copy(alpha = if (isDark) 0.16f else 0.09f), Color.Transparent),
            center = Offset(w * 0.18f, h * 0.16f),
            radius = w * 0.70f
        )
        val nebula2 = Brush.radialGradient(
            colors = listOf(scheme.tertiary.copy(alpha = if (isDark) 0.12f else 0.07f), Color.Transparent),
            center = Offset(w * 0.85f, h * 0.72f),
            radius = w * 0.65f
        )
        val rnd = Random(7)
        // 银河星带：沿对角线分布的密集星流
        val bandStars = List(110) {
            val t = rnd.nextFloat()
            Triple(
                t * w,
                h * 0.30f + t * h * 0.45f + (rnd.nextFloat() - 0.5f) * h * 0.10f,
                0.4f + rnd.nextFloat() * 1.0f
            )
        }
        // 弥散背景星
        val fieldStars = List(80) {
            Triple(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.4f + rnd.nextFloat() * 0.9f)
        }
        val featuredStars = bandStars.take(18)
        // 星座连线（两组小星座，星图感）
        val constellations = listOf(
            listOf(
                Offset(w * 0.12f, h * 0.55f), Offset(w * 0.18f, h * 0.50f),
                Offset(w * 0.25f, h * 0.53f), Offset(w * 0.30f, h * 0.47f)
            ),
            listOf(
                Offset(w * 0.58f, h * 0.30f), Offset(w * 0.64f, h * 0.35f),
                Offset(w * 0.71f, h * 0.33f), Offset(w * 0.76f, h * 0.38f)
            )
        )
        // 行星与星环（右上，远端静谧的一颗）
        val planetCx = w * 0.86f
        val planetCy = h * 0.13f
        val planetR = w * 0.10f
        val planetBody = Brush.radialGradient(
            colors = if (isDark) {
                listOf(Color(0xFF232E52), Color(0xFF0A0F22))
            } else {
                listOf(Color(0xFF8B97B8), Color(0xFF4A5578))
            },
            center = Offset(planetCx - planetR * 0.35f, planetCy - planetR * 0.35f),
            radius = planetR * 1.7f
        )
        val ringColor = scheme.primary.copy(alpha = if (isDark) 0.45f else 0.40f)
        val rimColor = scheme.primary.copy(alpha = if (isDark) 0.55f else 0.45f)
        val starBright = if (isDark) Color.White.copy(alpha = 0.65f) else scheme.onSurface.copy(alpha = 0.28f)
        val starDim = if (isDark) Color.White.copy(alpha = 0.30f) else scheme.onSurface.copy(alpha = 0.16f)
        val constellationInk = scheme.primary.copy(alpha = if (isDark) 0.30f else 0.25f)
        val constellationStar = if (isDark) Color.White.copy(alpha = 0.85f) else scheme.onSurface.copy(alpha = 0.40f)
        onDrawWithContent {
            drawRect(brush = nebula1)
            drawRect(brush = nebula2)
            // 行星体 + 金色弦边 + 星环
            drawCircle(planetBody, radius = planetR, center = Offset(planetCx, planetCy))
            drawArc(
                color = rimColor,
                startAngle = -115f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(planetCx - planetR, planetCy - planetR),
                size = androidx.compose.ui.geometry.Size(planetR * 2f, planetR * 2f),
                style = Stroke(width = 1.5.dp.toPx())
            )
            withTransform({
                rotate(degrees = -18f, pivot = Offset(planetCx, planetCy))
            }) {
                drawOval(
                    color = ringColor,
                    topLeft = Offset(planetCx - planetR * 1.8f, planetCy - planetR * 0.5f),
                    size = androidx.compose.ui.geometry.Size(planetR * 3.6f, planetR * 1.0f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            fieldStars.forEach { (x, y, r) -> drawCircle(starDim, r.dp.toPx(), Offset(x, y)) }
            bandStars.forEach { (x, y, r) -> drawCircle(starBright, r.dp.toPx(), Offset(x, y)) }
            // 星座：连线 + 稍亮的节点星
            constellations.forEach { points ->
                for (i in 0 until points.size - 1) {
                    drawLine(constellationInk, points[i], points[i + 1], strokeWidth = 0.5.dp.toPx())
                }
                points.forEach { p -> drawCircle(constellationStar, 1.3.dp.toPx(), p) }
            }
            featuredStars.forEach { (x, y, r) ->
                drawCircle(Color.White.copy(alpha = 0.42f), (r + 0.3f).dp.toPx(), Offset(x, y))
            }
            drawContent()
        }
    }
}
