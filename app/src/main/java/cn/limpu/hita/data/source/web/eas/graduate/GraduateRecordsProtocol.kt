package cn.limpu.hita.data.source.web.eas.graduate

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.ExamItem
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONObject

/**
 * 威海研究生成绩、考试、教学楼与空教室解析。
 *
 * 契约来源：iOS `WeihaiGraduateWebVPNRecords.swift` 及其
 * `WeihaiGraduateRecordsTest` 合成样例。纯 JVM，可离线驱动。
 */
internal object GraduateRecordsProtocol {

    /** 分页收集：总数稳定、页码匹配、中间页完整、无重复，收齐才返回。 */
    class PageCollector {
        private val rows = mutableListOf<JsonObject>()
        private var total: Int? = null
        private var pages = 0

        /** @return true 表示已收齐。 */
        fun consume(body: String, requestedPage: Int, enveloped: Boolean): Boolean {
            val root = runCatching { JsonParser().parse(body).asJsonObject }.getOrElse { invalid() }
            val page = if (enveloped) {
                val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
                require(code == 0 || code == 200) { "Graduate response code $code" }
                root.get("content")?.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
            } else {
                require(!root.has("content")) { "Unexpected envelope" }
                root
            }
            page.get("success")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.let {
                require(it.asBoolean) { "Business failure" }
            }
            val list = page.get("list")?.takeIf { it.isJsonArray }?.asJsonArray ?: invalid()
            val pageTotal = page.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: invalid()
            val pageNum = page.get("pageNum")?.takeIf { it.isJsonPrimitive }?.asInt ?: invalid()
            val serverSize = page.get("pageSize")?.takeIf { it.isJsonPrimitive }?.asInt ?: invalid()
            require(pageTotal >= 0 && pageNum == requestedPage && serverSize > 0) { "Pagination mismatch" }
            require(list.size() <= serverSize && list.size() <= pageTotal) { "Rows exceed total" }
            val knownTotal = total
            if (knownTotal != null) require(knownTotal == pageTotal) { "Total changed between pages" }
            total = pageTotal
            pages++
            require(pages <= MAX_PAGES) { "Too many pages" }
            val expected = (pageTotal - (requestedPage - 1) * serverSize).coerceIn(0, serverSize)
            require(list.size() == expected) { "Incomplete page $requestedPage" }
            list.forEach { rows += GraduateJson.objectOf(it) }
            require(rows.size <= pageTotal) { "Rows exceed total" }
            return rows.size == pageTotal
        }

        fun completedRows(): List<JsonObject> {
            val expected = total ?: invalid()
            require(rows.size == expected) { "Pagination incomplete" }
            require(rows.map(::rowIdentity).distinct().size == rows.size) { "Duplicate rows" }
            return rows.toList()
        }

        private fun rowIdentity(row: JsonObject): String = row.toString()

        companion object {
            const val MAX_PAGES = 100
        }
    }

    fun grades(rows: List<JsonObject>, termId: String): List<CourseScoreItem> {
        val seen = mutableSetOf<String>()
        return rows.map { row ->
            val name = GraduateJson.requiredText(row, "kcmc") ?: invalid()
            val code = GraduateJson.requiredText(row, "kcdm") ?: invalid()
            val termName = GraduateJson.requiredText(row, "xnxqmc") ?: invalid()
            // 只展示公布成绩 xscj 与公布数值 xszscj，不使用隐藏的 zzzscj。
            val scoreText = GraduateJson.requiredText(row, "xscj") ?: invalid()
            val id = listOf(
                termId, code, termName,
                GraduateJson.text(row, "bkcx") ?: "",
                GraduateJson.text(row, "cjid") ?: "",
            ).joinToString("|")
            require(seen.add(id)) { "Duplicate graduate grade" }
            val numeric = GraduateJson.text(row, "xszscj")?.toDoubleOrNull()
            val credits = GraduateJson.text(row, "xf")?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
            CourseScoreItem().apply {
                courseName = name
                courseCode = code
                this.termName = termName
                finalScoresText = scoreText
                finalScores = numeric?.toInt() ?: -1
                numericScore = numeric
                this.credits = credits ?: 0f
                hours = GraduateJson.text(row, "xs")?.toIntOrNull() ?: 0
                courseProperty = GraduateJson.text(row, "kcxz")
                courseCategory = GraduateJson.text(row, "kclb")
                schoolName = GraduateJson.text(row, "yxmc")
                assessMethod = GraduateJson.text(row, "khfs")
            }
        }
    }

