package cn.limpu.hita.data.repository.grader

import cn.limpu.hita.data.model.grader.GraderSummaryItem
import cn.limpu.hita.data.model.homework.ImportedTodo
import cn.limpu.hita.data.source.web.grader.GraderAccount
import cn.limpu.hita.data.source.web.grader.GraderAssignment
import cn.limpu.hita.data.source.web.grader.GraderParser
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException

internal class GraderStorageException : Exception("Grader storage unavailable")

internal data class GraderSnapshot(
    val account: GraderAccount? = null,
    val cookie: String? = null,
    /** 该会话是从哪个入口建立的：直连还是 aTrust 网关。刷新必须原路返回。 */
    val entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT,
    val assignmentsByAccount: Map<String, List<GraderAssignment>> = emptyMap(),
    val completedByAccount: Map<String, Set<String>> = emptyMap(),
    /** 远端已提交的 ID，按账号隔离；待办页不显示这些记录。 */
    val submittedByAccount: Map<String, Set<String>> = emptyMap(),
    val disconnected: Boolean = false,
) {
    val activeAccount: GraderAccount? get() = account.takeUnless { disconnected }
    val assignments: List<GraderAssignment> get() = activeAccount?.let { assignmentsByAccount[it.id] }.orEmpty()

    fun summaryItems(): List<GraderSummaryItem> {
        val id = activeAccount?.id ?: return emptyList()
        val completed = completedByAccount[id].orEmpty() + submittedByAccount[id].orEmpty()
        return assignments.filter { it.published }.map {
            GraderSummaryItem(id, it.id, it.title, it.courseTitle, it.dueAt, it.id in completed)
        }
    }

    fun todoTasks(): List<ImportedTodo> {
        val id = activeAccount?.id ?: return emptyList()
        val completed = completedByAccount[id].orEmpty()
        val submitted = submittedByAccount[id].orEmpty()
        return assignments.mapNotNull { assignment ->
            val dueAt = assignment.dueAt ?: return@mapNotNull null
            if (!assignment.published) return@mapNotNull null
            // 已提交的作业留在列表里并标记为完成：重交入口就挂在这一行上。
            val isSubmitted = assignment.id in submitted
            ImportedTodo(
                assignment.id,
                assignment.title,
                assignment.courseTitle,
                dueAt,
                assignment.id in completed || isSubmitted,
                isSubmitted,
            )
        }
    }
}

/** Pure JVM persistence seam. Callers supply encrypted durable read/commit on Android. */
internal class GraderStore(private val read: () -> String?, private val write: (String) -> Boolean) {
    private var snapshot: GraderSnapshot? = null

    @Synchronized
    fun load(): GraderSnapshot = storage {
        snapshot ?: (read()?.let(::decode) ?: GraderSnapshot()).also { snapshot = it }
    }

    @Synchronized
    fun replace(
        account: GraderAccount,
        cookie: String,
        assignments: List<GraderAssignment>,
        submitted: Set<String> = emptySet(),
        entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT,
    ): GraderSnapshot {
        val current = load()
        if (GraderSessionPolicy.cookieHeader(entry, cookie) != cookie) throw GraderStorageException()
        return save(current.copy(
            account = account,
            cookie = cookie,
            entry = entry,
            assignmentsByAccount = current.assignmentsByAccount + (account.id to GraderParser.unique(assignments)),
            submittedByAccount = current.submittedByAccount + (account.id to submitted),
            disconnected = false,
        ))
    }

    @Synchronized
    fun setCompleted(accountId: String, id: String, completed: Boolean): GraderSnapshot? {
        val current = load()
        if (
            current.activeAccount?.id != accountId ||
            id in current.submittedByAccount[accountId].orEmpty() ||
            current.assignments.none { it.id == id && it.published && it.dueAt != null }
        ) return null
        val previous = current.completedByAccount[accountId].orEmpty()
        val next = if (completed) previous + id else previous - id
        if (previous == next) return current
        return save(current.copy(completedByAccount = current.completedByAccount + (accountId to next)))
    }

    /** 提交成功后立刻记入远端提交集合，界面不必等下一次完整刷新。 */
    @Synchronized
    fun markSubmitted(accountId: String, id: String): GraderSnapshot? {
        val current = load()
        if (current.activeAccount?.id != accountId) return null
        if (current.assignments.none { it.id == id }) return null
        val previous = current.submittedByAccount[accountId].orEmpty()
        if (id in previous) return current
        return save(current.copy(submittedByAccount = current.submittedByAccount + (accountId to previous + id)))
    }

