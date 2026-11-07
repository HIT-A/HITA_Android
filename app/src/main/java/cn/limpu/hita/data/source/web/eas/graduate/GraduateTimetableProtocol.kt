package cn.limpu.hita.data.source.web.eas.graduate

import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.GraduateCourseRow
import cn.limpu.hita.data.model.eas.GraduateFilterOption
import cn.limpu.hita.data.model.eas.GraduateFilterOptions
import cn.limpu.hita.data.model.eas.GraduateSelectedCourses
import cn.limpu.hita.data.model.eas.GraduateSelectionCategory
import cn.limpu.hita.data.model.eas.GraduateSelectionPage
import cn.limpu.hita.data.model.eas.ShenzhenCourseMeeting
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * 威海研究生学期、作息、个人课表与课程规划解析。
 *
 * 纯 JVM，无 Android 依赖；网络由 `GraduateHttpTransport` 注入，合成响应可直接驱动
 * 实际生产解析链。字段契约来自 iOS `WeihaiGraduateWebVPNTimetable.swift`、
 * `WeihaiGraduateCoursePlanning.swift` 与对应 XCTest 合成样例。
 */
internal object GraduateTimetableProtocol {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val weekdays = listOf("MON", "TUES", "WED", "THUR", "FRI", "SAT", "SUN")

    fun terms(currentBody: String, optionsBody: String): List<TermItem> {
        val current = jsonObject(currentBody)
        val year = requiredText(current, "XN") ?: invalid()
        val term = requiredText(current, "XQ") ?: invalid()
        val currentId = "$year-$term"
        val parsed = LinkedHashMap<String, TermItem>()
        (optionsBody.jsonArrayOrNull() ?: invalid()).forEach { element ->
            val row = objectOf(element)
            val optionYear = optionalText(row, "XN") ?: optionalText(row, "xn")
            val optionTerm = optionalText(row, "XQ") ?: optionalText(row, "xq")
            if (optionYear == null || optionTerm == null) return@forEach
            val id = "$optionYear-$optionTerm"
            require(!parsed.containsKey(id)) { "Duplicate graduate term $id" }
            val yearName = optionalText(row, "XNMC") ?: optionalText(row, "xnmc") ?: optionYear
            val termName = optionalText(row, "XQMC") ?: optionalText(row, "xqmc") ?: termLabel(optionTerm)
            parsed[id] = term(optionYear, yearName, optionTerm, termName, id == currentId)
        }
        if (!parsed.containsKey(currentId)) {
            parsed[currentId] = term(year, year, term, termLabel(term), true)
        }
        return parsed.values.toList()
    }

    private fun term(year: String, yearName: String, termCode: String, termName: String, current: Boolean) =
        TermItem(year, yearName, termCode, termName).apply {
            isCurrent = current
        }

    private fun termLabel(code: String) = when (code) {
        "1" -> "秋季"
        "2" -> "春季"
        "3" -> "夏季"
        else -> ""
    }

    enum class TimetablePublication { AVAILABLE, UNPUBLISHED, EVALUATION_REQUIRED }

    /** `querykbsffb`: 1 可用、0 未发布、2 未评教。未知不是空课表。 */
    fun publication(body: String): TimetablePublication = when (body.trim()) {
        "1" -> TimetablePublication.AVAILABLE
        "0" -> TimetablePublication.UNPUBLISHED
        "2" -> TimetablePublication.EVALUATION_REQUIRED
        else -> invalid()
    }

    /** `Xsxk/query/2` 页面中的四个只读运行配置。 */
    fun runtimeFields(html: String): Map<String, String> {
        val fields = listOf("p_pylx", "p_sfgldjr", "p_sfredis", "p_sfsyxkgwc").associateWith { name ->
            val matches = Regex("""(?:var|let|const)\s+$name\s*=\s*['"](-?\d+)['"]\s*;?""")
                .findAll(html).toList()
            require(matches.size == 1) { "Expected exactly one $name" }
            matches.single().groupValues[1]
        }
        require(fields["p_pylx"] == "2") { "Graduate planning requires pylx=2" }
        return fields
    }

    /** `queryXkdqXnxq`：模块当前学期，历史查询时不能与请求学期混用。 */
    data class ModuleTerm(val year: String, val term: String, val combined: String) {
        val fields: Map<String, String>
            get() = mapOf("p_dqxn" to year, "p_dqxq" to term, "p_dqxnxq" to combined)
    }

