package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.eas.classroom.BuildingItem
import cn.limpu.hita.ui.eas.classroom.ClassroomItem
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Pure parsers for the read-only Shenzhen Web EAS endpoints. */
internal object ShenzhenWebAcademicParser {
    fun parseStartDate(body: String): LocalDate? = rows(body)
        .mapNotNull {
            first(
                it,
                "RQ", "rq",
                "DQRQ", "dqrq",
                "RQ1", "rq1",
                "KSRQ", "ksrq"
            ).toLocalDateOrNull()
        }
        .minOrNull()

    fun parseScheduleStructure(body: String): MutableList<TimePeriodInDay>? {
        val result = rows(body).mapNotNull { row ->
            val order = first(row, "XJ", "xj", "KS", "ks", "DJ", "dj").toIntOrNull()
                ?: return@mapNotNull null
            val start = parseTime(first(row, "KSSJ", "kssj")) ?: return@mapNotNull null
            val end = parseTime(first(row, "JSSJ", "jssj")) ?: return@mapNotNull null
            order to TimePeriodInDay(start, end)
        }.sortedBy { it.first }.map { it.second }.toMutableList()
        return result.takeIf { it.isNotEmpty() }
    }

    fun parseSelectedSubjects(body: String): MutableList<TermSubject>? {
        val root = parse(body) ?: return null
        val selected = when {
            root.isJsonObject && root.asJsonObject.get("yxkcList")?.isJsonArray == true ->
                root.asJsonObject.getAsJsonArray("yxkcList")
            root.isJsonObject && root.asJsonObject.get("yxkcList")?.isJsonObject == true ->
                root.asJsonObject.getAsJsonObject("yxkcList").getAsJsonArray("list") ?: JsonArray()
            else -> rowsElement(root)
        }
        return buildList<TermSubject> {
            selected.forEach { element ->
                val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val code = first(row, "kcdm", "KCDM")
                val name = first(row, "kcmc", "KCMC")
                if (code.isBlank() && name.isBlank()) return@forEach
                add(TermSubject().apply {
                    this.code = code.ifBlank { null }
                    this.name = name
                    this.school = first(row, "kkyxmc", "KKYXMC").ifBlank { null }
                    this.teacher = first(row, "dgjsmc", "DGJSMC", "jsmc", "JSMC").ifBlank { null }
                    this.credit = first(row, "xf", "XF").toFloatOrNull() ?: 0f
                    this.key = first(row, "rwh", "RWH", "id", "ID").ifBlank { null }
                    this.field = first(row, "kclbmc", "KCLBMC").ifBlank { null }
                    this.selectCategory = first(
                        row, "rwlxmc", "RWLXMC", "xkfsmc", "XKFSMC", "xklbmc", "XKLBMC"
                    ).ifBlank { null }
                    this.nature = first(row, "kcxzmc", "KCXZMC").ifBlank { null }
                    this.type = when {
                        first(row, "rwlxmc", "RWLXMC").contains("MOOC", true) -> TermSubject.TYPE.MOOC
                        this.nature == "必修" -> TermSubject.TYPE.COM_A
                        this.nature == "限选" -> TermSubject.TYPE.OPT_A
                        else -> TermSubject.TYPE.OPT_B
                    }
                })
            }
        }.toMutableList()
    }

