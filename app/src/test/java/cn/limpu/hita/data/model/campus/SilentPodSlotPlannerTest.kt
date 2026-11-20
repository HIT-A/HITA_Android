package cn.limpu.hita.data.model.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可约窗口 → 开始/结束候选」的换算规则。
 *
 * 断言直接对应线上踩过的失败：窗口起点就是当前分钟（`INVALID_FIELD`）、
 * 整段窗口超过最大时长（`MORE_RESERVATION_DURATION`）。
 */
class SilentPodSlotPlannerTest {

    private fun space(
        category: SilentPodCategory = SilentPodCategory.SINGLE,
        allow: List<String>,
        min: Int? = 20,
        max: Int? = 60,
    ) = SilentPodSpace(
        id = 1,
        codeName = "S01",
        areaFullName = "三楼前区",
        spaceTypeName = "单人静音舱（无门禁）",
        category = category,
        capacity = 1,
        teamCapacity = if (category == SilentPodCategory.SINGLE) 1 else 2,
        isTeam = category != SilentPodCategory.SINGLE,
        isAccessControl = false,
        allowDurations = allow,
        disabledDurations = emptyList(),
        minDurationMinutes = min,
        maxDurationMinutes = max,
    )

    private fun hhmm(minutes: Int): String = SilentPodSlotPlanner.formatMinutes(minutes)

    @Test
    fun `parseWindow keeps both ends as absolute minutes`() {
        val window = SilentPodSlotPlanner.parseWindow("17:05-18:20")

        assertEquals(17 * 60 + 5, window?.startMinutes)
        assertEquals(18 * 60 + 20, window?.endMinutes)
        assertNull(SilentPodSlotPlanner.parseWindow("17:05"))
        assertNull(SilentPodSlotPlanner.parseWindow("20:00-20:00"))
        assertNull(SilentPodSlotPlanner.parseWindow("00:00-25:00"))
        assertNull(SilentPodSlotPlanner.parseWindow("8:00-9:00"))
    }

    @Test
    fun `effectiveWindows drops the part that already passed`() {
        val windows = SilentPodSlotPlanner.effectiveWindows(
            space(allow = listOf("20:40-21:50")),
            nowMinutes = 20 * 60 + 43,
        )

        assertEquals(1, windows.size)
        assertEquals("20:50", hhmm(windows[0].startMinutes))
        assertEquals("21:50", hhmm(windows[0].endMinutes))
    }

    @Test
    fun `window shorter than min duration is dropped entirely`() {
        val windows = SilentPodSlotPlanner.effectiveWindows(
            space(allow = listOf("20:40-21:50")),
            nowMinutes = 21 * 60 + 45,
        )

        assertTrue(windows.isEmpty())
    }

    @Test
    fun `closed space yields no options`() {
        val offline = space(allow = emptyList())

        assertTrue(SilentPodSlotPlanner.effectiveWindows(offline, nowMinutes = 22 * 60).isEmpty())
        assertTrue(SilentPodSlotPlanner.startOptions(offline, nowMinutes = 22 * 60).isEmpty())
    }

    @Test
    fun `startOptions stop one min duration before window end`() {
        val starts = SilentPodSlotPlanner.startOptions(
            space(allow = listOf("20:40-21:50")),
            nowMinutes = 20 * 60,
        )

        assertEquals(
            listOf("20:40", "20:50", "21:00", "21:10", "21:20", "21:30"),
            starts.map(::hhmm),
        )
    }

    @Test
    fun `startOptions start from now when the window already began`() {
        val starts = SilentPodSlotPlanner.startOptions(
            space(allow = listOf("20:40-21:50")),
            nowMinutes = 20 * 60 + 43,
        )

        assertEquals("20:50", hhmm(starts.first()))
        assertEquals("21:30", hhmm(starts.last()))
    }

    @Test
    fun `multiple windows produce a merged, sorted option list`() {
        val starts = SilentPodSlotPlanner.startOptions(
            space(allow = listOf("17:30-18:20", "20:50-21:50")),
            nowMinutes = 17 * 60,
        )

        assertEquals(
            listOf("17:30", "17:40", "17:50", "18:00", "20:50", "21:00", "21:10", "21:20", "21:30"),
            starts.map(::hhmm),
        )
    }

    @Test
    fun `endOptions respect min and max duration`() {
        val ends = SilentPodSlotPlanner.endOptions(
            space(allow = listOf("20:40-21:50")),
            startMinutes = 20 * 60 + 40,
        )

        // 20:40 + 20 = 21:00 起，最多到 21:40（20:40 + 60）。
        assertEquals(listOf("21:00", "21:10", "21:20", "21:30", "21:40"), ends.map(::hhmm))
    }

