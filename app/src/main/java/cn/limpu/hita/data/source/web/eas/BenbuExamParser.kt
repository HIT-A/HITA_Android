package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/** 本部考试页面适配；不根据周数推算或补造尚未公布的考试时间。 */
internal object BenbuExamParser {
    private data class Columns(
        val course: Int,
        val time: Int,
        val location: Int? = null,
        val date: Int? = null,
        val type: Int? = null,
    )

    fun parse(html: String, requestedType: String, term: TermItem): List<ExamItem> {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table").map { table ->
            table to table.select("tr").filter { it.closest("table") == table }
        }
        val withHeader = tables.firstNotNullOfOrNull { (table, rows) ->
            rows.withIndex().firstNotNullOfOrNull { (index, row) ->
                columns(cells(row))?.let { Triple(table, index, it) }
            }
        }
        val table: Element
        val headerIndex: Int
        val layout: Columns
        if (withHeader != null) {
            table = withHeader.first
            headerIndex = withHeader.second
            layout = withHeader.third
        } else {
            // 兼容无表头的旧页面；六列时考试类型由请求参数补足。
            table = tables.firstOrNull { (candidate, rows) ->
                candidate.hasClass("bot_line") && rows.any {
                    val values = cells(it)
                    values.size >= 6 && values[0].text().trim().toIntOrNull() != null
                }
            }?.first ?: run {
                require(isEmptyMessage(doc.text())) { "考试页面格式无法识别，请稍后重试" }
                return emptyList()
            }
            headerIndex = -1
            layout = Columns(course = 1, time = 5, location = 3, type = 6)
        }
        val rows = tables.first { it.first == table }.second.drop(headerIndex + 1)
        return rows.mapNotNull { row ->
            val values = cells(row)
            if (values.isEmpty() || row.text().isBlank() || isEmptyMessage(row.text())) return@mapNotNull null
            if (columns(values) != null || (values.size == 1 && values[0].hasAttr("colspan"))) return@mapNotNull null
            if (headerIndex < 0 && values[0].text().trim().toIntOrNull() == null) return@mapNotNull null
            require(values.size > maxOf(layout.course, layout.time)) { "考试记录字段不完整，请稍后重试" }
            val courseName = values[layout.course].text().trim()
            if (courseName.isBlank()) return@mapNotNull null
            val rawTime = listOfNotNull(
                layout.date?.let { values.getOrNull(it)?.text() },
                values[layout.time].text(),
            ).joinToString(" ")
            val (date, time) = parseDateTime(rawTime)
            val rowType = layout.type?.let { values.getOrNull(it)?.text()?.trim() }.orEmpty()
            ExamItem().apply {
                this.courseName = courseName
                examLocation = layout.location?.let { values.getOrNull(it)?.text()?.trim() }
                examDate = date
                examTime = time
                examType = rowType.takeIf { value ->
                    listOf("期中", "期末", "补考").any(value::contains)
                } ?: requestedType
                campusName = "本部"
                termId = term.id
                termName = term.name
            }
        }
    }

    private fun cells(row: Element): List<Element> =
        row.children().filter { it.tagName() == "td" || it.tagName() == "th" }

    private fun columns(cells: List<Element>): Columns? {
        val labels = cells.map { it.text().replace(Regex("[\\s\u00a0\u3000]+"), "") }
        val course = labels.indexOfFirst { it in listOf("课程名称", "课程名", "考试科目") }
        val time = labels.indexOfFirst {
            it.contains("考试具体时间") || it.contains("考试日期时间") ||
                (it.contains("考试时间") && !it.contains("时间段"))
        }
        val date = labels.indexOfFirst { it.contains("考试日期") && !it.contains("时间") }
        if (course < 0 || (time < 0 && date < 0)) return null
        return Columns(
            course = course,
            time = if (time >= 0) time else date,
            date = date.takeIf { it >= 0 && it != time && time >= 0 },
            location = labels.indexOfFirst {
                it.contains("考试地点") || it.contains("考试教室") || it == "考场"
            }.takeIf { it >= 0 },
            type = labels.indexOfFirst { it in listOf("考试时间段", "考试类型", "考试类别") }
                .takeIf { it >= 0 },
        )
    }

    private fun isEmptyMessage(text: String): Boolean {
        val compact = text.replace(Regex("[\\s\u00a0\u3000]+"), "")
        return listOf("暂无数据", "无数据", "暂无考试", "无考试安排", "未查询到", "没有查询到", "没有相关数据", "共0条")
            .any(compact::contains)
    }

    private fun parseDateTime(raw: String): Pair<String, String> {
        val text = raw.replace('：', ':').replace('\u00a0', ' ').replace('\u3000', ' ')
        val dateMatch = Regex("""(?<!\d)(\d{4})\s*(?:年|[-/.])\s*(\d{1,2})\s*(?:月|[-/.])\s*(\d{1,2})日?""").find(text)
        val date = dateMatch?.let {
            runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()).toString() }
                .getOrNull()
        }.orEmpty()
        val timeMatch = Regex("""(?<!\d)(\d{1,2})\s*:\s*(\d{2})\s*(?:-|－|—|–|~|～|至|到)\s*(\d{1,2})\s*:\s*(\d{2})(?!\d)""").find(text)
        val time = timeMatch?.let {
            runCatching {
                val from = LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt())
                val to = LocalTime.of(it.groupValues[3].toInt(), it.groupValues[4].toInt())
                "$from-$to"
            }.getOrNull()
        }.orEmpty()
        return date to time
    }
}