    fun moduleTerm(body: String): ModuleTerm {
        val row = jsonObject(body)
        val year = requiredText(row, "p_dqxn") ?: invalid()
        val term = requiredText(row, "p_dqxq") ?: invalid()
        val combined = requiredText(row, "p_dqxnxq") ?: invalid()
        require(combined == year + term) { "Module term mismatch" }
        return ModuleTerm(year, term, combined)
    }

    /** `kbjclist` → 连续小节作息（北京时间）。 */
    fun schedule(selectedBody: String, year: String, term: String): List<TimePeriodInDay> {
        val rows = jsonObject(selectedBody).array("kbjclist")
        require(rows.size() > 0) { "Graduate schedule is required" }
        val periods = rows.map { element ->
            val row = objectOf(element)
            require(optionalText(row, "XN") == year && optionalText(row, "XQ") == term) {
                "Schedule term must match request"
            }
            val index = requiredInt(row, "XJ") ?: invalid()
            require(index > 0) { "Schedule index must be positive" }
            val start = clock(requiredText(row, "KSSJ"))
            val end = clock(requiredText(row, "JSSJ"))
            require(start != null && end != null && start.before(end)) { "Invalid schedule clock" }
            index to TimePeriodInDay(requireNotNull(start), requireNotNull(end))
        }.sortedBy { it.first }
        periods.forEachIndexed { offset, entry ->
            require(entry.first == offset + 1) { "Schedule indices must be consecutive" }
            if (offset > 0) {
                val previousEnd = periods[offset - 1].second.to
                val start = entry.second.from
                require(previousEnd.before(start) || previousEnd == start) { "Schedule periods overlap" }
            }
        }
        return periods.map { it.second }
    }

    /** `xlList`：第一周周一；所有有效行必须推导同一天。 */
    fun termStartDate(body: String, year: String, term: String): LocalDate {
        val rows = jsonObject(body).array("xlList")
        val wireTerm = year + term
        var start: LocalDate? = null
        rows.forEach { element ->
            val row = objectOf(element)
            require(optionalText(row, "XNXQ") == wireTerm) { "Calendar term must match request" }
            val week = requiredInt(row, "ZC") ?: invalid()
            require(week in 0..33) { "Calendar week out of range" }
            if (week == 0) return@forEach
            val raw = requiredText(row, "RQ") ?: invalid()
            val date = runCatching { LocalDate.parse(raw, dateFormat) }.getOrElse { invalid() }
            val named = weekdays.mapIndexedNotNull { index, key ->
                optionalText(row, key)?.let { index + 1 to it }
            }
            require(named.size == 1) { "Calendar row must name exactly one weekday" }
            val (weekday, value) = named.single()
            require(value == raw && date.dayOfWeek.value == weekday) { "Calendar weekday mismatch" }
            val candidate = date.minusDays(((week - 1) * 7 + weekday - 1).toLong())
            require(start == null || start == candidate) { "Calendar rows disagree on term start" }
            start = candidate
        }
        return start ?: invalid()
    }

    data class TimetableResult(
        val courses: List<CourseItem>,
        val schedule: List<TimePeriodInDay>,
        val startDate: LocalDate,
    )

