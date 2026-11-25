package cn.limpu.hita.data.source.web.grader

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.OffsetDateTime

internal data class GraderAccount(val id: String, val name: String)
internal data class GraderCourse(val id: String, val title: String)

/** Unfiltered cache: unpublished, expired and missing-deadline rows are retained. */
internal data class GraderAssignment(
    val id: String,
    val title: String,
    val courseTitle: String,
    val dueAt: Long?,
    val published: Boolean,
    /** 作业说明；列表接口可能不返回，提交前按需拉详情补齐。 */
    val description: String? = null,
    /** 单文件大小上限（字节），教师逐作业设定。 */
    val maxFileSize: Long? = null,
    /** 允许的扩展名（小写、无点）；为空表示不限制。 */
    val allowedExtensions: List<String> = emptyList(),
    /** 服务端是否允许提交（过期、未发布或已关闭时为 false）。 */
    val submitAllowed: Boolean? = null,
)

internal class GraderInvalidResponseException(detail: String? = null) :
    Exception("Invalid Grader response${detail?.let { ": $it" }.orEmpty()}")

/** 提交前展示与校验所需的作业详情。 */
internal data class GraderAssignmentDetail(
    val id: String,
    val title: String,
    val description: String?,
    val maxFileSize: Long?,
    val allowedExtensions: List<String>,
    val submitAllowed: Boolean?,
)

/** 服务端签发的上传目标：地址、随附表单字段与后续步骤用到的 ident。 */
internal data class GraderUploadTarget(
    val url: String,
    val params: Map<String, String>,
    val ident: String,
)

/** Pure JVM entry points; no Android, clock, network or credential dependencies. */
internal object GraderParser {
    fun account(json: String): GraderAccount = parse {
        val value = JsonParser().parse(json).asJsonObject
        val username = value.requiredString("username")
        GraderAccount(value.requiredId("id"), value.optionalString("real_name")?.takeIf { it.isNotBlank() } ?: username)
    }

    fun courses(json: String): List<GraderCourse> = parse {
        val values = JsonParser().parse(json).asJsonArray.map {
            val value = it.asJsonObject
            GraderCourse(value.requiredId("id"), value.requiredString("title"))
        }
        if (values.map { it.id }.distinct().size != values.size) throw GraderInvalidResponseException()
        values
    }

    fun assignments(json: String, course: GraderCourse): List<GraderAssignment> = parse {
        JsonParser().parse(json).asJsonArray.map {
            val value = it.asJsonObject
            if (value.requiredId("course") != course.id) throw GraderInvalidResponseException()
            val published = value.get("published")
            if (published == null || !published.isJsonPrimitive || !published.asJsonPrimitive.isBoolean) {
                throw GraderInvalidResponseException()
            }
            val due = value.optionalString("due_time")?.let { time ->
                try {
                    OffsetDateTime.parse(time).toInstant().toEpochMilli()
                } catch (_: java.time.DateTimeException) {
                    null
                } catch (_: ArithmeticException) {
                    null
                }
            }
            GraderAssignment(
                id = value.requiredId("id"),
                title = value.requiredString("title"),
                courseTitle = course.title,
                dueAt = due,
                published = published.asBoolean,
                description = value.optionalString("description")?.takeIf { it.isNotBlank() },
                maxFileSize = value.optionalLong("max_file_size"),
                allowedExtensions = value.optionalExtensions("allowed_extension"),
                submitAllowed = value.optionalBoolean("submit_allowed"),
            )
        }
    }

    /**
     * `GET /api/assignments/{id}/` 的详情。
     *
     * 列表接口不一定带说明与提交限制，提交前用它补齐；标题缺失时回落到列表里的标题。
     */
    fun assignmentDetail(json: String, fallbackTitle: String): GraderAssignmentDetail = parse {
        val value = JsonParser().parse(json).asJsonObject
        GraderAssignmentDetail(
            id = value.requiredId("id"),
            title = value.optionalString("title")?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            description = value.optionalString("description")?.takeIf { it.isNotBlank() },
            maxFileSize = value.optionalLong("max_file_size"),
            allowedExtensions = value.optionalExtensions("allowed_extension"),
            submitAllowed = value.optionalBoolean("submit_allowed"),
        )
    }

