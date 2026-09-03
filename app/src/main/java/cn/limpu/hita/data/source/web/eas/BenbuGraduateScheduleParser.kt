package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.TermItem
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Parser for the graduate gcourse JSON timetable returned by Aura's API. */
internal object BenbuGraduateScheduleParser {
    fun parseTerms(body: String): List<TermItem> {
        val result = linkedMapOf<String, TermItem>()
        collectTerms(runCatching { JsonParser().parse(body) }.getOrNull(), result)
        return result.values.sortedWith(compareByDescending<TermItem> { it.isCurrent }.thenByDescending { it.id })
    }

    private fun collectTerms(value: JsonElement?, result: MutableMap<String, TermItem>) {
        when {
            value == null || value.isJsonNull -> Unit
            value.isJsonArray -> value.asJsonArray.forEach { collectTerms(it, result) }
            value.isJsonObject -> {
                val objectValue = value.asJsonObject
                val strings = objectValue.entrySet().mapNotNull { it.value.asStringOrNull() }
                val code = strings.firstNotNullOfOrNull { Regex("(\\d{4}-\\d{4})(\\d+)").find(it) }
                if (code != null) {
                    val year = code.groupValues[1]
                    val termCode = code.groupValues[2]
                    val term = TermItem(year, year, termCode, termName(termCode)).apply {
                        name = strings.firstOrNull { it.contains("秋") || it.contains("春") || it.contains("夏") || it.contains("冬") }
                            ?: "$year${termName(termCode)}"
                        isCurrent = objectValue.entrySet().any { it.value.isCurrentFlag() } ||
                            strings.any { it.contains("当前") || it.contains("本学期") }
                    }
                    result[term.id] = term
                }
                objectValue.entrySet().forEach { collectTerms(it.value, result) }
            }
        }
    }

    fun parseTimetable(body: String): List<CourseItem> {
        val rows = runCatching { JsonParser().parse(body).takeIf { it.isJsonArray }?.asJsonArray }.getOrNull() ?: return emptyList()
        val result = mutableListOf<CourseItem>()
        val days = listOf("MON", "TUES", "WED", "THUR", "FRI", "SAT", "SUN")
        rows.forEach { rowElement ->
            if (!rowElement.isJsonObject) return@forEach
            val row = rowElement.asJsonObject
            val (begin, duration) = parsePeriod(row.stringValue("JCMC").ifBlank { row.stringValue("jcmc") }) ?: return@forEach
            days.forEachIndexed { dayIndex, key ->
                val html = row.stringValue(key).ifBlank { row.stringValue(key.lowercase()) }
                parseCell(html, dayIndex + 1, begin, duration, result)
            }
        }
        return result
    }

    private fun parseCell(html: String, dow: Int, begin: Int, duration: Int, result: MutableList<CourseItem>) {
        val text = html.replace(Regex("<\\s*br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ")
        val pattern = Regex("(?:^|[，,])\\s*([^\\[，,]+?)\\s*((?:\\[[^\\]]*周[^\\]]*\\]\\s*[，,]?\\s*)+)([^\\[]*?)\\s*\\[([^\\]]*)\\]([^，,]*)")
        text.split('\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            pattern.findAll(line).forEach { match ->
                val identityParts = match.groupValues[1].trim().split('◇', limit = 2)
                val course = CourseItem().apply {
                    name = identityParts.first().trim(); rawName = name
                    teacher = identityParts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                    classroom = match.groupValues[3].trim().trim('◇', '，', ',').takeIf { it.isNotBlank() }
                    notes = listOf(match.groupValues[4], match.groupValues[5]).map { it.trim() }.filter { it.isNotBlank() }.joinToString("；").ifBlank { null }
                    this.dow = dow; this.begin = begin; this.last = duration
                    weeks = Regex("\\[([^\\]]*周[^\\]]*)\\]").findAll(match.groupValues[2]).flatMap { parseWeeks(it.groupValues[1]).asSequence() }.distinct().sorted().toMutableList()
                }
                if (!course.name.isNullOrBlank() && course.weeks.isNotEmpty()) result += course
            }
        }
    }

    private fun parseWeeks(value: String): List<Int> = value.replace("周", "").split(',', '，', '、').flatMap { part ->
        val range = Regex("(\\d+)\\s*[-~至到]\\s*(\\d+)").find(part)
        if (range != null) (range.groupValues[1].toInt()..range.groupValues[2].toInt()).toList()
        else part.trim().toIntOrNull()?.let(::listOf).orEmpty()
    }

    private fun parsePeriod(value: String): Pair<Int, Int>? {
        val numbers = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
        if (numbers.isEmpty()) return null
        val begin = numbers.first()
        return begin to ((numbers.getOrNull(1) ?: begin) - begin + 1).coerceAtLeast(1)
    }

    private fun termName(code: String) = when (code.firstOrNull()) { '1' -> "秋季学期"; '2' -> "春季学期"; '3' -> "夏季学期"; else -> "冬季学期" }
    private fun JsonElement.asStringOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    private fun JsonObject.stringValue(key: String): String = get(key)?.asStringOrNull().orEmpty()
    private fun JsonElement.isCurrentFlag(): Boolean {
        if (!isJsonPrimitive) return false
        val primitive = asJsonPrimitive
        return (primitive.isBoolean && asBoolean) || (primitive.isNumber && asNumber.toString() == "1") ||
            (primitive.isString && asString.equals("true", ignoreCase = true)) || (primitive.isString && asString == "1")
    }
}