    fun timetable(
        rowsBody: String,
        selectedBody: String,
        calendarBody: String,
        year: String,
        term: String,
    ): TimetableResult {
        val schedule = schedule(selectedBody, year, term)
        val metadata = selectedCourses(selectedBody, year, term)
        val taskLookup = metadata.rows.associateBy(GraduateCourseRow::taskNumber)
        val startDate = termStartDate(calendarBody, year, term)
        val rows = rowsBody.jsonArrayOrNull() ?: invalid()
        val courses = rows.mapNotNull { element ->
            val row = objectOf(element)
            val key = requiredText(row, "KEY") ?: invalid()
            if (key.equals("bz", ignoreCase = true)) return@mapNotNull null
            val keyMatch = Regex("""(?i)^xq([1-7])_jc(\d+)$""").matchEntire(key) ?: invalid()
            val weekday = keyMatch.groupValues[1].toInt()
            val begin = requiredInt(row, "KSJC") ?: invalid()
            val end = requiredInt(row, "JSJC") ?: invalid()
            require(begin > 0 && end >= begin && end <= schedule.size) { "Timetable period out of range" }
            val teachingWeeks = weeks(requiredText(row, "ZC") ?: invalid())
            val metadataRow = optionalText(row, "RWH")?.let(taskLookup::get)
            val display = displayText(optionalText(row, "SKSJ"))
            CourseItem().apply {
                code = metadataRow?.courseCode?.takeIf { it.isNotBlank() }
                name = metadataRow?.courseName?.takeIf { it.isNotBlank() } ?: display.name
                weeks = teachingWeeks
                teacher = metadataRow?.teachers?.firstOrNull()?.takeIf { it.isNotBlank() } ?: display.teacher
                classroom = display.location
                rawName = display.rawName
                notes = display.remark
                dow = weekday
                this.begin = begin
                last = end - begin + 1
                startTime = clockText(schedule[begin - 1].from)
                endTime = clockText(schedule[end - 1].to)
            }
        }
        require(courses.isNotEmpty()) { "Empty graduate timetable is not authoritative" }
        val merged = courses.groupBy {
            listOf(it.code, it.name, it.teacher, it.classroom, it.dow, it.begin, it.last, it.startTime, it.endTime)
        }.values.map { group ->
            group.first().also { course ->
                course.weeks = group.flatMap { it.weeks }.distinct().sorted().toMutableList()
            }
        }.sortedWith(compareBy({ it.dow }, { it.begin }, { it.name.orEmpty() }))
        return TimetableResult(merged, schedule, startDate)
    }

    private data class DisplayText(
        val name: String,
        val teacher: String?,
        val location: String?,
        val rawName: String?,
        val remark: String?,
    )

    private fun displayText(raw: String?): DisplayText {
        if (raw.isNullOrBlank()) return DisplayText("", null, null, null, null)
        val lines = raw.replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .split('\n').map(String::trim)
        val name = lines.firstOrNull().orEmpty()
        val teacher = lines.getOrNull(1)?.trim('[', ']', '【', '】')?.takeIf { it.isNotBlank() }
        val locationLine = lines.getOrNull(2).orEmpty()
        val location = Regex("""[\[【][^\]】]*周[^\]】]*[\]】]\s*[\[【]([^\]】]+)[\]】]""")
            .find(locationLine)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val remark = lines.getOrNull(3)?.takeIf { it.isNotBlank() }
        return DisplayText(name, teacher, location, raw, remark)
    }

    /** 34 位周次位图，索引 0 不使用，1..33 表示教学周。 */
    fun weeks(bitset: String): MutableList<Int> {
        require(bitset.length == 34 && bitset.all { it == '0' || it == '1' }) { "Invalid week bitset" }
        val result = bitset.drop(1).mapIndexedNotNull { index, bit ->
            (index + 1).takeIf { bit == '1' }
        }
        require(result.isNotEmpty()) { "Timetable row has no teaching week" }
        return result.toMutableList()
    }