    fun parseTimetable(body: String, subjects: List<TermSubject>): List<CourseItem>? {
        val parsed = parse(body) ?: return null
        val metadata = subjects.flatMap { subject ->
            listOfNotNull(
                subject.key?.takeIf { it.isNotBlank() }?.let { it to subject },
                subject.code?.takeIf { it.isNotBlank() }?.let { it to subject }
            )
        }.toMap()

        return buildList<CourseItem> {
            rowsElement(parsed).forEach { element ->
                val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val begin = first(row, "KSJC", "ksjc").toIntOrNull() ?: return@forEach
                val end = first(row, "JSJC", "jsjc").toIntOrNull() ?: return@forEach
                val weekday = timetableWeekday(row) ?: return@forEach
                val weeks = parseWeeks(first(row, "ZC", "zc"))
                if (begin <= 0 || end < begin || weeks.isEmpty()) return@forEach

                val task = first(row, "RWH", "rwh")
                val detailLines = scheduleLines(first(row, "SKSJ", "sksj"))
                val meta = metadata[task]
                val rawName = meta?.name?.takeIf { it.isNotBlank() }
                    ?: detailLines.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                if (rawName in setOf("备注", "说明")) return@forEach

                val bracketValues = detailLines.flatMap { line ->
                    Regex("[\\[【]([^\\]】]+)[\\]】]").findAll(line).map { it.groupValues[1].trim() }.toList()
                }
                val arrangementTeacher = if (rawName.startsWith("【实验】")) {
                    null
                } else {
                    bracketValues.firstOrNull { value ->
                        !looksLikeWeeks(value) && !looksLikePeriods(value) && !looksLikeClassroom(value)
                    }
                }
                val teacher = arrangementTeacher ?: meta?.teacher?.takeIf { it.isNotBlank() }
                val classroom = bracketValues.lastOrNull { value ->
                    !looksLikeWeeks(value) && !looksLikePeriods(value) && value != teacher
                }

                add(CourseItem().apply {
                    code = meta?.code?.takeIf { it.isNotBlank() } ?: inferCourseCode(task)
                    name = rawName
                    this.rawName = rawName
                    this.weeks = weeks.toMutableList()
                    this.teacher = teacher
                    this.classroom = classroom
                    notes = first(row, "KCWZSM", "kcwzsm").ifBlank { null }
                    dow = weekday
                    this.begin = begin
                    last = end - begin + 1
                })
            }
        }
    }

    fun parseExams(body: String, term: TermItem): List<ExamItem>? {
        val parsed = parse(body) ?: return null
        return rowsElement(parsed).mapNotNull { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = first(row, "KCMC", "kcmc")
            val date = parseExamDate(first(row, "KSRQ", "ksrq"))
            val time = first(row, "KSJTSJ", "ksjtsj")
            if (name.isBlank() || date.isBlank() || time.isBlank()) return@mapNotNull null
            ExamItem().apply {
                courseName = name
                examDate = date
                examTime = time
                examType = first(row, "KSSJDMC", "kssjdmc").ifBlank { "考试" }
                examLocation = listOf(
                    first(row, "JXLMC", "jxlmc"),
                    first(row, "JXCDMC", "jxcdmc", "CDMC", "cdmc"),
                    first(row, "ZWH", "zwh").takeIf { it.isNotBlank() }?.let { "座位 $it" }.orEmpty()
                ).filter { it.isNotBlank() }.distinct().joinToString(" ")
                termName = term.name
                termId = term.id
                campusName = "深圳校区"
            }
        }
    }

    fun parseBuildings(body: String): List<BuildingItem>? {
        val parsed = parse(body) ?: return null
        return rowsElement(parsed).mapNotNull { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = first(row, "DM", "dm")
            if (id.isBlank()) return@mapNotNull null
            BuildingItem().apply {
                this.id = id
                this.name = first(row, "MC", "mc").ifBlank { id }
            }
        }
    }

    fun parseClassrooms(leftBody: String, occupancyBody: String): List<ClassroomItem>? {
        val left = parse(leftBody) ?: return null
        val occupancy = parse(occupancyBody) ?: return null
        val schedules = mutableMapOf<String, MutableList<JSONObject>>()
        rowsElement(occupancy).forEach { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val room = first(row, "CDDM", "cddm")
            val weekday = first(row, "XQJ", "xqj").toIntOrNull()
            val period = first(row, "XJ", "xj").toIntOrNull()
            val occupied = listOf(
                first(row, "PKBJ", "pkbj"),
                first(row, "JYBJ", "jybj"),
                first(row, "PKJYBJ", "pkjybj")
            ).any { it.isNotBlank() && it != "0" && it != "否" }
            if (room.isBlank() || weekday == null || period == null || !occupied) return@forEach
            schedules.getOrPut(room) { mutableListOf() }.add(JSONObject().apply {
                put("XQJ", weekday)
                put("XJ", period)
                put("PKBJ", "占用")
            })
        }

        return rowsElement(left).mapNotNull { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = first(row, "DM", "dm")
            if (id.isBlank()) return@mapNotNull null
            ClassroomItem().apply {
                this.id = id
                this.name = first(row, "MC", "mc").ifBlank { id }
                capacity = first(row, "ZWS", "zws").toDoubleOrNull()?.toInt() ?: 0
                specialClassroom = first(row, "SFKJ", "sfkj", "SFJTJS", "sfjtjs").ifBlank { null }
                scheduleList.addAll(schedules[id].orEmpty().sortedWith(
                    compareBy({ it.optInt("XQJ") }, { it.optInt("XJ") })
                ))
            }
        }
    }