    @Synchronized
    fun disconnect(): GraderSnapshot {
        val current = load()
        return if (current.disconnected) current else save(current.copy(disconnected = true))
    }

    /** A login attempt can retire an active session, but may not discard a deliberately parked one. */
    @Synchronized
    fun clearForReplacement(): GraderSnapshot {
        val current = load()
        return if (current.activeAccount == null) current else save(current.copy(account = null, cookie = null))
    }

    private fun save(next: GraderSnapshot): GraderSnapshot = storage {
        if (!write(encode(next))) throw GraderStorageException()
        snapshot = next
        next
    }

    private fun encode(value: GraderSnapshot): String {
        val root = JsonObject()
        root.addProperty("version", 1)
        if (value.disconnected) root.addProperty("disconnected", true)
        value.account?.let {
            val account = JsonObject()
            account.addProperty("id", it.id)
            account.addProperty("name", it.name)
            account.addProperty("cookie", value.cookie)
            account.addProperty("entry", value.entry.name)
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
                item.description?.let { row.addProperty("description", it) }
                item.maxFileSize?.let { row.addProperty("maxFileSize", it) }
                item.submitAllowed?.let { row.addProperty("submitAllowed", it) }
                if (item.allowedExtensions.isNotEmpty()) {
                    val extensions = JsonArray()
                    item.allowedExtensions.forEach { extensions.add(it) }
                    row.add("allowedExtensions", extensions)
                }
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
        val submitted = JsonObject()
        value.submittedByAccount.forEach { (accountId, ids) ->
            val entries = JsonArray()
            ids.sorted().forEach { entries.add(it) }
            submitted.add(accountId, entries)
        }
        root.add("submitted", submitted)
        return root.toString()
    }

    private fun decode(raw: String): GraderSnapshot {
        val root = JsonParser().parse(raw).asJsonObject
        require(root.get("version").asInt == 1)
        val disconnected = root.get("disconnected")?.asJsonPrimitive?.let {
            require(it.isBoolean)
            it.asBoolean
        } ?: false
        val accountObject = root.getAsJsonObject("account")
        val account = accountObject?.let { GraderAccount(it.string("id").also(::requireId), it.string("name")) }
        // 旧版本快照没有 entry 字段；那时只有直连，按直连读。
        val entry = accountObject?.optionalString("entry")?.let { name ->
            requireNotNull(GraderSessionPolicy.Entry.entries.firstOrNull { it.name == name })
        } ?: GraderSessionPolicy.Entry.DIRECT
        val cookie = accountObject?.string("cookie")
        require(cookie == null || GraderSessionPolicy.cookieHeader(entry, cookie) == cookie)
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
                GraderAssignment(
                    id = row.string("id").also(::requireId),
                    title = row.string("title"),
                    courseTitle = row.string("courseTitle"),
                    dueAt = due,
                    published = published.asBoolean,
                    description = row.optionalString("description"),
                    maxFileSize = row.optionalLong("maxFileSize"),
                    allowedExtensions = row.optionalStringArray("allowedExtensions"),
                    submitAllowed = row.optionalBoolean("submitAllowed"),
                )
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
        // 旧版本快照没有 submitted 字段，按空集合读取。
        val submitted = root.getAsJsonObject("submitted")?.entrySet()?.associate { (accountId, ids) ->
            requireId(accountId)
            accountId to ids.asJsonArray.map { id ->
                require(id.isJsonPrimitive && id.asJsonPrimitive.isString)
                id.asString.also(::requireId)
            }.toSet()
        }.orEmpty()
        return GraderSnapshot(account, cookie, entry, caches, completed, submitted, disconnected)
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key).asJsonPrimitive
        require(value.isString && value.asString.isNotBlank())
        return value.asString
    }

    private fun JsonObject.optionalString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asJsonPrimitive?.takeIf { it.isString && it.asString.isNotBlank() }?.asString

    private fun JsonObject.optionalLong(key: String): Long? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.longValueExact()
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }

    private fun JsonObject.optionalStringArray(key: String): List<String> {
        val value = get(key) ?: return emptyList()
        if (value.isJsonNull) return emptyList()
        require(value.isJsonArray)
        return value.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank())
            it.asString
        }
    }

    private fun requireId(value: String) {
        require(value.isNotEmpty() && value[0] in '1'..'9' && value.all { it in '0'..'9' })
    }

    private inline fun <T> storage(action: () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: GraderStorageException) {
        throw error
    } catch (_: Exception) {
        throw GraderStorageException()
    }
}
