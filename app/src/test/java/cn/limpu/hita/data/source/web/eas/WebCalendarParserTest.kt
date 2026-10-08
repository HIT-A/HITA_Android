package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class WebCalendarParserTest {

    private fun cal(year: Int, month: Int, day: Int): Calendar = Calendar.Builder()
        .setDate(year, month - 1, day)
        .setTimeOfDay(0, 0, 0, 0)
        .build()

    private fun assertSameDate(expected: Calendar, actual: Calendar?) {
        assertEquals(expected.timeInMillis, actual?.timeInMillis)
    }

    /** 表头含「周次」+「星期一」，第 1 周行写完整年月日。 */
    @Test
    fun parseTermStartDate_fullDateInWeekOneRow() {
        val html = """
            <table>
                <caption>2026年8月</caption>
                <tr><th>周次</th><th>星期一</th><th>星期二</th></tr>
                <tr><td>第1周</td><td>2026年8月31日</td><td>2026年9月1日</td></tr>
                <tr><td>第2周</td><td>2026年9月7日</td><td>2026年9月8日</td></tr>
            </table>
        """.trimIndent()

        assertSameDate(cal(2026, 8, 31), WebCalendarParser.parseTermStartDate(html))
    }

    /** 单元格只有「M月D日」，年份由表标题提供。 */
    @Test
    fun parseTermStartDate_monthDayWithCaptionYear() {
        val html = """
            <table>
                <caption>2026年9月</caption>
                <tr><th>周次</th><th>星期一</th></tr>
                <tr><td>第1周</td><td>9月14日</td></tr>
            </table>
        """.trimIndent()

        assertSameDate(cal(2026, 9, 14), WebCalendarParser.parseTermStartDate(html))
    }

    /** 单元格只有纯日数字，上下文月份由表标题提供。 */
    @Test
    fun parseTermStartDate_pureDayWithContextMonth() {
        val html = """
            <table>
                <caption>2026年9月</caption>
                <tr><th>周次</th><th>星期一</th><th>星期二</th></tr>
                <tr><td>第1周</td><td>7</td><td>8</td></tr>
            </table>
        """.trimIndent()

        assertSameDate(cal(2026, 9, 7), WebCalendarParser.parseTermStartDate(html))
    }

    /** 表头用「周一」别名。 */
    @Test
    fun parseTermStartDate_mondayAliasHeader() {
        val html = """
            <table>
                <caption>2026年8月</caption>
                <tr><th>周次</th><th>周一</th></tr>
                <tr><td>1</td><td>2026-08-31</td></tr>
            </table>
        """.trimIndent()

        assertSameDate(cal(2026, 8, 31), WebCalendarParser.parseTermStartDate(html))
    }

    /** 无「周次」表头时返回 null。 */
    @Test
    fun parseTermStartDate_returnsNullWhenHeaderMissing() {
        val html = """
            <table>
                <tr><th>星期</th><th>课程</th></tr>
                <tr><td>星期一</td><td>高数</td></tr>
            </table>
        """.trimIndent()

        assertNull(WebCalendarParser.parseTermStartDate(html))
    }

    /** 无第 1 周数据行时返回 null。 */
    @Test
    fun parseTermStartDate_returnsNullWithoutWeekOneRow() {
        val html = """
            <table>
                <caption>2026年9月</caption>
                <tr><th>周次</th><th>星期一</th></tr>
                <tr><td>第2周</td><td>2026年9月7日</td></tr>
            </table>
        """.trimIndent()

        assertNull(WebCalendarParser.parseTermStartDate(html))
    }
}