    /**
     * `POST /api/files/touch/` 的应答：签名上传目标。
     *
     * 只做结构校验；目标地址是否可信由 [GraderUploadPolicy] 判定，解析层不碰网络策略。
     */
    fun uploadTarget(json: String): GraderUploadTarget = try {
        parseUploadTarget(json)
    } catch (error: GraderInvalidResponseException) {
        throw GraderInvalidResponseException("upload target ${json.take(300)}")
    }

    private fun parseUploadTarget(json: String): GraderUploadTarget = parse {
        val value = JsonParser().parse(json).asJsonObject
        val url = value.requiredString("url")
        val ident = value.get("ident")?.let { element ->
            if (element.isJsonNull) return@let null
            if (!element.isJsonPrimitive) throw GraderInvalidResponseException()
            element.asString.takeIf { it.isNotBlank() }
        } ?: throw GraderInvalidResponseException()
        val params = value.get("params")?.let { element ->
            when {
                element.isJsonNull -> emptyMap()
                !element.isJsonObject -> throw GraderInvalidResponseException()
                // 表单字段可能是数字或布尔；对象/数组不是合法表单值。
                else -> element.asJsonObject.entrySet().mapNotNull { (key, value) ->
                    when {
                        value.isJsonNull -> null
                        value.isJsonPrimitive -> key to value.asString
                        else -> throw GraderInvalidResponseException()
                    }
                }.toMap()
            }
        } ?: emptyMap()
        GraderUploadTarget(url, params, ident)
    }

    /**
     * `/api/submissions/` 返回当前账号的提交记录数组，`assignment` 字段是作业 id。
     * 提交过即视为该作业已完成——assignment 自身的 `submit_count` 恒为 null，不能据此判断。
     */
    fun submittedAssignmentIds(json: String): Set<String> = parse {
        JsonParser().parse(json).asJsonArray.mapTo(linkedSetOf()) {
            val assignment = it.asJsonObject.get("assignment")
            if (assignment == null || !assignment.isJsonPrimitive || !assignment.asJsonPrimitive.isNumber) {
                throw GraderInvalidResponseException()
            }
            val text = assignment.asString
            if (text.isEmpty() || text[0] !in '1'..'9' || text.any { it !in '0'..'9' }) {
                throw GraderInvalidResponseException()
            }
            text
        }
    }

    fun unique(assignments: List<GraderAssignment>): List<GraderAssignment> {
        val result = linkedMapOf<String, GraderAssignment>()
        assignments.forEach { assignment ->
            val previous = result.put(assignment.id, assignment)
            if (previous != null && previous != assignment) throw GraderInvalidResponseException()
        }
        return result.values.toList()
    }

    private fun JsonObject.requiredId(key: String): String {
        val value = get(key) ?: throw GraderInvalidResponseException()
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw GraderInvalidResponseException()
        val text = value.asString
        if (text.isEmpty() || text[0] !in '1'..'9' || text.any { it !in '0'..'9' }) throw GraderInvalidResponseException()
        return text
    }

    private fun JsonObject.requiredString(key: String): String =
        optionalString(key)?.takeIf { it.isNotBlank() } ?: throw GraderInvalidResponseException()

    private fun JsonObject.optionalString(key: String): String? {
        val value: JsonElement = get(key) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw GraderInvalidResponseException()
        return value.asString
    }

    private fun JsonObject.optionalLong(key: String): Long? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw GraderInvalidResponseException()
        return value.asLong
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw GraderInvalidResponseException()
        return value.asBoolean
    }

    /**
     * `allowed_extension` 线上是逗号分隔字符串（编辑页默认 `pdf`），但客户端也对数组形式容错。
     * 统一规范成小写、无前导点的形式，便于与文件名直接比对。
     */
    private fun JsonObject.optionalExtensions(key: String): List<String> {
        val value = get(key) ?: return emptyList()
        if (value.isJsonNull) return emptyList()
        val raw = when {
            value.isJsonArray -> value.asJsonArray.map {
                if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw GraderInvalidResponseException()
                it.asString
            }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.split(',', ' ', ';')
            else -> return emptyList()
        }
        return raw.map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (error: GraderInvalidResponseException) {
        throw error
    } catch (_: RuntimeException) {
        throw GraderInvalidResponseException()
    }
}
