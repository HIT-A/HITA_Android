package cn.limpu.hita.ui.main.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TodayLayoutPolicyTest {
    @Test fun ordinaryPortraitPhonesKeepFullSummariesFixed() {
        for ((width, height, bottom) in listOf(Triple(360f, 674f, 76f), Triple(360f, 674f, 64f),
            Triple(390f, 726f, 76f), Triple(360f, 622f, 76f))) {
            assertEquals(TodayLayoutMode.FIXED_FULL, TodayLayoutPolicy.choose(width, height, bottom, 1f))
        }
    }
    @Test fun insufficientHeightWidthAndLargeTextFallBackToOnePageList() {
        for ((width, height, bottom, scale) in listOf(listOf(360f, 240f, 76f, 1f),
            listOf(360f, 538f, 102f, 1.3f), listOf(320f, 674f, 76f, 2f))) {
            assertEquals(TodayLayoutMode.PAGE_SCROLL, TodayLayoutPolicy.choose(width, height, bottom, scale))
        }
    }
    @Test fun fullModeRespectsTheMinimumAgendaAtTheBoundary() {
        assertEquals(TodayLayoutMode.FIXED_FULL, TodayLayoutPolicy.choose(360f, 580f, 75f, 1f))
        assertEquals(TodayLayoutMode.PAGE_SCROLL, TodayLayoutPolicy.choose(360f, 579f, 75f, 1f))
    }
}
