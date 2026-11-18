package cn.limpu.hita.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.limpu.hita.ui.design.HitaTheme

internal fun pillBarBottomPadding(navBottom: Dp): Dp = if (navBottom >= 32.dp) navBottom + 8.dp else 12.dp
internal fun pillBarHeight(fontScale: Float): Dp = (8f + maxOf(44f, 29f + 10f * fontScale)).dp
internal fun mainBottomBarClearance(dock: Boolean, navBottom: Dp, fontScale: Float): Dp =
    if (dock) maxOf(DockMetrics.EDGE_MARGIN.dp, navBottom) + dockBarHeight(DockMetrics.LABEL_SIZE * fontScale).dp
    else pillBarBottomPadding(navBottom) + pillBarHeight(fontScale)

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

@Composable
internal fun rememberMainBottomBarClearance(): Dp = mainBottomBarClearance(
    usesDockTabBar(HitaTheme.preferenceStyle), rememberMainNavigationBottom(), LocalDensity.current.fontScale,
)
