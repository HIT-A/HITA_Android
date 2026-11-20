package cn.limpu.hita.data.source.web.geniusta

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.time.OffsetDateTime

internal class GeniusTaInvalidResponseException : Exception("Invalid GeniusTA response")

internal data class GeniusTaCourse(val id: String, val title: String, val sectionId: String?)
internal data class GeniusTaAssignment(val id: String, val title: String, val courseTitle: String, val dueAt: Long)
internal class GeniusTaLogin(val accountId: String, val sessionId: String, val expiresAt: Long)

internal fun isGeniusTaId(value: String): Boolean = value.length in 1..256 && value.all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
}

internal object GeniusTaParser {
    private val jsonAdapter = Gson().getAdapter(JsonElement::class.java)
    private val unsubmitted = setOf("not_submitted", "late_unsubmitted", "makeup_unsubmitted", "closed_unsubmitted")

    fun login(json: String): GeniusTaLogin = parse {
        val data = data(json).asJsonObject
        val session = data.getAsJsonObject("session")
        // Idle expiry can move with server activity; use the absolute expiry locally.
        val expiresAt = session.deadline("expires_at") ?: throw GeniusTaInvalidResponseException()
        GeniusTaLogin(student(data.getAsJsonObject("user")), session.id("id"), expiresAt)
    }

    fun accountId(json: String): String = parse { student(data(json).asJsonObject) }

    fun courses(json: String): List<GeniusTaCourse> = parse {
        val seen = mutableSetOf<String>()
        data(json).asJsonArray.mapNotNull { element ->
            val course = element.asJsonObject
            if (course.text("current_user_role") != "student") return@mapNotNull null
            val id = course.id("public_id")
            require(seen.add(id))
            GeniusTaCourse(id, course.text("name"), course.section("current_user_section_id"))
        }
    }

    fun assignments(json: String, course: GeniusTaCourse): List<GeniusTaAssignment> = parse {
        data(json).asJsonArray.mapNotNull { element ->
            val assignment = element.asJsonObject
            val released = assignment.get("is_released").asJsonPrimitive
            require(released.isBoolean)
            if (!released.asBoolean) return@mapNotNull null
            val submission = assignment.get("submission_state")
            if (submission == null || submission.isJsonNull) return@mapNotNull null
            val status = submission.asJsonObject.get("status")
            if (status == null || !status.isJsonPrimitive || !status.asJsonPrimitive.isString || status.asString !in unsubmitted) {
                return@mapNotNull null
            }
            val schedule = if (course.sectionId != null) {
                assignment.getAsJsonArray("section_schedules").singleOrNullMatchingSection(course.sectionId)
            } else {
                assignment.get("ungrouped_schedule")?.takeUnless { it.isJsonNull }?.asJsonObject
            }
            // An applicable override with an explicit null cancels the base deadline.
            val dueAt = (schedule ?: assignment).deadline("due_date") ?: return@mapNotNull null
            GeniusTaAssignment(assignment.id("public_id"), assignment.text("title"), course.title, dueAt)
        }
    }

    private fun Iterable<JsonElement>.singleOrNullMatchingSection(sectionId: String): JsonObject? {
        var match: JsonObject? = null
        for (element in this) {
            val schedule = element.asJsonObject
            if (schedule.section("section_id") == sectionId) {
                require(match == null)
                match = schedule
            }
        }
        return match
    }

    private fun student(user: JsonObject): String {
        if (user.text("role") != "student") throw GeniusTaAuthenticationException()
        return user.id("public_id")
    }

    private fun data(json: String): JsonElement = BoundedJsonReader(json).use { reader ->
        val root = jsonAdapter.read(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        root.asJsonObject.get("data") ?: throw GeniusTaInvalidResponseException()
    }

    private fun JsonObject.id(key: String): String = text(key).also { require(isGeniusTaId(it)) }

    private fun JsonObject.text(key: String): String = get(key).asJsonPrimitive.let {
        require(it.isString)
        it.asString.also { value -> require(value.isNotBlank()) }
    }

    private fun JsonObject.section(key: String): String? {
        val element = get(key) ?: throw GeniusTaInvalidResponseException()
        if (element.isJsonNull) return null
        return id(key)
    }

    private fun JsonObject.deadline(key: String): Long? {
        val element = get(key) ?: throw GeniusTaInvalidResponseException()
        return if (element.isJsonNull) null else OffsetDateTime.parse(text(key)).toInstant().toEpochMilli()
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (_: IOException) {
        throw GeniusTaInvalidResponseException()
    } catch (_: RuntimeException) {
        throw GeniusTaInvalidResponseException()
    }

    /** The Gson tree reader is recursive; cap nesting independently of the HTTP byte limit. */
    private class BoundedJsonReader(json: String) : JsonReader(StringReader(json)) {
        private var depth = 0

        override fun beginArray() {
            require(++depth <= 64)
            super.beginArray()
        }

        override fun endArray() {
            super.endArray()
            depth--
        }

        override fun beginObject() {
            require(++depth <= 64)
            super.beginObject()
        }

        override fun endObject() {
            super.endObject()
            depth--
        }
    }
}
