package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.data.model.eas.CourseScoreItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Comment
import org.jsoup.nodes.TextNode

internal fun parseScoreCredit(raw: String): Float {
    return raw.trim().toFloatOrNull() ?: 0f
}

object BenbuScoreParser {

    internal fun isScoreResultPage(doc: Document): Boolean {
        if (doc.select("table tr").any(::isScoreHeader)) return true
        val text = doc.body().text().trim().trimEnd('。', '！', '!')
        return text in setOf("暂无成绩", "未查询到成绩", "没有查询到成绩", "没有成绩记录")
    }

    private fun isScoreHeader(row: Element): Boolean {
        val headers = row.select("th, td").map { normalizeHeader(it.text()) }
        return headers.any { it.contains("课程名称") } && headers.any { it.contains("成绩") }
    }

    fun parseGradesHtml(html: String): List<CourseScoreItem> {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table").toList()
        if (tables.isEmpty()) {
            LogUtils.d("parseGradesHtml: no table in page, title=${doc.title().trim()}")
            return emptyList()
        }

        var bestResult: List<CourseScoreItem> = emptyList()
        var bestHeader: List<String> = emptyList()
        var bestRowCount = 0

        tables.forEachIndexed { index, table ->
            val rows = table.select("tr")
            if (rows.size < 2) {
                LogUtils.d("parseGradesHtml: table#$index rows=${rows.size} skipped(<2)")
                return@forEachIndexed
            }

            val row0 = rows.getOrNull(0)?.select("th, td")?.map { normalizeHeader(it.text()) }.orEmpty()
            val row1 = rows.getOrNull(1)?.select("th, td")?.map { normalizeHeader(it.text()) }.orEmpty()
            val headerIndex = rows.indexOfFirst(::isScoreHeader)
            LogUtils.d("parseGradesHtml: table#$index rows=${rows.size} row0=$row0 row1=$row1 headerIndex=$headerIndex")
            if (headerIndex < 0 || headerIndex >= rows.lastIndex) return@forEachIndexed

            val headers = rows[headerIndex].select("th, td").map { normalizeHeader(it.text()) }
            val tableResult = mutableListOf<CourseScoreItem>()
            for (rowIndex in (headerIndex + 1) until rows.size) {
                val cells = rows[rowIndex].select("td")
                if (cells.isEmpty()) continue

                val values = linkedMapOf<String, String>()
                val bound = minOf(headers.size, cells.size)
                for (cellIndex in 0 until bound) {
                    values[headers[cellIndex]] = cells[cellIndex].text().trim()
                }

                val courseName = getValue(values, "课程名称")
                if (courseName.isBlank()) continue

                val item = CourseScoreItem().apply {
                    termName = getValue(values, "学年学期").ifBlank { null }
                    courseCode = getValue(values, "课程代码", "课程号").ifBlank { null }
                    this.courseName = courseName
                    courseProperty = getValue(values, "课程性质").ifBlank { null }
                    courseCategory = getValue(values, "课程类别").ifBlank { null }
                    schoolName = getValue(values, "开课院系", "开课学院").ifBlank { null }
                    assessMethod = getValue(values, "是否考试课", "考核方式").ifBlank { null }
                    credits = parseScoreCredit(getValue(values, "学分"))
                    finalScoresText = getValue(values, "最终成绩", "成绩", "总成绩").ifBlank { null }
                    finalScores = parseGradeToInt(finalScoresText.orEmpty())
                    val scoreIndex = listOf("最终成绩", "成绩", "总成绩")
                        .map { headers.indexOf(normalizeHeader(it)) }
                        .firstOrNull { it >= 0 }
                    numericScore = parseNumericScore(
                        scoreIndex?.let { cells.getOrNull(it) }
                    )
                    hours = getValue(values, "学时").toIntOrNull() ?: 0
                }
                tableResult.add(item)
            }

            LogUtils.d("parseGradesHtml: table#$index rows=${rows.size} parsed=${tableResult.size} headers=$headers")
            if (tableResult.size > bestResult.size) {
                bestResult = tableResult
                bestHeader = headers
                bestRowCount = rows.size
            }
        }

        if (bestResult.isEmpty()) {
            LogUtils.d("parseGradesHtml: parsed empty, title=${doc.title().trim()} tableCount=${tables.size}")
        } else {
            LogUtils.d("parseGradesHtml: picked table rows=$bestRowCount parsed=${bestResult.size} headers=$bestHeader")
        }
        return bestResult
    }


