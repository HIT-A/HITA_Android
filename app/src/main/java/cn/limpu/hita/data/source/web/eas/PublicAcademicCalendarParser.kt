package cn.limpu.hita.data.source.web.eas

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.YearMonth

/** Public HIT calendar dates, independent of the last class in a personal timetable. */
@androidx.annotation.Keep
data class AcademicCalendarPeriod(val startDay: Long, val endDay: Long, val vacation: Boolean, val campus: String = "BENBU") {
    fun contains(day: Long) = day in startDay..endDay
    fun valid() = campus in setOf("BENBU", "SHENZHEN", "WEIHAI") && startDay > 0 && endDay >= startDay && endDay - startDay < 370
}

object PublicAcademicCalendarParser {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val chineseMonths = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")

    fun parse(html: String): List<AcademicCalendarPeriod> {
        val result = mutableListOf<AcademicCalendarPeriod>()
        val document = Jsoup.parse(html)
        for (table in document.select("table")) {
            val grid = expand(table) ?: continue
            val header = grid.indexOfFirst { row -> row.any { it.equals("Mon.", true) || it == "星期一" } }
            if (header < 0) continue
            val monday = grid[header].indexOfFirst { it.equals("Mon.", true) || it == "星期一" }
            if (monday != 2) continue // This source has separate week and month columns.
            val teaching = sortedSetOf<LocalDate>()
            val vacation = sortedSetOf<LocalDate>()
            for (row in grid.drop(header + 1)) {
                if (row.size < 9) continue
                val month = month(row[1]) ?: continue
                val label = row[0].lowercase()
                val isVacation = label.contains("winter vacation") || label.contains("summer vacation") || label.contains("寒假") || label.contains("暑假")
                val week = Regex("^(?:第)?(\\d{1,2})(?:周)?$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                if (!isVacation && week !in 1..30) continue
                for (offset in 0..6) {
                    val cell = row[monday + offset]
                    val day = Regex("^\\s*(\\d{1,2})(?!\\d)").find(cell)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    val date = runCatching { month.atDay(day) }.getOrNull() ?: continue
                    if (date.dayOfWeek.value != offset + 1) continue
                    if (isVacation) vacation.add(date) else teaching.add(date)
                }
            }
            // Only a table with an explicit vacation boundary establishes a term/holiday split.
            // Summer semester + summer vacation tables without that boundary are ambiguous.
            if (teaching.isEmpty()) continue
            if (vacation.isEmpty()) {
                // Spring calendars can end at the semester boundary without including the holiday table.
                if (document.title().contains("Spring Semester", true)) {
                    val term = AcademicCalendarPeriod(teaching.first().toEpochDay(), teaching.last().toEpochDay(), false)
                    if (term.valid()) result.add(term)
                }
                continue
            }
            val firstVacation = vacation.first()
            if (firstVacation <= teaching.last() || firstVacation.toEpochDay() - teaching.last().toEpochDay() != 1L) continue
            val periods = listOf(
                AcademicCalendarPeriod(teaching.first().toEpochDay(), teaching.last().toEpochDay(), false),
                AcademicCalendarPeriod(firstVacation.toEpochDay(), vacation.last().toEpochDay(), true),
            )
            if (periods.all { it.valid() }) result.addAll(periods)
        }
        return result.distinct()
    }

    fun parseWeihaiText(text: String): List<AcademicCalendarPeriod> {
        val compact = text.replace(Regex("\\s+"), "")
        val date = "((?:19|20)\\d{2})年(\\d{1,2})月(\\d{1,2})日"
        fun read(m: MatchResult, offset: Int) = runCatching {
            LocalDate.of(m.groupValues[offset].toInt(), m.groupValues[offset + 1].toInt(), m.groupValues[offset + 2].toInt()).toEpochDay()
        }.getOrNull()
        val vacation = Regex("$date[—–至-]+$date(寒假|暑假)").findAll(compact).mapNotNull { m ->
            val start = read(m, 1) ?: return@mapNotNull null
            val end = read(m, 4) ?: return@mapNotNull null
            AcademicCalendarPeriod(start, end, true, "WEIHAI").takeIf { it.valid() }
        }.toList()
        val starts = Regex("$date(?:秋季|春季|夏季)学期开始").findAll(compact).mapNotNull { read(it, 1) }.toList()
        return vacation + starts.mapNotNull { start ->
            val end = vacation.filter { it.startDay > start && it.startDay - start < 220 }.minOfOrNull { it.startDay - 1 } ?: return@mapNotNull null
            AcademicCalendarPeriod(start, end, false, "WEIHAI").takeIf { it.valid() }
        }
    }

    fun parseShenzhen(html: String): List<AcademicCalendarPeriod> {
        val doc = Jsoup.parse(html)
        val year = Regex("((?:19|20)\\d{2})[/—–-]((?:19|20)\\d{2})\\s*学年(秋季|春季|夏季)学期")
            .find(doc.text()) ?: return emptyList()
        val y = year.groupValues[if (year.groupValues[3] == "秋季") 1 else 2].toInt()
        for (table in doc.select("table")) {
            val grid = expand(table) ?: continue
            val first = grid.firstOrNull { it.getOrNull(1) == "第1周" } ?: continue
            val monthNumber = chineseMonths.indexOf(first[0].removeSuffix("月")) + 1
            if (monthNumber !in 1..12) continue
            val day = first[2].toIntOrNull() ?: continue
            val context = YearMonth.of(y, monthNumber)
            val start = listOf(context, context.minusMonths(1)).mapNotNull { runCatching { it.atDay(day) }.getOrNull() }
                .firstOrNull { candidate -> candidate.dayOfWeek.value == 1 && (0..6).all { first[it + 2].toIntOrNull() == candidate.plusDays(it.toLong()).dayOfMonth } } ?: continue
            val weeks = grid.mapNotNull { row ->
                val w = Regex("第(\\d{1,2})周").matchEntire(row[1])?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                if (w !in 1..30 || !(0..6).all { row[it + 2].toIntOrNull() == start.plusDays((w - 1) * 7L + it).dayOfMonth }) return emptyList()
                w
            }
            val count = weeks.maxOrNull() ?: continue
            if (weeks.distinct().sorted() != (1..count).toList()) continue
            return listOf(AcademicCalendarPeriod(start.toEpochDay(), start.plusWeeks(count.toLong()).minusDays(1).toEpochDay(), false, "SHENZHEN"))
        }
        return emptyList()
    }

    private fun month(text: String): YearMonth? {
        val year = Regex("(?:19|20)\\d{2}").find(text)?.value?.toIntOrNull() ?: return null
        val english = months.indexOfFirst { text.lowercase().contains(it) }.takeIf { it >= 0 }?.plus(1)
        val numeric = Regex("(\\d{1,2})\\s*月").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val chinese = Regex("年\\s*([一二三四五六七八九十]+)月").find(text)?.groupValues?.get(1)
            ?.let { chineseMonths.indexOf(it).takeIf { n -> n >= 0 }?.plus(1) }
        return runCatching { YearMonth.of(year, english ?: numeric ?: chinese ?: return null) }.getOrNull()
    }

    /** Expand month/week rowspans before reading weekday columns, including split-month weeks. */
    private fun expand(table: Element): List<List<String>>? {
        val rows = table.select("tr")
        if (rows.size > 150) return null
        val cells = mutableMapOf<Pair<Int, Int>, String>()
        rows.forEachIndexed { r, row ->
            var col = 0
            for (cell in row.children().filter { it.tagName() == "td" || it.tagName() == "th" }) {
                while (cells.containsKey(r to col)) col++
                val rs = cell.attr("rowspan").toIntOrNull() ?: 1
                val cs = cell.attr("colspan").toIntOrNull() ?: 1
                if (rs !in 1..100 || cs !in 1..10 || col + cs > 10) return null
                for (dr in 0 until rs) for (dc in 0 until cs) {
                    cells[(r + dr) to (col + dc)] = cell.text().replace('\u00a0', ' ').trim()
                }
                col += cs
            }
        }
        return rows.indices.map { r -> (0..8).map { c -> cells[r to c].orEmpty() } }
    }
}