    fun exams(rows: List<JsonObject>, termId: String, termName: String): List<ExamItem> {
        val seen = mutableSetOf<String>()
        return rows.map { row ->
            val name = GraduateJson.requiredText(row, "KCMC") ?: invalid()
            require(GraduateJson.requiredText(row, "PYLX") == "2") { "Non-graduate exam row" }
            val code = GraduateJson.requiredText(row, "KCDM") ?: invalid()
            val date = GraduateJson.text(row, "KSRQ2")
            val time = GraduateJson.text(row, "KSJTSJ")
            val begin = GraduateJson.int(row, "KSJC")
            val end = GraduateJson.int(row, "JSJC")
            require((begin == null) == (end == null)) { "Exam period must be all-or-nothing" }
            if (begin != null && end != null) require(begin > 0 && end >= begin) { "Invalid exam period" }
            val location = listOfNotNull(
                GraduateJson.text(row, "JXLMC"),
                GraduateJson.text(row, "CDMC"),
            ).joinToString(" ")
            // 只有节次没有钟点时使用“第n-m节”，不伪造具体钟点。
            val periodLabel = if (begin != null && end != null) "第${begin}-${end}节" else null
            val id = listOf(
                termId, code, date ?: "", time ?: periodLabel ?: "",
                GraduateJson.text(row, "KSSJDMC") ?: "", location,
            ).joinToString("|")
            require(seen.add(id)) { "Duplicate graduate exam" }
            ExamItem().apply {
                courseName = name
                examDate = date
                examTime = time ?: periodLabel
                examType = GraduateJson.text(row, "KSSJDMC")
                examLocation = location.takeIf { it.isNotBlank() }
                this.termName = termName
                this.termId = termId
                campusName = "威海校区"
            }
        }
    }

    fun buildings(body: String): List<BuildingItem> {
        val rows = body.jsonArrayOrNull() ?: invalid()
        val seen = mutableSetOf<String>()
        return rows.map { element ->
            val row = GraduateJson.objectOf(element)
            val id = GraduateJson.requiredText(row, "DM") ?: invalid()
            require(seen.add(id)) { "Duplicate building" }
            BuildingItem().apply {
                this.id = id
                name = GraduateJson.requiredText(row, "MC") ?: invalid()
            }
        }
    }

    fun classrooms(rooms: List<JsonObject>, occupancyBody: String): List<ClassroomItem> {
        val occupancy = occupancyBody.jsonArrayOrNull() ?: invalid()
        val schedules = mutableMapOf<String, MutableList<OccupancyCell>>()
        val cells = mutableSetOf<String>()
        occupancy.forEach { element ->
            val row = GraduateJson.objectOf(element)
            val room = GraduateJson.requiredText(row, "CDDM") ?: invalid()
            val weekday = GraduateJson.int(row, "XQJ") ?: invalid()
            val period = GraduateJson.int(row, "XJ") ?: invalid()
            require(weekday in 1..7 && period > 0) { "Invalid occupancy cell" }
            require(cells.add("$room|$weekday|$period")) { "Duplicate occupancy cell" }
            schedules.getOrPut(room) { mutableListOf() }.add(
                OccupancyCell(weekday, period, occupancyState(row))
            )
        }
        val seen = mutableSetOf<String>()
        return rooms.map { row ->
            val id = GraduateJson.requiredText(row, "DM") ?: invalid()
            require(seen.add(id)) { "Duplicate classroom" }
            ClassroomItem().apply {
                this.id = id
                name = GraduateJson.requiredText(row, "MC") ?: invalid()
                capacity = GraduateJson.int(row, "ZWS")?.also { require(it >= 0) } ?: 0
                specialClassroom = GraduateJson.text(row, "SFKJ")
                scheduleList = scheduleJson(schedules[id].orEmpty())
            }
        }
    }

