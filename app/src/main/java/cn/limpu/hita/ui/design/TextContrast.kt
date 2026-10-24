package cn.limpu.hita.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 白字压在品牌色（或品牌色渐变）上时需要的"压暗"比例。
 *
 * 深色模式下 primary/secondary 往往是浅色（如经典 #6BB5F7、深空 #EDEEF0 的渐变），
 * 白字直接叠上去只有 1.7–2.3:1。这里按 WCAG 反推需要把底色压暗多少：
 * 白字要达到 [targetRatio]，背景最大亮度为 `1.05 / ratio - 0.05`。
 *
 * @return 0f 表示原色已经够暗；否则返回把颜色向黑色混合的比例（0..1）。
 */
fun scrimForWhiteText(base: Color, targetRatio: Float = 4.6f): Float {
    val maxLuminance = 1.05f / targetRatio - 0.05f
    if (base.luminance() <= maxLuminance) return 0f
    var low = 0f
    var high = 0.92f
    repeat(12) {
        val mid = (low + high) / 2f
        if (lerp(base, Color.Black, mid).luminance() <= maxLuminance) high = mid else low = mid
    }
    return high
}

/** 按 [scrimForWhiteText] 算出的比例把颜色压暗，得到可直接用作渐变端点的颜色。 */
fun shadeForWhiteText(base: Color, targetRatio: Float = 4.6f): Color =
    lerp(base, Color.Black, scrimForWhiteText(base, targetRatio))
