package cn.limpu.hita.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.limpu.hita.ui.design.HitaTheme

internal fun pillBarBottomPadding(navBottom: Dp): Dp = if (navBottom >= 32.dp) navBottom + 8.dp else 12.dp
internal fun pillBarHeight(fontScale: Float, labelHeight: Dp = (10f * fontScale).dp): Dp =
    8.dp + maxOf(44.dp, 29.dp + labelHeight)
internal fun mainBottomBarClearance(dock: Boolean, navBottom: Dp, fontScale: Float,
    pillLabelHeight: Dp = (10f * fontScale).dp): Dp =
    if (dock) maxOf(DockMetrics.EDGE_MARGIN.dp, navBottom) + dockBarHeight(DockMetrics.LABEL_SIZE * fontScale).dp
    else pillBarBottomPadding(navBottom) + pillBarHeight(fontScale, pillLabelHeight)

/** Fragment ComposeViews may not receive insets; read the root as well and track window changes. */
@Composable
internal fun rememberMainNavigationBottom(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var rootBottom by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val root = view.rootView
        fun update() {
            rootBottom = ViewCompat.getRootWindowInsets(root)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        }
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { update() }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        update()
        onDispose { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return with(density) { maxOf(rootBottom, WindowInsets.navigationBars.getBottom(this)).toDp() }
}

/** Use the same text style for label measurement and rendering, including theme fonts. */
@Composable
internal fun pillBarLabelStyle(): TextStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 10.sp, lineHeight = 10.sp))

@Composable
internal fun rememberPillBarLabelHeight(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = pillBarLabelStyle()
    // CJK fallback font metrics can exceed 10sp even with a 10sp line height. Include both weights.
    val pixels = MainTab.entries.maxOf { tab ->
        listOf(FontWeight.Normal, FontWeight.Bold).maxOf { weight ->
            measurer.measure(context.getString(tab.titleRes), style = style.copy(fontWeight = weight), maxLines = 1).size.height
        }
    }
    return with(density) { pixels.toDp() }
}

@Composable
internal fun rememberMainBottomBarClearance(): Dp {
    val dock = usesDockTabBar(HitaTheme.preferenceStyle)
    val scale = LocalDensity.current.fontScale
    return mainBottomBarClearance(dock, rememberMainNavigationBottom(), scale,
        if (dock) (10f * scale).dp else rememberPillBarLabelHeight())
}
