package cn.limpu.hita.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwipeTest {
    private val width = 1000f
    private val velocity = 500f

    @Test fun `distance past the fraction commits to the next page`() {
        val direction = TabSwipe.commitDirection(
            travelled = -width * TabSwipe.COMMIT_FRACTION - 1f,
            widthPx = width,
            velocityPxPerSecond = 0f,
            velocityThresholdPxPerSecond = velocity,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(1, direction)
    }

    @Test fun `distance past the fraction backwards commits to the previous page`() {
        val direction = TabSwipe.commitDirection(
            travelled = width * TabSwipe.COMMIT_FRACTION + 1f,
            widthPx = width,
            velocityPxPerSecond = 0f,
            velocityThresholdPxPerSecond = velocity,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(-1, direction)
    }

    @Test fun `a flick commits even when the drag is short`() {
        assertEquals(
            1,
            TabSwipe.commitDirection(-40f, width, -900f, velocity, canPrevious = true, canNext = true),
        )
        assertEquals(
            -1,
            TabSwipe.commitDirection(40f, width, 900f, velocity, canPrevious = true, canNext = true),
        )
    }

    @Test fun `a short slow drag rebounds`() {
        assertEquals(0, TabSwipe.commitDirection(-30f, width, 120f, velocity, canPrevious = true, canNext = true))
        assertEquals(0, TabSwipe.commitDirection(0f, width, 0f, velocity, canPrevious = true, canNext = true))
    }

    @Test fun `velocity wins over distance when it passes the threshold`() {
        // 与 PagerSnapHelper 一致：越过速度阈值时以速度方向为准，
        // 因此「向左拖出去再向右甩」会回到上一页，而不是停在下一页。
        assertEquals(-1, TabSwipe.commitDirection(-width * 0.4f, width, 800f, velocity, canPrevious = true, canNext = true))
        assertEquals(1, TabSwipe.commitDirection(width * 0.4f, width, -800f, velocity, canPrevious = true, canNext = true))
    }

    @Test fun `velocity below the threshold falls back to distance`() {
        assertEquals(1, TabSwipe.commitDirection(-width * 0.4f, width, 480f, velocity, canPrevious = true, canNext = true))
        assertEquals(0, TabSwipe.commitDirection(-width * 0.1f, width, 480f, velocity, canPrevious = true, canNext = true))
    }

    @Test fun `edges never slide outward`() {
        assertEquals(
            0,
            TabSwipe.commitDirection(-width, width, -2000f, velocity, canPrevious = true, canNext = false),
        )
        assertEquals(
            0,
            TabSwipe.commitDirection(width, width, 2000f, velocity, canPrevious = false, canNext = true),
        )
    }

    @Test fun `rubber band is damped and capped in both directions`() {
        assertEquals(-width * TabSwipe.RUBBER_LIMIT_FRACTION, TabSwipe.rubberBand(-width, width), 0.001f)
        assertEquals(width * TabSwipe.RUBBER_LIMIT_FRACTION, TabSwipe.rubberBand(width, width), 0.001f)
        assertEquals(-80f * TabSwipe.RUBBER_DAMPING, TabSwipe.rubberBand(-80f, width), 0.001f)
        assertEquals(0f, TabSwipe.rubberBand(-80f, 0f), 0f)
    }

    @Test fun `exit keeps the gesture direction instead of reversing`() {
        // 向左滑（travelled < 0，direction = 1）继续向左推，因此终点为负；向右滑相反。
        assertTrue(TabSwipe.exitTarget(1, width) < 0f)
        assertTrue(TabSwipe.exitTarget(-1, width) > 0f)
        assertEquals(-width, TabSwipe.exitTarget(1, width), 0.001f)
        assertEquals(width, TabSwipe.exitTarget(-1, width), 0.001f)
    }

    @Test fun `enter starts from the opposite edge so it slides in the same direction`() {
        // 向左滑后新页从右侧进入，位移同为负方向，视觉上与前一段连贯。
        assertTrue(TabSwipe.enterSource(1, width) > 0f)
        assertTrue(TabSwipe.enterSource(-1, width) < 0f)
        assertEquals(width, TabSwipe.enterSource(1, width), 0.001f)
    }

    @Test fun `alpha fades out exactly when the page leaves the screen`() {
        assertEquals(1f, TabSwipe.alpha(0f, width), 0f)
        assertEquals(0.5f, TabSwipe.alpha(-width / 2f, width), 0.001f)
        assertEquals(0f, TabSwipe.alpha(TabSwipe.exitTarget(1, width), width), 0f)
        assertEquals(0f, TabSwipe.alpha(TabSwipe.enterSource(1, width), width), 0f)
        // 位移不会为负透明度，也不会因超界反而变亮。
        assertEquals(0f, TabSwipe.alpha(-width * 2f, width), 0f)
        assertEquals(1f, TabSwipe.alpha(-width / 2f, 0f), 0f)
    }

    @Test fun `exit and enter never point the same way as the incoming page`() {
        // 两段动画必须首尾相接：滑出终点与新页入场起点分处两侧，中间隔着换页的那一帧。
        val out = TabSwipe.exitTarget(1, width)
        val incoming = TabSwipe.enterSource(1, width)
        assertTrue("滑出应在左侧", out < 0f)
        assertTrue("新页应从右侧进入", incoming > 0f)
    }
}