    private fun findScoreTable(tables: List<Element>): Element? {
        val scored = tables.map { table ->
            val rows = table.select("tr")
            val headerTexts = rows.flatMap { row ->
                row.select("th, td").map { normalizeHeader(it.text()) }
            }
            val hasCourseHeader = headerTexts.any { it.contains("课程名称") }
            val hasScoreHeader = headerTexts.any {
                it.contains("成绩") || it.contains("最终成绩") || it.contains("总成绩")
            }
            val dataRows = rows.drop(1).count { row ->
                val tds = row.select("td")
                tds.size >= 5 && tds.any { it.text().trim().isNotEmpty() }
            }
            val rowCount = rows.size
            val score = (if (hasCourseHeader) 100 else 0) +
                (if (hasScoreHeader) 100 else 0) +
                dataRows * 10 +
                rowCount
            Triple(table, score, dataRows)
        }

        val best = scored
            .sortedWith(
                compareByDescending<Triple<Element, Int, Int>> { it.second }
                    .thenByDescending { it.third }
            )
            .firstOrNull { candidate ->
                val rows = candidate.first.select("tr")
                val headerTexts = rows.flatMap { row ->
                    row.select("th, td").map { normalizeHeader(it.text()) }
                }
                headerTexts.any { it.contains("课程名称") } &&
                    headerTexts.any {
                        it.contains("成绩") || it.contains("最终成绩") || it.contains("总成绩")
                    }
            }

        return best?.first
    }


    private fun getValue(values: Map<String, String>, vararg keys: String): String {
        keys.forEach { key ->
            val normalizedKey = normalizeHeader(key)
            values[normalizedKey]?.let { return it.trim() }
        }
        return ""
    }

    private fun normalizeHeader(raw: String): String {
        return raw.replace(Regex("\\s+"), "")
            .replace("：", "")
            .replace(":", "")
            .trim()
    }

    private fun parseNumericScore(cell: Element?): Double? {
        if (cell == null) return null

        // 跳过成绩单元格前的空白，找到紧邻的节点。
        var previous = cell.previousSibling()
        while (previous is TextNode && previous.text().isBlank()) {
            previous = previous.previousSibling()
        }

        // 本部教务将原始数字成绩放在最终成绩单元格前的注释中。
        val commentScore = (previous as? Comment)?.let { comment ->
            val doc = Jsoup.parse("<table><tr>${comment.data}</tr></table>")
            val cells = doc.select("table > tbody > tr > td")
            if (cells.size == 1) {
                cells.first()?.text()?.trim()?.toDoubleOrNull()
            } else {
                null
            }
        }?.takeIf { it.isFinite() && it in 0.0..100.0 }

        return commentScore
            ?: cell.text().trim().toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    private fun parseGradeToInt(raw: String): Int {
        val value = raw.trim()
        if (value.isEmpty()) return -1

        value.toDoubleOrNull()?.let { return it.toInt() }

        return when (value.uppercase()) {
            "A+" -> 95
            "A" -> 90
            "A-" -> 85
            "B+" -> 85
            "B" -> 80
            "B-" -> 75
            "C+" -> 75
            "C" -> 70
            "C-" -> 65
            "D+" -> 65
            "D" -> 60
            "D-" -> 55
            "F" -> 0
            "合格", "通过" -> 60
            "不合格", "未通过" -> 0
            else -> -1
        }
    }
}
