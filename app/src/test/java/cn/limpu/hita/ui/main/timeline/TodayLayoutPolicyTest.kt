package cn.limpu.hita.ui.main.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayLayoutPolicyTest {
    @Test fun ordinaryPortraitPhonesKeepFullSummariesFixed() {
        for ((width, height, bottom) in listOf(Triple(360f, 674f, 76f), Triple(360f, 674f, 64f),
            Triple(390f, 726f, 76f), Triple(360f, 622f, 76f), Triple(360f, 538f, 102f))) {
            assertEquals(TodayLayoutMode.FIXED_FULL, TodayLayoutPolicy.choose(width, height, bottom, 1f))
        }
    }
    @Test fun insufficientHeightWidthAndLargeTextFallBackToOnePageList() {
        for ((width, height, bottom, scale) in listOf(listOf(360f, 240f, 76f, 1f),
            listOf(360f, 320f, 76f, 1.3f), listOf(320f, 674f, 76f, 2f))) {
            assertEquals(TodayLayoutMode.PAGE_SCROLL, TodayLayoutPolicy.choose(width, height, bottom, scale))
        }
    }
    @Test fun shortScreensAndModeratelyLargeTextUseCompactSummaries() {
        for ((width, height, bottom, scale) in listOf(listOf(360f, 440f, 102f, 1f),
            listOf(360f, 538f, 102f, 1.3f), listOf(360f, 674f, 76f, 1.3f), listOf(360f, 674f, 76f, 1.6f))) {
            assertEquals(TodayLayoutMode.FIXED_COMPACT, TodayLayoutPolicy.choose(width, height, bottom, scale))
        }
        assertEquals(TodayLayoutMode.PAGE_SCROLL, TodayLayoutPolicy.choose(360f, 674f, 76f, 1.61f))
    }
    @Test fun fullModeRespectsTheMinimumAgendaAtTheBoundary() {
        assertEquals(TodayLayoutMode.FIXED_FULL, TodayLayoutPolicy.choose(360f, 442f, 75f, 1f))
        assertEquals(TodayLayoutMode.FIXED_COMPACT, TodayLayoutPolicy.choose(360f, 441f, 75f, 1f))
    }
    @Test fun sixCoursesFitWithoutScrollingOnAShortPhone() {
        for (compact in listOf(false, true)) {
            assertTrue(TodayLayoutPolicy.agendaRowsAreDense(674f, 76f, compact, 1f, 6))
            val available = 674f - 76f - TodayLayoutPolicy.summariesHeight(compact, 1f) - 24f
            assertTrue(TodayLayoutPolicy.denseAgendaHeight(6) <= available)
        }
    }
    @Test fun shortDaysKeepTheFullCourseCards() {
        assertFalse(TodayLayoutPolicy.agendaRowsAreDense(674f, 76f, false, 1f, 2))
        assertFalse(TodayLayoutPolicy.agendaRowsAreDense(674f, 76f, false, 1f, 3))
    }
}
