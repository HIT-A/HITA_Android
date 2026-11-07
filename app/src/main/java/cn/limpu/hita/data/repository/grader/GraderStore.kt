package cn.limpu.hita.data.repository.grader

import cn.limpu.hita.data.model.grader.GraderTask
import cn.limpu.hita.data.source.web.grader.GraderAccount
import cn.limpu.hita.data.source.web.grader.GraderAssignment
import cn.limpu.hita.data.source.web.grader.GraderParser
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal class GraderStorageException : Exception("Grader storage unavailable")

internal data class GraderSnapshot(
    val account: GraderAccount? = null,
    val cookie: String? = null,
    val assignmentsByAccount: Map<String, List<GraderAssignment>> = emptyMap(),
    val completedByAccount: Map<String, Set<String>> = emptyMap(),
) {
    val assignments: List<GraderAssignment> get() = account?.let { assignmentsByAccount[it.id] }.orEmpty()

    fun visibleTasks(now: Long): List<GraderTask> = if (account == null) emptyList() else
        GraderParser.visibleTasks(assignments, completedByAccount[account.id].orEmpty(), now)
}

/** Pure JVM persistence seam. Callers supply encrypted durable read/commit on Android. */
internal class GraderStore(private val read: () -> String?, private val write: (String) -> Boolean) {
    private var snapshot: GraderSnapshot? = null

    @Synchronized
    fun load(): GraderSnapshot = storage {
        snapshot ?: (read()?.let(::decode) ?: GraderSnapshot()).also { snapshot = it }
    }

    @Synchronized
    fun replace(account: GraderAccount, cookie: String, assignments: List<GraderAssignment>): GraderSnapshot {
        val current = load()
        if (GraderSessionPolicy.sessionCookie(cookie) != cookie) throw GraderStorageException()
        return save(current.copy(
            account = account,
            cookie = cookie,
            assignmentsByAccount = current.assignmentsByAccount + (account.id to GraderParser.unique(assignments)),
        ))
    }

    @Synchronized
    fun setCompleted(accountId: String, id: String, completed: Boolean): GraderSnapshot {
        val current = load()
        if (current.account?.id != accountId || current.assignments.none { it.id == id }) return current
        val previous = current.completedByAccount[accountId].orEmpty()
        val next = if (completed) previous + id else previous - id
        if (previous == next) return current
        return save(current.copy(completedByAccount = current.completedByAccount + (accountId to next)))
    }

    @Synchronized
    fun disconnect(): GraderSnapshot = save(load().copy(account = null, cookie = null))

    private fun save(next: GraderSnapshot): GraderSnapshot = storage {
        if (!write(encode(next))) throw GraderStorageException()
        snapshot = next
        next
    }

    private fun encode(value: GraderSnapshot): String {
        val root = JsonObject()
        root.addProperty("version", 1)
        value.account?.let {
            val account = JsonObject()
            account.addProperty("id", it.id)
            account.addProperty("name", it.name)
            account.addProperty("cookie", value.cookie)
            root.add("account", account)
        }
        val caches = JsonObject()
        value.assignmentsByAccount.forEach { (accountId, items) ->
            val assignments = JsonArray()
            items.forEach { item ->
                val row = JsonObject()
                row.addProperty("id", item.id)
                row.addProperty("title", item.title)
                row.addProperty("courseTitle", item.courseTitle)
                item.dueAt?.let { row.addProperty("dueAt", it) }
                row.addProperty("published", item.published)
                assignments.add(row)
            }
            caches.add(accountId, assignments)
        }
        root.add("cache", caches)
        val completed = JsonObject()
        value.completedByAccount.forEach { (accountId, ids) ->
            val entries = JsonArray()
            ids.sorted().forEach { entries.add(it) }
            completed.add(accountId, entries)
        }
        root.add("completed", completed)
        return root.toString()
    }

    private fun decode(raw: String): GraderSnapshot {
        val root = JsonParser().parse(raw).asJsonObject
        require(root.get("version").asInt == 1)
        val accountObject = root.getAsJsonObject("account")
        val account = accountObject?.let { GraderAccount(it.string("id").also(::requireId), it.string("name")) }
        val cookie = accountObject?.string("cookie")
        require(cookie == null || GraderSessionPolicy.sessionCookie(cookie) == cookie)
        val caches = root.getAsJsonObject("cache").entrySet().associate { (accountId, rows) ->
            requireId(accountId)
            val assignments = rows.asJsonArray.map {
                val row = it.asJsonObject
                val published = row.get("published").asJsonPrimitive
                require(published.isBoolean)
                val due = row.get("dueAt")?.let { time ->
                    require(time.isJsonPrimitive && time.asJsonPrimitive.isNumber)
                    time.asBigDecimal.longValueExact()
                }
                GraderAssignment(row.string("id").also(::requireId), row.string("title"), row.string("courseTitle"), due, published.asBoolean)
            }
            accountId to GraderParser.unique(assignments)
        }
        require(account == null || caches.containsKey(account.id))
        val completed = root.getAsJsonObject("completed").entrySet().associate { (accountId, ids) ->
            requireId(accountId)
            accountId to ids.asJsonArray.map { id ->
                require(id.isJsonPrimitive && id.asJsonPrimitive.isString)
                id.asString.also(::requireId)
            }.toSet()
        }
        return GraderSnapshot(account, cookie, caches, completed)
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key).asJsonPrimitive
        require(value.isString && value.asString.isNotBlank())
        return value.asString
    }

    private fun requireId(value: String) {
        require(value.isNotEmpty() && value[0] in '1'..'9' && value.all { it in '0'..'9' })
    }

    private inline fun <T> storage(action: () -> T): T = try {
        action()
    } catch (error: GraderStorageException) {
        throw error
    } catch (_: Exception) {
        throw GraderStorageException()
    }
}