    @Test
    fun `endOptions never exceed the window end`() {
        val ends = SilentPodSlotPlanner.endOptions(
            space(allow = listOf("20:40-21:50")),
            startMinutes = 21 * 60 + 30,
        )

        assertEquals(listOf("21:50"), ends.map(::hhmm))
    }

    @Test
    fun `endOptions are empty when start is outside every window`() {
        val ends = SilentPodSlotPlanner.endOptions(
            space(allow = listOf("20:40-21:50")),
            startMinutes = 19 * 60,
        )

        assertTrue(ends.isEmpty())
    }

    @Test
    fun `defaultEnd prefers sixty minutes and falls back to the shortest`() {
        val ends = SilentPodSlotPlanner.endOptions(
            space(allow = listOf("20:40-21:50")),
            startMinutes = 20 * 60 + 40,
        )
        // 可选结束时间 21:00–21:40，60 分钟对应 21:40。
        assertEquals(21 * 60 + 40, SilentPodSlotPlanner.defaultEnd(20 * 60 + 40, ends))

        // 窗口太短、凑不到 60 分钟时退回最短可选。
        assertEquals(
            21 * 60 + 50,
            SilentPodSlotPlanner.defaultEnd(21 * 60 + 30, listOf(21 * 60 + 50)),
        )
        assertNull(SilentPodSlotPlanner.defaultEnd(null, emptyList()))
    }

    @Test
    fun `isValidSelection rejects duration outside limits and past starts`() {
        val silentPod = space(allow = listOf("20:40-21:50"))

        assertTrue(SilentPodSlotPlanner.isValidSelection(silentPod, 20 * 60 + 40, 21 * 60 + 40, 20 * 60))
        // 70 分钟超过单人仓上限 60
        assertFalse(SilentPodSlotPlanner.isValidSelection(silentPod, 20 * 60 + 40, 21 * 60 + 50, 20 * 60))
        // 10 分钟低于下限 20
        assertFalse(SilentPodSlotPlanner.isValidSelection(silentPod, 20 * 60 + 40, 20 * 60 + 50, 20 * 60))
        // 开始时间已经过去
        assertFalse(SilentPodSlotPlanner.isValidSelection(silentPod, 20 * 60 + 40, 21 * 60 + 40, 20 * 60 + 45))
        // 结束时间超出窗口
        assertFalse(SilentPodSlotPlanner.isValidSelection(silentPod, 21 * 60, 22 * 60, 20 * 60))
    }

    @Test
    fun `missing duration limits fall back by category`() {
        val single = space(category = SilentPodCategory.SINGLE, allow = emptyList(), min = null, max = null)
        assertEquals(20, SilentPodSlotPlanner.minDuration(single))
        assertEquals(60, SilentPodSlotPlanner.maxDuration(single))

        val quad = space(category = SilentPodCategory.QUAD_LOCKED, allow = emptyList(), min = null, max = null)
        assertEquals(30, SilentPodSlotPlanner.minDuration(quad))
        assertEquals(120, SilentPodSlotPlanner.maxDuration(quad))
    }

    @Test
    fun `ceilToStep rounds up to ten minute grid`() {
        assertEquals(0, SilentPodSlotPlanner.ceilToStep(0))
        assertEquals(10, SilentPodSlotPlanner.ceilToStep(5))
        assertEquals(10, SilentPodSlotPlanner.ceilToStep(10))
        assertEquals(50, SilentPodSlotPlanner.ceilToStep(43))
    }

    @Test
    fun `team space asks for one to three guests while single space asks none`() {
        val quad = space(category = SilentPodCategory.QUAD_LOCKED, allow = emptyList(), min = 30, max = 120)
            .copy(capacity = 4, teamCapacity = 2, isTeam = true)
        assertEquals(1..3, quad.requiredGuestRange)

        val single = space(allow = emptyList())
        assertNull(single.requiredGuestRange)
    }

    @Test
    fun `formatMinutes is zero padded and clamped`() {
        assertEquals("00:00", SilentPodSlotPlanner.formatMinutes(0))
        assertEquals("05:07", SilentPodSlotPlanner.formatMinutes(5 * 60 + 7))
        assertEquals("24:00", SilentPodSlotPlanner.formatMinutes(24 * 60 + 30))
    }
}
