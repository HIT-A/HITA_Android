package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.ShenzhenCreditGroupProgress
import cn.limpu.hita.data.model.eas.ShenzhenCreditGroupCourse
import cn.limpu.hita.data.model.eas.ShenzhenCreditIdentity
import cn.limpu.hita.data.model.eas.ShenzhenCreditPlanContext
import cn.limpu.hita.data.model.eas.ShenzhenCreditProgress
import cn.limpu.hita.data.model.eas.ShenzhenCreditRequirement
import cn.limpu.hita.data.model.eas.ShenzhenCreditCourseRecord
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object ShenzhenCreditProgressParser {
    fun parseIdentity(body: String): ShenzhenCreditIdentity? {
        val root = parse(body) ?: return null
        val source = allObjects(root).firstOrNull { row ->
            first(row, "XH", "xh", "YHDM", "yhdm").isNotBlank()
        } ?: return null
        val studentNumber = first(source, "XH", "xh", "YHDM", "yhdm")
        val recordId = first(source, "xjid", "XJID", "ID", "id")
        val studentType = first(source, "pylx", "PYLX").let(::studentTypeCode)
        val grade = Regex("(?:19|20)\\d{2}")
            .find(first(source, "NJ", "nj", "NJMC", "njmc", "NJDM", "njdm"))
            ?.value.orEmpty()
        if (studentNumber.isBlank()) return null
        return ShenzhenCreditIdentity(studentNumber, recordId, studentType, grade)
    }

    fun parseCurrentTerm(body: String): String? {
        val root = parse(body)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val year = first(root, "XN", "xn")
        val term = first(root, "XQ", "xq")
        return if (year.isBlank() || term.isBlank()) null else "$year$term"
    }

    fun parseCourseRecordTotal(body: String): Int {
        val root = parse(body)?.takeIf { it.isJsonObject }?.asJsonObject ?: return 0
        return number(root, "R", "r")?.toInt()?.coerceAtLeast(0) ?: 0
    }

    fun parsePlanContext(body: String): ShenzhenCreditPlanContext? {
        val root = parse(body)
        if (root == null || root.isJsonPrimitive) {
            val raw = root?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asString }.getOrNull() }
                ?: body.trim().removeSurrounding("\"")
            return raw.trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,}")) }
                ?.let { ShenzhenCreditPlanContext(it) }
        }
        val source = allObjects(root).firstOrNull {
            first(it, "FAH", "fah", "PYFADM", "pyfadm").isNotBlank()
        } ?: return null
        val planId = first(source, "FAH", "fah", "PYFADM", "pyfadm")
        return ShenzhenCreditPlanContext(
            planId = planId,
            changeId = first(source, "BGID", "bgid")
        ).takeIf { it.planId.isNotBlank() }
    }

    fun parseProgress(
        summaryBody: String,
        categoriesBody: String,
        groupsBody: String,
        groupCourseBodies: Map<String, String> = emptyMap(),
        courseRecordBodies: List<String>,
        currentTerm: String,
        allowMissingSummary: Boolean = false
    ): ShenzhenCreditProgress? {
        val summaryRoot = objectRoot(summaryBody)
        if (summaryRoot == null && !allowMissingSummary) return null
        val summary = objectAt(summaryRoot, "content")
        if (summary == null && !allowMissingSummary) return null
        val required = objectAt(summary, "yqmsxf", "YQMSXF")
        val requiredCredits = number(required, "YQXF", "yqxf") ?: 0.0
        val completedCredits = number(summary, "ywcxf", "YWCXF") ?: 0.0
        val remainingCredits = number(summary, "wwcxf", "WWCXF")
            ?: (requiredCredits - completedCredits).coerceAtLeast(0.0)

        return ShenzhenCreditProgress(
            requiredCredits = requiredCredits,
            completedCredits = completedCredits,
            remainingCredits = remainingCredits,
            requiredCourses = number(required, "YQMS", "yqms")?.toInt(),
            completedCourses = number(summary, "ywcms", "YWCMS")?.toInt(),
            remainingCourses = number(summary, "wwcms", "WWCMS")?.toInt(),
            averageCreditScore = number(required, "XFJ", "xfj"),
            rank = number(required, "PM", "pm")?.toInt(),
            cohortSize = number(required, "ZYRS", "zyrs")?.toInt(),
            currentTerm = currentTerm,
            categories = parseCategories(categoriesBody).ifEmpty {
                parseCategories(summaryBody)
            },
            groups = parseGroups(groupsBody, groupCourseBodies),
            courseRecords = parseCourseRecords(courseRecordBodies)
        )
    }

    fun parseCourseRecordPageCount(body: String): Int {
        val root = objectRoot(body) ?: return 1
        val content = objectAt(root, "content") ?: return 1
        return number(content, "pages", "PAGES")?.toInt()?.coerceAtLeast(1) ?: 1
    }

    private fun parseCategories(body: String): List<ShenzhenCreditRequirement> {
        val root = objectRoot(body) ?: return emptyList()
        val content = objectAt(root, "content") ?: root
        val rows = arrayAt(content, "xflbyq", "XFLBYQ") ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = first(row, "kclbmc", "KCLBMC")
            val direction = first(row, "zyfxmc", "ZYFXMC")
            val nature = first(row, "kcxzmc", "KCXZMC")
            val language = first(row, "skyymc", "SKYYMC")
            val required = number(row, "yqwcxf", "YQWCXF") ?: return@mapNotNull null
            val completed = number(row, "ywcxf", "YWCXF") ?: 0.0
            val remaining = number(row, "wwcxf", "WWCXF")
                ?: (required - completed).coerceAtLeast(0.0)
            val creditedMoocCredits = number(row, "moochdxf", "MOOCHDXF") ?: 0.0
            val earnedMoocCredits = number(row, "moocsjhdxf", "MOOCSJHDXF") ?: 0.0
            ShenzhenCreditRequirement(
                id = listOf(
                    first(row, "kcxzdm", "KCXZDM"),
                    first(row, "kclbdm", "KCLBDM"),
                    first(row, "skyydm", "SKYYDM")
                ).joinToString("|"),
                name = name.ifBlank { "未命名类别" },
                majorDirection = direction,
                teachingLanguage = language,
                courseNature = nature,
                requiredCredits = required,
                completedCredits = completed,
                remainingCredits = remaining,
                completedHours = number(row, "ywcxs", "YWCXS")?.toInt(),
                includesMooc = creditedMoocCredits > 0.0 || earnedMoocCredits > 0.0,
                creditedMoocCredits = creditedMoocCredits,
                earnedMoocCredits = earnedMoocCredits,
                passed = remaining <= 0.0001 || completed + 0.0001 >= required
            )
        }.distinctBy { it.id }
    }

    internal fun parseGroups(
        body: String,
        groupCourseBodies: Map<String, String> = emptyMap()
    ): List<ShenzhenCreditGroupProgress> {
        val root = objectRoot(body) ?: return emptyList()
        val content = arrayAt(root, "content") ?: return emptyList()
        val result = mutableListOf<ShenzhenCreditGroupProgress>()

        fun visit(row: JsonObject, depth: Int) {
            val id = first(row, "kzid", "KZID")
            val name = first(row, "title", "TITLE", "kzmc", "KZMC")
            if (id.isNotBlank() && name.isNotBlank()) {
                val requiredCredits = number(row, "yqxdxf", "YQXDXF")
                val completedCredits = number(row, "wc_xf", "WC_XF") ?: 0.0
                val requiredCourses = number(row, "yqxdms", "YQXDMS")?.toInt()
                val completedCourses = number(row, "wc_ms", "WC_MS")?.toInt() ?: 0
                result += ShenzhenCreditGroupProgress(
                    id = id,
                    parentId = first(row, "fkzid", "FKZID"),
                    name = name,
                    depth = depth,
                    requiredCredits = requiredCredits,
                    completedCredits = completedCredits,
                    requiredCourses = requiredCourses,
                    completedCourses = completedCourses,
                    passed = boolean(row, "sftg", "SFTG")
                        ?: ((requiredCredits == null || completedCredits + 0.0001 >= requiredCredits) &&
                            (requiredCourses == null || completedCourses >= requiredCourses))
                )
            }
            arrayAt(row, "children", "CHILDREN")?.forEach { child ->
                child.takeIf { it.isJsonObject }?.asJsonObject?.let { visit(it, depth + 1) }
            }
        }

        content.forEach { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { visit(it, 0) }
        }
        return result.distinctBy { it.id }.map { group ->
            group.copy(courses = groupCourseBodies[group.id]?.let(::parseGroupCourses).orEmpty())
        }
    }

    private fun parseGroupCourses(body: String): List<ShenzhenCreditGroupCourse> {
        val root = objectRoot(body) ?: return emptyList()
        val content = objectAt(root, "content") ?: return emptyList()
        val rows = arrayAt(content, "list") ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val code = first(row, "kcdm", "KCDM")
            val name = first(row, "kcmc", "KCMC")
            if (code.isBlank() && name.isBlank()) return@mapNotNull null
            ShenzhenCreditGroupCourse(
                courseCode = code,
                courseName = name.ifBlank { code },
                credits = number(row, "xf", "XF") ?: 0.0,
                recommendedTerm = first(row, "tjkkxnxq", "TJKKXNXQ"),
                courseNature = first(row, "kcxzmc", "KCXZMC"),
                completed = first(row, "sfyxkc", "SFYXKC") == "1" ||
                    first(row, "zpcj", "ZPCJ", "xscj", "XSCJ").isNotBlank()
            )
        }.distinctBy { it.courseCode.ifBlank { it.courseName } }
    }

    private fun parseCourseRecords(bodies: List<String>): List<ShenzhenCreditCourseRecord> =
        bodies.flatMap { body ->
            val root = objectRoot(body) ?: return@flatMap emptyList()
            val content = objectAt(root, "content") ?: return@flatMap emptyList()
            val rows = arrayAt(content, "list") ?: return@flatMap emptyList()
            rows.mapNotNull { element ->
                val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val code = first(row, "kcdm", "KCDM")
                val name = first(row, "kcmc", "KCMC")
                if (code.isBlank() && name.isBlank()) return@mapNotNull null
                val term = first(row, "xnxq", "XNXQ", "xnxqx", "XNXQX")
                ShenzhenCreditCourseRecord(
                    id = first(row, "rwh", "RWH").ifBlank { "$term-$code-$name" },
                    term = term,
                    courseCode = code,
                    courseName = name.ifBlank { code },
                    credits = number(row, "xf", "XF") ?: 0.0,
                    score = first(row, "xscj", "XSCJ", "zpcj", "ZPCJ"),
                    teacher = first(row, "jsxm", "JSXM"),
                    courseNature = first(row, "kcxzmc", "KCXZMC"),
                    courseCategory = first(row, "kclbmc", "KCLBMC")
                )
            }
        }.distinctBy { it.id }
            .sortedWith(compareByDescending<ShenzhenCreditCourseRecord> { it.term }.thenBy { it.courseName })

    private fun parse(body: String): JsonElement? =
        runCatching { JsonParser().parse(body) }.getOrNull()

    private fun objectRoot(body: String): JsonObject? = parse(body)
        ?.takeIf { it.isJsonObject }?.asJsonObject

    private fun allObjects(root: JsonElement): List<JsonObject> = buildList {
        fun visit(element: JsonElement) {
            when {
                element.isJsonObject -> {
                    val value = element.asJsonObject
                    add(value)
                    value.entrySet().forEach { visit(it.value) }
                }
                element.isJsonArray -> element.asJsonArray.forEach(::visit)
            }
        }
        visit(root)
    }

    private fun objectAt(value: JsonObject?, vararg keys: String): JsonObject? {
        if (value == null) return null
        keys.forEach { key ->
            value.get(key)?.takeIf { it.isJsonObject }?.asJsonObject?.let { return it }
        }
        return null
    }

    private fun arrayAt(value: JsonObject, vararg keys: String): JsonArray? {
        keys.forEach { key ->
            value.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.let { return it }
        }
        return null
    }

    private fun first(value: JsonObject?, vararg keys: String): String {
        if (value == null) return ""
        keys.forEach { key ->
            val element = value.get(key) ?: return@forEach
            if (!element.isJsonPrimitive) return@forEach
            val text = runCatching { element.asString }.getOrNull()?.trim().orEmpty()
            if (text.isNotBlank() && !text.equals("null", true)) return text
        }
        return ""
    }

    private fun number(value: JsonObject?, vararg keys: String): Double? =
        first(value, *keys).toDoubleOrNull()

    private fun boolean(value: JsonObject, vararg keys: String): Boolean? =
        when (first(value, *keys).lowercase()) {
            "1", "true", "yes", "是" -> true
            "0", "false", "no", "否" -> false
            else -> null
        }

    private fun studentTypeCode(raw: String): String = when {
        raw == "1" || raw.contains("本科") -> "1"
        raw == "2" || raw.contains("研究生") -> "2"
        else -> raw
    }
}
