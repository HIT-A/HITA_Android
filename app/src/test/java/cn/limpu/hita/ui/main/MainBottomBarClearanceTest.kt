package cn.limpu.hita.ui.main

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class MainBottomBarClearanceTest {
    @Test fun floatingDockUsesItsMeasuredBodyAndNavigationInset() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            assertEquals((75f + 10f * (scale - 1f)).dp, mainBottomBarClearance(true, 0.dp, scale))
            assertEquals((102f + 10f * (scale - 1f)).dp, mainBottomBarClearance(true, 48.dp, scale))
        }
    }
    @Test fun pillBarKeepsGestureGapAndAvoidsThreeButtonNavigation() {
        for ((scale, body) in listOf(1f to 52f, 1.3f to 52f, 2f to 57f)) {
            assertEquals((12f + body).dp, mainBottomBarClearance(false, 24.dp, scale))
            assertEquals((56f + body).dp, mainBottomBarClearance(false, 48.dp, scale))
        }
        assertEquals(12.dp, pillBarBottomPadding(31.dp))
        assertEquals(40.dp, pillBarBottomPadding(32.dp))
    }
    @Test fun largerFallbackFontMetricsAreIncludedInPillClearance() {
        assertEquals(57.dp, pillBarHeight(1.6f, 20.dp))
        assertEquals(69.dp, mainBottomBarClearance(false, 0.dp, 1.6f, 20.dp))
        assertEquals(113.dp, mainBottomBarClearance(false, 48.dp, 1.6f, 20.dp))
    }
}