    private data class OccupancyCell(val weekday: Int, val period: Int, val state: String)

    /** 下游 `classroom.scheduleList` 使用 XQJ/XJ/PKBJ 键；仅在 Android 运行时构造。 */
    private fun scheduleJson(cells: List<OccupancyCell>): MutableList<JSONObject> =
        cells.sortedWith(compareBy({ it.weekday }, { it.period })).map { cell ->
            JSONObject().apply {
                put("XQJ", cell.weekday)
                put("XJ", cell.period)
                put("PKBJ", cell.state)
            }
        }.toMutableList()

    /** 纯 JVM 视图，供离线验证不依赖 org.json 的 Android 桩实现。 */
    fun occupancyCells(occupancyBody: String): Map<String, List<Triple<Int, Int, String>>> {
        val occupancy = occupancyBody.jsonArrayOrNull() ?: invalid()
        val result = linkedMapOf<String, MutableList<Triple<Int, Int, String>>>()
        occupancy.forEach { element ->
            val row = GraduateJson.objectOf(element)
            val room = GraduateJson.requiredText(row, "CDDM") ?: invalid()
            val weekday = GraduateJson.int(row, "XQJ") ?: invalid()
            val period = GraduateJson.int(row, "XJ") ?: invalid()
            require(weekday in 1..7 && period > 0) { "Invalid occupancy cell" }
            result.getOrPut(room) { mutableListOf() } += Triple(weekday, period, occupancyState(row))
        }
        return result
    }

    /** 失败的占用标记不能被当成空闲。 */
    private fun occupancyState(row: JsonObject): String {
        val keys = listOf("PKBJ", "JYBJ", "PKJYBJ", "PKSB", "JYSB")
        require(keys.all { row.has(it) }) { "Occupancy fields missing" }
        val scheduled = GraduateJson.text(row, "PKBJ")
        val borrowed = GraduateJson.text(row, "JYBJ")
        val composite = GraduateJson.text(row, "PKJYBJ")
        val schedulingFailure = GraduateJson.text(row, "PKSB")
        val borrowingFailure = GraduateJson.text(row, "JYSB")
        require(scheduled == null || scheduled == "排" || scheduled == "考") { "Invalid scheduling flag" }
        require(borrowed == null || borrowed == "借") { "Invalid borrowing flag" }
        require(composite == null || composite == "0" || composite == "1") { "Invalid composite flag" }
        require(schedulingFailure == null || schedulingFailure == "0" || schedulingFailure == "1") {
            "Invalid scheduling failure"
        }
        require(borrowingFailure == null || borrowingFailure == "0" || borrowingFailure == "1") {
            "Invalid borrowing failure"
        }
        require(schedulingFailure != "1" && borrowingFailure != "1") { "Occupancy unavailable" }
        if (composite == "1") {
            require(scheduled != null && borrowed == "借") { "Composite requires schedule and borrowing" }
            return "$scheduled、借"
        }
        require(composite == "0" || composite == null) { "Invalid composite" }
        if (scheduled != null) {
            require(borrowed == null && composite == "0") { "Scheduled cell must not borrow" }
            return scheduled
        }
        if (borrowed == "借") {
            require(composite == "0") { "Borrowed cell requires composite" }
            return "借"
        }
        return ""
    }

    private fun String.jsonArrayOrNull(): JsonArray? =
        runCatching { JsonParser().parse(this).asJsonArray }.getOrNull()

    private fun <T> invalid(): T = throw GraduateInvalidResponseException()
}