    private fun parse(body: String): JsonElement? =
        runCatching { JsonParser().parse(body) }.getOrNull()

    private fun rows(body: String): List<JsonObject> =
        parse(body)?.let(::rowsElement)?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()

    private fun rowsElement(root: JsonElement): JsonArray {
        if (root.isJsonArray) return root.asJsonArray
        if (!root.isJsonObject) return JsonArray()
        val obj = root.asJsonObject
        if (obj.get("list")?.isJsonArray == true) return obj.getAsJsonArray("list")
        val content = obj.get("content")
        if (content?.isJsonArray == true) return content.asJsonArray
        if (content?.isJsonObject == true && content.asJsonObject.get("list")?.isJsonArray == true) {
            return content.asJsonObject.getAsJsonArray("list")
        }
        listOf("data", "rows", "result", "resultData", "kbjclist").forEach { key ->
            val nested = obj.get(key) ?: return@forEach
            if (nested.isJsonArray) return nested.asJsonArray
            if (nested.isJsonObject) {
                val rows = rowsElement(nested)
                if (rows.size() > 0) return rows
            }
        }
        return JsonArray()
    }

    private fun first(row: JsonObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = row.get(key) ?: return@forEach
            if (!value.isJsonPrimitive) return@forEach
            val text = runCatching { value.asString }.getOrNull()?.trim().orEmpty()
            if (text.isNotBlank() && !text.equals("null", true)) return text
        }
        return ""
    }

    private fun parseTime(raw: String): TimeInDay? {
        val timestampTime = runCatching {
            OffsetDateTime.parse(raw)
                .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                .toLocalTime()
        }.getOrNull()
        if (timestampTime != null) {
            return TimeInDay(timestampTime.hour, timestampTime.minute)
        }
        val parts = raw.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return TimeInDay(hour, minute)
    }

    private fun String.toLocalDateOrNull(): LocalDate? =
        runCatching { LocalDate.parse(take(10)) }.getOrNull()

    private fun parseExamDate(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
        }.getOrElse { raw.take(10).toLocalDateOrNull()?.toString().orEmpty() }
    }

    private fun parseWeeks(raw: String): List<Int> {
        val compact = raw.filterNot(Char::isWhitespace)
        if (compact.length >= 3 && compact.all { it == '0' || it == '1' }) {
            return compact.drop(1).mapIndexedNotNull { index, value ->
                (index + 1).takeIf { value == '1' && it <= 33 }
            }
        }
        val result = linkedSetOf<Int>()
        Regex("(\\d+)(?:\\s*[-－–—~～至到]\\s*(\\d+))?").findAll(raw).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: start
            if (start in 1..33 && end in start..33) result.addAll(start..end)
        }
        return result.toList()
    }

    private fun scheduleLines(raw: String): List<String> = raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .split('\n')
        .map { Jsoup.parse(it).text().trim() }
        .filter(String::isNotBlank)

    private fun timetableWeekday(row: JsonObject): Int? {
        first(row, "XQJ", "xqj").toIntOrNull()?.takeIf { it in 1..7 }?.let { return it }
        return Regex("(?i)^xq(\\d+)_jc").find(first(row, "KEY", "key"))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..7 }
    }

    private fun inferCourseCode(task: String): String? =
        Regex("^\\d{4}-\\d{4}-\\d-([A-Za-z]+\\d+[A-Za-z]?(?:-\\d+)?)").find(task)
            ?.groupValues?.getOrNull(1)

    private fun looksLikeWeeks(value: String) = value.contains("周") || value.contains("Week", true)
    private fun looksLikePeriods(value: String) = value.contains("节") || value.matches(Regex("\\d+\\s*[-—]\\s*\\d+"))
    private fun looksLikeClassroom(value: String) = value.matches(
        Regex("(?i)(?:[A-Z]{1,4}|教学楼|大学城|体育馆|主楼|实训楼|经管楼).*[0-9场地]|[A-Z]{1,4}\\d{2,4}")
    )
}
