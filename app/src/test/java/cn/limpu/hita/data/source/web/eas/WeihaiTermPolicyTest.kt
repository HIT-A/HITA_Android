package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.TermItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * 威海学期列表策略的单测。
 *
 * 背景：威海成绩页只能选到秋季学期，根因是旧实现用非单调的时间轴 key
 * （秋季取学年起始年、春季/夏季取学年结束年）判断"是否晚于当前学期"，
 * 把上一学年的春季/夏季误判为未来学期删除。这里用纯 JVM 测试锁住行为，
 * 不需要威海账号即可回归。
 */
class WeihaiTermPolicyTest {

    private fun term(yearCode: String, termCode: String, current: Boolean = false): TermItem =
        TermItem(yearCode, yearCode, termCode, "$yearCode 第${termCode}学期").apply {
            isCurrent = current
        }

    @Test
    fun `keeps spring and summer of the previous academic year`() {
        val terms = listOf(
            term("2025-2026", "1"),
            term("2025-2026", "2"),
            term("2025-2026", "3"),
            term("2026-2027", "1", current = true),
            term("2026-2027", "2"),
        )

        val visible = WeihaiTermPolicy.filterVisible(terms, 2026, Calendar.OCTOBER)

        assertEquals(
            listOf("2025-2026-1", "2025-2026-2", "2025-2026-3", "2026-2027-1", "2026-2027-2"),
            visible.map { it.id },
        )
    }

    @Test
    fun `hides academic years after the current one`() {
        val terms = listOf(
            term("2026-2027", "1", current = true),
            term("2027-2028", "1"),
            term("2025-2026", "2"),
        )

        val visible = WeihaiTermPolicy.filterVisible(terms, 2026, Calendar.OCTOBER)

        assertEquals(listOf("2026-2027-1", "2025-2026-2"), visible.map { it.id })
    }

    @Test
    fun `stale current mark does not shrink the visible range`() {
        // 教务页面默认选中项可能停在旧学期，成绩页与课表页也可能各有一个标记
        val terms = listOf(
            term("2025-2026", "1", current = true),
            term("2026-2027", "1", current = true),
            term("2027-2028", "1"),
        )

        val visible = WeihaiTermPolicy.filterVisible(terms, 2026, Calendar.OCTOBER)

        assertEquals(listOf("2025-2026-1", "2026-2027-1"), visible.map { it.id })
    }

    @Test
    fun `falls back to the calendar academic year without any current mark`() {
        val terms = listOf(
            term("2025-2026", "2"),
            term("2026-2027", "1"),
            term("2027-2028", "1"),
        )

        // 2026 年 2 月仍属于 2025-2026 学年
        val visible = WeihaiTermPolicy.filterVisible(terms, 2026, Calendar.FEBRUARY)

        assertEquals(listOf("2025-2026-2"), visible.map { it.id })
    }

    @Test
    fun `keeps terms whose year code cannot be parsed`() {
        val terms = listOf(
            term("", "1"),
            term("2026-2027", "1", current = true),
        )

        val visible = WeihaiTermPolicy.filterVisible(terms, 2026, Calendar.OCTOBER)

        assertEquals(2, visible.size)
    }

    @Test
    fun `returns the original list when every term would be hidden`() {
        val terms = listOf(term("2028-2029", "1"), term("2029-2030", "1"))

        val visible = WeihaiTermPolicy.filterVisible(terms, 2026, Calendar.OCTOBER)

        assertEquals(2, visible.size)
    }

    @Test
    fun `parses padded year and term value`() {
        val parsed = WeihaiTermPolicy.parseTermValue("2025-20262", "2025-2026学年第二学期")

        assertEquals("2025-2026-2", parsed?.id)
        assertEquals("2025-2026 春季学期", parsed?.name)
    }

    @Test
    fun `parses dashed year and term value`() {
        val parsed = WeihaiTermPolicy.parseTermValue("2025-2026-3", "2025-2026学年夏季学期")

        assertEquals("2025-2026-3", parsed?.id)
    }

    @Test
    fun `parses compact ten digit value`() {
        val parsed = WeihaiTermPolicy.parseTermValue("202520262", "第二学期")

        assertEquals("2025-2026-2", parsed?.id)
    }

    @Test
    fun `falls back to the option label when the value has no term digit`() {
        val parsed = WeihaiTermPolicy.parseTermValue("2025-2026", "春季学期")

        assertEquals("2025-2026-2", parsed?.id)
    }

    @Test
    fun `synthesizes a term from the label when the option value is blank`() {
        assertEquals("2025-2026-2", WeihaiTermPolicy.parseTermValue("", "2025-2026学年春季学期")?.id)
        assertEquals("2025-2026-2", WeihaiTermPolicy.parseTermValue("", "2025学年第二学期")?.id)
    }

    @Test
    fun `returns null when the value carries no academic year`() {
        assertNull(WeihaiTermPolicy.parseTermValue("", "春季学期"))
        assertNull(WeihaiTermPolicy.parseTermValue("abc", "春季学期"))
    }
}
