package cn.limpu.hita.data.repository.homework

import cn.limpu.hita.data.model.homework.HomeworkItem
import com.google.gson.Gson
import java.io.IOException
import java.util.UUID
import java.time.LocalDate

/** The snapshot is replaced only after durable persistence succeeds. */
internal class HomeworkStore(
    private val read: () -> String?,
    private val write: (String) -> Boolean,
) {
    private val gson = Gson()
    private var snapshot: List<HomeworkItem>? = null

    @Synchronized
    fun load(): List<HomeworkItem> {
        snapshot?.let { return it }
        val raw = read()
        val items = if (raw == null) emptyList() else {
            require(raw.length <= MAX_SNAPSHOT_LENGTH) { "Homework snapshot too large" }
            val decoded = gson.fromJson(raw, Array<HomeworkItem?>::class.java)
                ?: throw IOException("Invalid homework snapshot")
            require(decoded.size <= MAX_ITEMS) { "Too many homework items" }
            decoded.map { item ->
                requireNotNull(item) { "Null homework item" }
                require(!item.id.isNullOrBlank() && !item.title.isNullOrBlank()) { "Invalid homework item" }
                require(item.title.length <= MAX_TITLE_LENGTH && item.createdAt >= 0L) { "Invalid homework content" }
                validateDate(item.plannedDate)
                item
            }.also { list ->
                require(list.map { it.id }.distinct().size == list.size) { "Duplicate homework ids" }
            }
        }
        snapshot = items.toList()
        return snapshot.orEmpty()
    }

    @Synchronized
    fun add(title: String, now: Long = System.currentTimeMillis(), plannedDate: String? = null): List<HomeworkItem> {
        val normalized = title.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_TITLE_LENGTH) { "Invalid homework title" }
        require(now >= 0L)
        validateDate(plannedDate)
        val current = load()
        require(current.size < MAX_ITEMS) { "Homework storage is full" }
        return save(current + HomeworkItem(UUID.randomUUID().toString(), normalized, now, plannedDate = plannedDate))
    }

    @Synchronized
    fun setCompleted(id: String, completed: Boolean): List<HomeworkItem> {
        val current = load()
        if (current.none { it.id == id && it.completed != completed }) return current
        return save(current.map { if (it.id == id) it.copy(completed = completed) else it })
    }

    @Synchronized
    fun clearCompleted(): List<HomeworkItem> = save(load().filterNot { it.completed })

    @Synchronized
    fun edit(id: String, title: String, plannedDate: String?): List<HomeworkItem> {
        val normalized = title.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_TITLE_LENGTH)
        validateDate(plannedDate)
        val current = load()
        require(current.any { it.id == id }) { "Homework no longer exists" }
        return save(current.map {
            if (it.id == id) it.copy(title = normalized, plannedDate = plannedDate) else it
        })
    }

    @Synchronized
    fun delete(id: String): List<HomeworkItem> {
        val current = load()
        if (current.none { it.id == id }) return current
        return save(current.filterNot { it.id == id })
    }

    private fun validateDate(date: String?) {
        if (date == null) return
        val parsed = LocalDate.parse(date)
        require(parsed.year in 1..9999 && parsed.toString() == date) { "Invalid planned date" }
    }
    private fun save(items: List<HomeworkItem>): List<HomeworkItem> {
        if (!write(gson.toJson(items))) throw IOException("Homework persistence failed")
        snapshot = items.toList()
        return snapshot.orEmpty()
    }

    companion object {
        const val MAX_TITLE_LENGTH = 200
        private const val MAX_ITEMS = 500
        private const val MAX_SNAPSHOT_LENGTH = 1_000_000
    }
}
