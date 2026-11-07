package cn.limpu.hita.data.source.web.grader

import cn.limpu.hita.data.model.grader.GraderTask
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
)

internal class GraderInvalidResponseException : Exception("Invalid Grader response")

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
            GraderAssignment(value.requiredId("id"), value.requiredString("title"), course.title, due, published.asBoolean)
        }
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

    /**
     * 只保留已发布、未过截止、且尚未完成的作业。
     * [completedIds] 是本地手动完成与远端已提交作业 id 的并集；任一来源标记完成后不再显示。
     */
    fun visibleTasks(
        assignments: List<GraderAssignment>,
        completedIds: Set<String>,
        now: Long,
    ): List<GraderTask> = unique(assignments).mapNotNull { item ->
        val dueAt = item.dueAt ?: return@mapNotNull null
        if (!item.published || dueAt < now) return@mapNotNull null
        if (item.id in completedIds) return@mapNotNull null
        GraderTask(item.id, item.title, item.courseTitle, dueAt, completed = false)
    }.sortedWith(compareBy<GraderTask> { it.dueAt }.thenBy { it.id })

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

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (error: GraderInvalidResponseException) {
        throw error
    } catch (_: RuntimeException) {
        throw GraderInvalidResponseException()
    }
}
