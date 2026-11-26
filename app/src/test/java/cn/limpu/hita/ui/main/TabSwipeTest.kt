package cn.limpu.hita.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TabSwipeTest {

    private val width = 1000f
    private val velocity = TabSwipe.COMMIT_VELOCITY_DP

    @Test fun `passing a quarter of the page commits in both directions`() {
        assertTrue(TabSwipe.commitProgress(0.25f, 0f, velocity, hasTarget = true))
        assertTrue(TabSwipe.commitProgress(0.9f, 0f, velocity, hasTarget = true))
        assertFalse(TabSwipe.commitProgress(0.24f, 0f, velocity, hasTarget = true))
    }

    @Test fun `even a tiny flick commits`() {
        // 原版 ViewPager 的 FLING 阈值是 50 dp/s，轻扫即可换页。
        assertEquals(50f, TabSwipe.COMMIT_VELOCITY_DP, 0f)
        assertTrue(TabSwipe.commitProgress(0.05f, 60f, velocity, hasTarget = true))
    }

    @Test fun `a short fast flick commits without crossing the threshold`() {
        assertTrue(TabSwipe.commitProgress(0.1f, 900f, velocity, hasTarget = true))
    }

    @Test fun `flicking back cancels even after crossing the threshold`() {
        // 与 PagerSnapHelper 一致：速度朝回退方向越过阈值时以速度为准，
        // 因此“推过阈值再甩回来”会回弹，而不是落在下一页。
        assertFalse(TabSwipe.commitProgress(0.8f, -900f, velocity, hasTarget = true))
    }

    @Test fun `velocity below the threshold falls back to distance`() {
        assertTrue(TabSwipe.commitProgress(0.6f, 40f, velocity, hasTarget = true))
        assertFalse(TabSwipe.commitProgress(0.2f, 40f, velocity, hasTarget = true))
    }

    @Test fun `edges never commit`() {
        assertFalse(TabSwipe.commitProgress(1f, 2000f, velocity, hasTarget = false))
    }

    @Test fun `dragging left advances to the next page`() {
        // 负位移是向左推，下一页从右侧进入；两页的进度增量都为正。
        assertEquals(0.4f, TabSwipe.progressDelta(-400f, 1, width), 0.001f)
        assertEquals(-0.4f, TabSwipe.progressDelta(400f, 1, width), 0.001f)
        assertEquals(0.4f, TabSwipe.progressDelta(400f, -1, width), 0.001f)
    }

    @Test fun `an unmeasured page neither moves nor divides`() {
        assertEquals(0f, TabSwipe.progressDelta(-400f, 1, 0f), 0f)
        assertEquals(0f, TabSwipe.progressDelta(-400f, 0, width), 0f)
    }

    @Test fun `the two pages keep exactly one screen between them`() {
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (direction in listOf(-1, 1)) {
                val anchor = TabSwipe.selectedOffset(direction, progress) * width
                val neighbor = TabSwipe.neighborOffset(direction, progress) * width
                assertEquals(width, abs(neighbor - anchor), 0.001f)
            }
        }
    }

    @Test fun `the neighbor waits off-screen and ends centered`() {
        assertEquals(width, TabSwipe.neighborOffset(1, 0f) * width, 0.001f)
        assertEquals(-width, TabSwipe.neighborOffset(-1, 0f) * width, 0.001f)
        assertEquals(0f, TabSwipe.neighborOffset(1, 1f), 0f)
        // 推满后锚点页正好整屏移出，相邻页居中。
        assertEquals(-width, TabSwipe.selectedOffset(1, 1f) * width, 0.001f)
        assertEquals(width, TabSwipe.selectedOffset(-1, 1f) * width, 0.001f)
    }

    @Test fun `an edge drag is damped instead of moving a page`() {
        assertEquals(0.08f, TabSwipe.selectedOffset(0, 0.08f), 0.001f)
        assertEquals(-0.08f, TabSwipe.selectedOffset(0, -0.08f), 0.001f)
    }

    @Test fun `rubber band is damped and capped in both directions`() {
        assertEquals(-width * TabSwipe.RUBBER_LIMIT_FRACTION, TabSwipe.rubberBand(-width, width), 0.001f)
        assertEquals(width * TabSwipe.RUBBER_LIMIT_FRACTION, TabSwipe.rubberBand(width, width), 0.001f)
        assertEquals(-80f * TabSwipe.RUBBER_DAMPING, TabSwipe.rubberBand(-80f, width), 0.001f)
        assertEquals(0f, TabSwipe.rubberBand(-80f, 0f), 0f)
    }

    @Test fun `short drags follow the pointer in both directions`() {
        assertEquals(-35f, TabSwipe.follow(-35f, width), 0.001f)
        assertEquals(35f, TabSwipe.follow(35f, width), 0.001f)
    }

    @Test fun `a single page stays mostly on screen during a full-width drag`() {
        assertEquals(-120f, TabSwipe.follow(-width, width), 0.001f)
        assertEquals(120f, TabSwipe.follow(width, width), 0.001f)
    }

    @Test fun `the actual follow range cannot make an interactive page transparent`() {
        assertEquals(1f, TabSwipe.alpha(0f, width), 0f)
        assertTrue(TabSwipe.alpha(TabSwipe.follow(-width, width), width) > 0.85f)
        assertTrue(TabSwipe.alpha(TabSwipe.follow(width, width), width) > 0.85f)
    }

    @Test fun `a temporarily unmeasured page neither moves nor disappears`() {
        assertEquals(0f, TabSwipe.follow(-80f, 0f), 0f)
        assertEquals(1f, TabSwipe.alpha(-80f, 0f), 0f)
    }
}
