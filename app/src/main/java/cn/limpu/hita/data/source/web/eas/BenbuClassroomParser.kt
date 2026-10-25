package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.classroom.ClassroomItem
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object BenbuClassroomParser {

    private const val SCHEDULE_CELL_COUNT = 42

    fun parseEmptyClassroomHtml(html: String): List<ClassroomItem> {
        val result = mutableListOf<ClassroomItem>()
        try {
            val doc = Jsoup.parse(html)
            val table = doc.select("table.dataTable").maxByOrNull { candidate ->
                candidate.select("tr").maxOfOrNull { row ->
                    row.select("td,th").size
                } ?: 0
            } ?: return result
            val rows = table.select("tr")

            if (rows.isEmpty()) return result

            for (row in rows) {
                val cells = row.select("td,th")
                if (cells.size < SCHEDULE_CELL_COUNT + 1) continue

                val fixedColumnCount = cells.size - SCHEDULE_CELL_COUNT
                if (fixedColumnCount < 1) continue

                val roomName = cells[0].text().trim().replace("\u00A0", "")
                if (roomName.isBlank()) continue

                val metaCells = if (fixedColumnCount > 1) cells.subList(1, fixedColumnCount) else emptyList()
                val capacity = metaCells
                    .asSequence()
                    .map { it.text().trim().replace("\u00A0", "") }
                    .mapNotNull { text -> text.filter(Char::isDigit).toIntOrNull() }
                    .firstOrNull() ?: 0

                val classroom = ClassroomItem().apply {
                    name = roomName
                    id = roomName
                    this.capacity = capacity
                    specialClassroom = ""
                }

                for (scheduleIndex in 0 until SCHEDULE_CELL_COUNT) {
                    val cell = cells[fixedColumnCount + scheduleIndex]
                    val dow = scheduleIndex / 6 + 1
                    val timeBlock = scheduleIndex % 6 + 1
                    val occupied = isOccupiedCell(cell)

                    for (period in getBenbuPeriodsForTimeBlock(timeBlock)) {
                        val scheduleJson = JSONObject()
                        scheduleJson.put("XQJ", dow)
                        scheduleJson.put("XJ", period)
                        scheduleJson.put("PKBJ", if (occupied) "占用" else "")
                        classroom.scheduleList.add(scheduleJson)
                    }
                }

                result.add(classroom)
            }
        } catch (e: Exception) {
            // Silent fail
        }
        return result
    }

    private fun isOccupiedCell(cell: Element): Boolean {
        if (cell.select("div.kjs_icon.kjs_icon01").isNotEmpty()) return true
        return cell.select(".kjs_icon01").any { it.classNames().contains("kjs_icon01") }
    }

    private fun getBenbuPeriodsForTimeBlock(block: Int): List<Int> {
        return when (block) {
            1 -> listOf(1, 2)
            2 -> listOf(3, 4)
            3 -> listOf(5, 6)
            4 -> listOf(7, 8)
            5 -> listOf(9, 10)
            6 -> listOf(11, 12)
            else -> emptyList()
        }
    }
}