    fun selectedCourses(body: String, year: String, term: String): GraduateSelectedCourses {
        val root = jsonObject(body)
        root.get("xsxkPage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { page ->
            require(
                optionalText(page, "p_xn") == year &&
                    optionalText(page, "p_xq") == term &&
                    optionalText(page, "p_xnxq") == year + term
            ) { "Selected courses term mismatch" }
        }
        val rows = root.array("yxkcList")
        val categories = root.get("xkgzszList")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                val row = objectOf(element)
                val id = optionalText(row, "xkfsdm")
                val name = optionalText(row, "xkfsmc")
                if (id == null || name == null) null else GraduateSelectionCategory(id, name)
            }.orEmpty()
        val parsed = rows.map { courseRow(objectOf(it)) }
        require(parsed.map(GraduateCourseRow::taskNumber).distinct().size == parsed.size) {
            "Duplicate task number"
        }
        return GraduateSelectedCourses(parsed, categories)
    }

    fun candidates(body: String, categoryId: String, page: Int, pageSize: Int): GraduateSelectionPage {
        val payload = jsonObject(body).get("kxrwList")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: invalid()
        return selectionPage(payload, page, pageSize).also { parsed ->
            if (categoryId.isNotBlank()) {
                parsed.rows.filter { it.selectionMode.isNotBlank() }
                    .forEach { require(it.selectionMode == categoryId) { "Mixed selection category" } }
            }
        }
    }

    fun schoolCourses(body: String, page: Int, pageSize: Int): GraduateSelectionPage {
        val payload = jsonObject(body).get("rwList")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: invalid()
        return selectionPage(payload, page, pageSize).also { parsed ->
            parsed.rows.forEach { require(it.trainingLevel == "2") { "Non-graduate row in school timetable" } }
        }
    }

    private fun selectionPage(payload: JsonObject, page: Int, pageSize: Int): GraduateSelectionPage {
        val list = payload.array("list")
        val total = requiredInt(payload, "total") ?: invalid()
        val pageNum = requiredInt(payload, "pageNum") ?: invalid()
        val serverSize = requiredInt(payload, "pageSize") ?: invalid()
        require(total >= 0 && pageNum == page && serverSize > 0) { "Pagination mismatch" }
        require(list.size() <= serverSize && list.size() <= total) { "Page rows exceed total" }
        val expected = (total - (page - 1) * serverSize).coerceIn(0, serverSize)
        require(list.size() == expected) { "Page row count incomplete" }
        val rows = list.map { courseRow(objectOf(it)) }
        require(rows.map(GraduateCourseRow::taskNumber).distinct().size == rows.size) {
            "Duplicate task number"
        }
        return GraduateSelectionPage(rows, total, pageNum, serverSize)
    }

    private fun courseRow(row: JsonObject): GraduateCourseRow {
        val task = firstText(row, "rwh", "RWH") ?: invalid()
        val structured = row.get("sksjList")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull(::structuredMeeting).orEmpty()
        return GraduateCourseRow(
            taskNumber = task,
            requestId = firstText(row, "id") ?: task,
            courseCode = firstText(row, "kcdm", "KCDM") ?: "",
            courseName = firstText(row, "kcmc", "KCMC") ?: invalid(),
            teachers = firstText(row, "dgjsmc")?.let(::listOf).orEmpty(),
            credits = firstText(row, "xf") ?: "",
            totalHours = firstText(row, "xs") ?: "",
            nature = firstText(row, "kcxz") ?: "",
            category = firstText(row, "kclb") ?: "",
            college = firstText(row, "kkyxmc", "kkyx") ?: "",
            campus = firstText(row, "xqmc", "xiaoqumc") ?: "",
            classNumber = firstText(row, "bjh", "dgbjmc") ?: "",
            trainingLevel = firstText(row, "pylx", "PYLX") ?: "",
            capacity = firstInt(row, "zrl", "kxrs"),
            selectedCount = firstInt(row, "yxzrs", "yxrs"),
            selectionMode = firstText(row, "xkfsdm") ?: "",
            selectionModeName = firstText(row, "xkfsmc") ?: "",
            note = firstText(row, "message", "msg") ?: "",
            meetings = structured.ifEmpty { parsePkjgmx(firstText(row, "pkjgmx")) },
        )
    }

    private fun structuredMeeting(element: JsonElement): ShenzhenCourseMeeting? = runCatching {
        val row = objectOf(element)
        val weekday = requiredInt(row, "XQJ") ?: return@runCatching null
        val begin = requiredInt(row, "KSJC") ?: return@runCatching null
        val end = firstInt(row, "JSJC", "CXJC") ?: begin
        val weeks = firstText(row, "ZC")?.let(::parseWeekRanges).orEmpty()
        if (weeks.isEmpty() || weekday !in 1..7 || begin <= 0 || end < begin) return@runCatching null
        ShenzhenCourseMeeting(
            weeks, weekday, begin, end,
            firstText(row, "JSXM") ?: "", firstText(row, "CDMC") ?: "",
        )
    }.getOrNull()

    /** `pkjgmx`：`<p>2-4周,星期二第5-6节 地点</p>`。 */
    fun parsePkjgmx(html: String?): List<ShenzhenCourseMeeting> {
        if (html.isNullOrBlank()) return emptyList()
        val text = html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val weekdayChar = Regex("""(?:星期|周)([一二三四五六日天])""").find(text)
            ?.groupValues?.get(1) ?: return emptyList()
        val weekday = weekdayOf(weekdayChar)
        val period = Regex("""第(\d+)(?:-(\d+))?节""").find(text) ?: return emptyList()
        val begin = period.groupValues[1].toInt()
        val end = period.groupValues[2].takeIf(String::isNotEmpty)?.toInt() ?: begin
        if (begin <= 0 || end < begin) return emptyList()
        val weeksRaw = Regex("""([\d\-,，、单双周]+)周""").find(text)?.groupValues?.get(1).orEmpty()
        val weeks = parseWeekRanges(weeksRaw)
        if (weeks.isEmpty()) return emptyList()
        val location = text.substring(period.range.last + 1).trim()
        return listOf(ShenzhenCourseMeeting(weeks, weekday, begin, end, "", location))
    }

    private fun weekdayOf(value: String) = when (value) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        else -> 7
    }

    /** 支持 `1-16`、`1,3,5`、`2-8双`、`1-15单`。 */
    fun parseWeekRanges(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        val odd = raw.contains("单")
        val even = raw.contains("双")
        val result = sortedSetOf<Int>()
        Regex("""(\d+)(?:\s*-\s*(\d+))?""").findAll(raw).forEach { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt() ?: start
            if (start <= 0 || end < start || end > 33) return emptyList()
            (start..end).forEach { week ->
                val matchesParity = when {
                    odd -> week % 2 == 1
                    even -> week % 2 == 0
                    else -> true
                }
                if (matchesParity) result += week
            }
        }
        return result.toList()
    }

    fun filters(body: String, campuses: List<GraduateFilterOption> = emptyList(), colleges: List<GraduateFilterOption> = emptyList()): GraduateFilterOptions {
        val root = jsonObject(body)
        val periods = root.array("jclist").mapNotNull { optionalText(objectOf(it), "XJMS") }
        val weeks = root.array("zclist").mapNotNull { requiredInt(objectOf(it), "ZC") }
        require(periods.isNotEmpty() && weeks.isNotEmpty()) { "Filter lists must not be empty" }
        return GraduateFilterOptions(periods, weeks, campuses, colleges)
    }

    fun options(body: String): List<GraduateFilterOption> {
        val rows = body.jsonArrayOrNull() ?: invalid()
        return rows.map { element ->
            val row = objectOf(element)
            GraduateFilterOption(
                firstText(row, "DM", "dm") ?: invalid(),
                firstText(row, "MC", "mc") ?: invalid(),
            )
        }
    }

    private fun clock(raw: String): TimeInDay? = runCatching {
        if (raw.contains("T")) {
            val time = OffsetDateTime.parse(raw).atZoneSameInstant(shanghai).toLocalTime()
            TimeInDay(time.hour, time.minute)
        } else {
            val parts = raw.split(':').map(String::toInt)
            require(parts.size == 2 || parts.size == 3)
            require(parts[0] in 0..23 && parts[1] in 0..59)
            TimeInDay(parts[0], parts[1])
        }
    }.getOrNull()

    private fun clockText(time: TimeInDay) = "%02d:%02d".format(time.hour, time.minute)

    fun jsonObject(body: String): JsonObject = runCatching { JsonParser().parse(body).asJsonObject }
        .getOrElse { invalid() }

    private fun String.jsonArrayOrNull(): JsonArray? = runCatching { JsonParser().parse(this).asJsonArray }
        .getOrNull()

    private fun JsonObject.array(key: String): JsonArray =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: invalid()

    private fun objectOf(element: JsonElement): JsonObject =
        element.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()

    private fun firstText(row: JsonObject, vararg keys: String): String? {
        keys.forEach { key -> optionalText(row, key)?.let { return it } }
        return null
    }

    private fun firstInt(row: JsonObject, vararg keys: String): Int? {
        keys.forEach { key -> optionalInt(row, key)?.let { return it } }
        return null
    }

    private fun requiredText(row: JsonObject, key: String): String =
        optionalText(row, key) ?: invalid()

    private fun requiredInt(row: JsonObject, key: String): Int =
        optionalInt(row, key) ?: invalid()

    private fun optionalText(row: JsonObject, key: String): String? {
        val value = row.get(key) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        val primitive = value.asJsonPrimitive
        if (!primitive.isString && !primitive.isNumber) return null
        return primitive.asString.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun optionalInt(row: JsonObject, key: String): Int? = optionalText(row, key)?.toIntOrNull()

    private fun <T> invalid(): T = throw GraduateInvalidResponseException()
}
