package cn.limpu.hita.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaDeepSpaceBackground
import cn.limpu.hita.ui.design.hitaSoraCloudBackground
import cn.limpu.hita.ui.design.hitaSumiBackground
import com.limpu.style.ThemeTools

/** Capture the artwork and page (including any wallpaper), but not the base or foreground chrome. */
@Composable
internal fun MainThemeBackground(
    captureModifier: Modifier = Modifier,
    page: @Composable BoxScope.() -> Unit,
    chrome: @Composable BoxScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().then(captureModifier)) {
            when (HitaTheme.preferenceStyle) {
                ThemeTools.STYLE.APPLE_GLASS -> AppleGlassBackground()
                ThemeTools.STYLE.SORA_CLOUD -> Box(Modifier.fillMaxSize().hitaSoraCloudBackground())
                ThemeTools.STYLE.DEEP_SPACE -> Box(Modifier.fillMaxSize().hitaDeepSpaceBackground())
                ThemeTools.STYLE.SUMI -> Box(Modifier.fillMaxSize().hitaSumiBackground())
                ThemeTools.STYLE.CLASSIC, ThemeTools.STYLE.CYBER, ThemeTools.STYLE.P5 -> Unit
            }
            page()
        }
        chrome()
    }
}

@Composable
private fun AppleGlassBackground() {
    val colors = if (HitaTheme.isDark) {
        listOf(
            Color(0xFF080B10),
            Color(0xFF111823),
            Color(0xFF0C1017),
        )
    } else {
        listOf(
            Color(0xFFF5F9FE),
            Color(0xFFEAF3FC),
            Color(0xFFEFF7FF),
        )
    }
    val atmosphere = if (HitaTheme.isDark) {
        listOf(
            Color.White.copy(alpha = 0.42f),
            Color.Transparent,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        )
    } else {
        listOf(
            Color.White.copy(alpha = 0.20f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.045f),
            Color(0xFF9CCBFA).copy(alpha = 0.16f)
        )
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors)))
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(atmosphere)))
}
