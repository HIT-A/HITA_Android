package cn.limpu.hita.data.source.web.eas

import org.jsoup.Jsoup
import java.util.Calendar

/**
 * 老教务（本部 ivpn / 威海 webvpn）校历页解析：从「校历」表格中提取第 1 教学周的周一日期（= 开学日）。
 *
 * 移植自 iOS 版 HITA-Aura 的 `AcademicWebCalendarParser.parseTermStartDate`
 * （Packages/HITAAcademicKernel/Sources/HITAAcademicConnectors/AcademicWebConnectors.swift）。
 *
 * 解析策略：
 * 1. 遍历所有 `table`，找到表头行同时含「周次」与「星期一/周一/monday」的表；
 * 2. 定位「第 1 周」所在数据行；
 * 3. 取该行「星期一」列的日期作为开学日；
 * 4. 日期格式容错：`YYYY年M月D日`、`YYYY-M-D`、`M月D日`、纯日；
 * 5. 首行跨月时，在 `context / 上月 / 下月` 候选中择“真正为周一”的日期。
 *
 * 解析失败返回 null，由调用方回退到推算逻辑。
 */
object WebCalendarParser {

    private data class YearMonth(val year: Int, val month: Int)

    private val YEAR_MONTH_PATTERNS = listOf(
        Regex("""((?:19|20)\d{2})\s*年\s*(\d{1,2})\s*月"""),
        Regex("""((?:19|20)\d{2})\s*[-/.]\s*(\d{1,2})"""),
    )

    private val FULL_DATE_PATTERNS = listOf(
        Regex("""((?:19|20)\d{2})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日?"""),
        Regex("""((?:19|20)\d{2})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})"""),
    )

    private val MONTH_DAY_PATTERN = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日?""")

    private val DAY_PATTERN = Regex("""(?<!\d)(?:[1-9]|[12]\d|3[01])(?!\d)""")

    private val MONDAY_HEADER_LABELS = setOf("星期一", "周一", "monday")

    /** 解析校历页 HTML，返回第 1 教学周周一（开学日）；无法解析时返回 null。 */
    fun parseTermStartDate(html: String): Calendar? {
        val document = Jsoup.parse(html)
        for (table in document.select("table")) {
            val rows = table.select("tr")
            val headerIndex = rows.indexOfFirst { row ->
                val labels = row.select("th, td").map { normalized(it.text()) }
                labels.contains("周次") && labels.any { it in MONDAY_HEADER_LABELS }
            }
            if (headerIndex < 0) continue

            val headers = rows[headerIndex].select("th, td").map { normalized(it.text()) }
            val weekIndex = headers.indexOf("周次")
            val mondayIndex = headers.indexOfFirst { it in MONDAY_HEADER_LABELS }
            if (weekIndex < 0 || mondayIndex < 0) continue

            val yearMonth = yearMonth(table) ?: continue

            val dataRows = rows.drop(headerIndex + 1)
            dataRows.forEachIndexed { rowIndex, row ->
                val cells = row.select("td, th")
                if (weekIndex >= cells.size || mondayIndex >= cells.size) return@forEachIndexed
                if (!isWeekOne(cells[weekIndex].text())) return@forEachIndexed

                val weekdayCells = cells.drop(mondayIndex).take(7)
                val date = parseDate(
                    cells[mondayIndex].text(),
                    yearMonth,
                    weekdayCells,
                    isFirstDataRow = rowIndex == 0,
                )
                if (date != null) return date
            }
        }
        return null
    }

    private fun yearMonth(table: org.jsoup.nodes.Element): YearMonth? {
        val candidates = mutableListOf<String>()
        table.selectFirst("caption")?.let { candidates.add(it.text()) }
        for (attribute in listOf("data-year-month", "data-month", "title")) {
            val value = table.attr(attribute)
            if (value.isNotEmpty()) candidates.add(value)
        }
        var sibling = table.previousElementSibling()
        var hops = 0
        while (sibling != null && hops < 4) {
            candidates.add(sibling.text())
            sibling = sibling.previousElementSibling()
            hops++
        }
        if (candidates.isEmpty()) {
            table.parent()?.let { candidates.add(it.text()) }
        }
        return candidates.firstNotNullOfOrNull { parseYearMonth(it) }
    }

    private fun parseYearMonth(text: String): YearMonth? {
        for (pattern in YEAR_MONTH_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val year = match.groupValues[1].toIntOrNull() ?: continue
            val month = match.groupValues[2].toIntOrNull() ?: continue
            if (month in 1..12) return YearMonth(year, month)
        }
        return null
    }

    private fun parseDate(
        text: String,
        context: YearMonth,
        weekdayCells: List<org.jsoup.nodes.Element>,
        isFirstDataRow: Boolean,
    ): Calendar? {
        for (pattern in FULL_DATE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val year = match.groupValues[1].toIntOrNull() ?: continue
            val month = match.groupValues[2].toIntOrNull() ?: continue
            val day = match.groupValues[3].toIntOrNull() ?: continue
            return makeDate(year, month, day)
        }

        MONTH_DAY_PATTERN.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            if (month != null && day != null) {
                return makeDate(context.year, month, day)
            }
        }

        val dayText = DAY_PATTERN.find(text)?.value ?: return null
        val day = dayText.toIntOrNull() ?: return null

        val days = weekdayCells.mapNotNull { cell ->
            DAY_PATTERN.find(cell.text())?.value?.toIntOrNull()
        }
        val crossesMonth = days.zipWithNext().any { (a, b) -> b < a }
        val contextualDate = makeDate(context.year, context.month, day)
        if (!isFirstDataRow || !crossesMonth) return contextualDate

        // 首行跨月时，月份标签可能描述周一两侧的任一月；此单元格位于「星期一」表头下，
        // 优先选择相邻月份中“确实是周一”的那个日期。
        for (candidate in listOf(context, previousMonth(context), nextMonth(context))) {
            val date = makeDate(candidate.year, candidate.month, day)
            if (date != null && isMonday(date)) return date
        }
        return contextualDate
    }

    private fun previousMonth(value: YearMonth): YearMonth =
        if (value.month == 1) YearMonth(value.year - 1, 12) else YearMonth(value.year, value.month - 1)

    private fun nextMonth(value: YearMonth): YearMonth =
        if (value.month == 12) YearMonth(value.year + 1, 1) else YearMonth(value.year, value.month + 1)

    private fun isMonday(date: Calendar): Boolean = date.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY

    private fun makeDate(year: Int, month: Int, day: Int): Calendar? {
        if (month !in 1..12 || day !in 1..31) return null
        return Calendar.Builder()
            .setDate(year, month - 1, day)
            .setTimeOfDay(0, 0, 0, 0)
            .build()
    }

    private fun normalized(text: String): String = text.replace(Regex("""\s+"""), "")

    private fun isWeekOne(text: String): Boolean =
        normalized(text) in setOf("1", "1周", "第1周")
}
