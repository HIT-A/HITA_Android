package cn.limpu.hita.data.repository.geniusta

import cn.limpu.hita.data.model.homework.ImportedTodo
import cn.limpu.hita.data.source.web.geniusta.GeniusTaAssignment
import cn.limpu.hita.data.source.web.geniusta.GeniusTaConnection
import cn.limpu.hita.data.source.web.geniusta.GeniusTaSession
import cn.limpu.hita.data.source.web.geniusta.isGeniusTaId
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException

internal class GeniusTaStorageException : Exception("GeniusTA storage unavailable")

internal data class GeniusTaSnapshot(
    val accountId: String? = null,
    val session: GeniusTaSession? = null,
    val cache: Map<String, GeniusTaAccountCache> = emptyMap(),
) {
    val tasks: List<ImportedTodo> = accountId?.let(cache::get)?.let { active ->
        active.assignments.map { assignment ->
            ImportedTodo(assignment.id, assignment.title, assignment.courseTitle, assignment.dueAt, assignment.id in active.completed)
        }
    }.orEmpty()
}

internal data class GeniusTaAccountCache(
    val assignments: List<GeniusTaAssignment> = emptyList(),
    val completed: Set<String> = emptySet(),
)

/** Persistence and projection are JVM-testable; production read/commit use encrypted preferences. */
internal class GeniusTaStore(private val read: () -> String?, private val commit: (String) -> Boolean) {
    private var current: GeniusTaSnapshot? = null

    fun load(): GeniusTaSnapshot = storage {
        current ?: (read()?.let(::decode) ?: GeniusTaSnapshot()).also { current = it }
    }

    fun replace(connection: GeniusTaConnection, assignments: List<GeniusTaAssignment>): GeniusTaSnapshot {
        val before = load()
        val cache = before.cache[connection.accountId] ?: GeniusTaAccountCache()
        return save(GeniusTaSnapshot(
            connection.accountId,
            connection.session,
            before.cache + (connection.accountId to cache.copy(assignments = assignments)),
        ))
    }

    fun setCompleted(accountId: String, id: String, completed: Boolean): GeniusTaSnapshot? {
        val before = load()
        if (accountId != before.accountId) return null
        val cache = before.cache[accountId] ?: return null
        if (cache.assignments.none { it.id == id }) return null
        if ((id in cache.completed) == completed) return before
        val next = cache.copy(completed = if (completed) cache.completed + id else cache.completed - id)
        return save(before.copy(cache = before.cache + (accountId to next)))
    }

    fun disconnect(): GeniusTaSnapshot {
        val before = load()
        return if (before.accountId == null) before else save(before.copy(accountId = null, session = null))
    }

    fun expireSession(): GeniusTaSnapshot {
        val before = load()
        return if (before.session == null) before else save(before.copy(session = null))
    }

    fun activateCached(accountId: String): GeniusTaSnapshot {
        val before = load()
        check(before.accountId == null)
        return if (accountId in before.cache) save(before.copy(accountId = accountId)) else before
    }

    private fun save(next: GeniusTaSnapshot): GeniusTaSnapshot = storage {
        if (!commit(encode(next).toString())) throw GeniusTaStorageException()
        current = next
        next
    }

    private fun encode(snapshot: GeniusTaSnapshot) = JsonObject().apply {
        snapshot.accountId?.let { addProperty("account_id", it) }
        snapshot.session?.let { session ->
            add("session", JsonObject().apply {
                addProperty("id", session.id)
                addProperty("cookie", session.cookieValue)
                addProperty("expires_at", session.expiresAt)
            })
        }
        add("cache", JsonObject().apply {
            snapshot.cache.forEach { (accountId, cache) ->
                add(accountId, JsonObject().apply {
                    add("assignments", JsonArray().apply {
                        cache.assignments.forEach { assignment ->
                            add(JsonObject().apply {
                                addProperty("id", assignment.id)
                                addProperty("title", assignment.title)
                                addProperty("course_title", assignment.courseTitle)
                                addProperty("due_at", assignment.dueAt)
                            })
                        }
                    })
                    add("completed", JsonArray().apply { cache.completed.forEach { add(it) } })
                })
            }
        })
    }

    private fun decode(encoded: String): GeniusTaSnapshot {
        val root = JsonParser().parse(encoded).asJsonObject
        val accountId = if (root.has("account_id")) root.id("account_id") else null
        val session = root.getAsJsonObject("session")?.let {
            GeniusTaSession(it.id("id"), it.text("cookie"), it.timestamp("expires_at"))
        }
        val cache = root.getAsJsonObject("cache").entrySet().associate { (id, value) ->
            require(isGeniusTaId(id))
            val row = value.asJsonObject
            val assignments = row.getAsJsonArray("assignments").map { element ->
                val assignment = element.asJsonObject
                GeniusTaAssignment(assignment.id("id"), assignment.text("title"), assignment.text("course_title"), assignment.timestamp("due_at"))
            }
            val completed = row.getAsJsonArray("completed").mapTo(mutableSetOf()) { element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
                element.asString.also { require(isGeniusTaId(it)) }
            }
            id to GeniusTaAccountCache(assignments, completed)
        }
        require(accountId == null || accountId in cache)
        require(session == null || accountId != null)
        return GeniusTaSnapshot(accountId, session, cache)
    }

    private fun JsonObject.id(key: String): String = text(key).also { require(isGeniusTaId(it)) }
    private fun JsonObject.text(key: String): String = get(key).asJsonPrimitive.let {
        require(it.isString)
        it.asString.also { text -> require(text.isNotBlank()) }
    }

    private fun JsonObject.timestamp(key: String): Long = get(key).asJsonPrimitive.let {
        require(it.isNumber)
        it.asString.toLong()
    }

    private inline fun <T> storage(action: () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GeniusTaStorageException()
    }
}
