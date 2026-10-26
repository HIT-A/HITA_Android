package cn.limpu.hita.ui.main

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

internal class LiquidGlassBackdropHandle(val value: Any)

internal fun Modifier.mainLiquidGlassSurface(
    backdropHandle: LiquidGlassBackdropHandle,
    shape: CornerBasedShape,
    blurRadius: Float,
    refractionHeight: Float,
    refractionAmount: Float,
): Modifier {
    val backdrop = backdropHandle.value as com.kyant.backdrop.backdrops.LayerBackdrop
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(blurRadius)
            vibrancy()
            colorControls(brightness = 0.05f, contrast = 1.08f, saturation = 1.16f)
            lens(
                refractionHeight = refractionHeight,
                refractionAmount = refractionAmount,
                depthEffect = true,
                chromaticAberration = true
            )
        },
        highlight = {
            Highlight(
                width = 1.2.dp,
                blurRadius = 7.dp,
                alpha = 0.92f,
                style = HighlightStyle.Default(intensity = 0.7f, angle = 315f, falloff = 1.35f)
            )
        },
        shadow = {
            Shadow(
                radius = 30.dp,
                offset = DpOffset(0.dp, 9.dp),
                color = Color.Black.copy(alpha = 0.13f)
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 18.dp,
                offset = DpOffset(0.dp, 8.dp),
                color = Color.White.copy(alpha = 0.20f),
                alpha = 0.82f
            )
        }
    )
}
