package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventEditHistoryEntity
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.source.dao.EventEditHistoryDao
import cn.limpu.hita.data.source.dao.EventItemDao
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import cn.limpu.hita.data.model.DateDeserializer
import cn.limpu.hita.data.model.DateSerializer
import java.sql.Timestamp
import java.util.UUID

enum class DeletedEventStatus { AVAILABLE, RESTORED, CHANGED, UNAVAILABLE }

data class DeletedEventHistory(
    val id: String,
    val name: String?,
    val startsAt: Long?,
    val deletedAt: Long,
    val status: DeletedEventStatus,
)

/** Records course edits and restores one history row without exposing hidden courses to readers. */
class EventEditHistoryStore(
    private val eventDao: EventItemDao,
    private val historyDao: EventEditHistoryDao,
    private val gson: Gson = eventCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    fun recordCreate(event: EventItem) = append(
        timetableId = event.timetableId,
        eventId = event.id,
        action = ACTION_CREATE,
        before = null,
        after = event,
    )

    fun recordUpdate(before: EventItem, after: EventItem) = append(
        timetableId = after.timetableId.ifBlank { before.timetableId },
        eventId = after.id,
        action = ACTION_UPDATE,
        before = before,
        after = after,
    )

    /** Teaching-source courses stay in the table; every other course is removed but remains recoverable. */
    fun delete(eventIds: List<String>, receiptId: String? = null): Boolean {
        val events = eventDao.getEventsInIdsIncludingHiddenSync(eventIds).filter { it.hidden == 0 }
        if (events.isEmpty()) return false
        events.forEach { event ->
            append(event.timetableId, event.id, ACTION_DELETE, before = event, after = null, receiptId = receiptId)
        }
        val (hide, remove) = events.partition { it.hidesInsteadOfDeleting() }
        if (hide.isNotEmpty()) eventDao.hideEventsInIdsSync(hide.map { it.id })
        if (remove.isNotEmpty()) eventDao.deleteEventsInIdsSync(remove.map { it.id })
        return true
    }

    /**
     * 删除整门课（课程组级）。
     *
     * 与单条删除同一规则：教务来源的课软隐藏、手动课物理删除，都会留下历史行。
     * 之所以按 `subjectId` 而不是 `groupId`：Android 的课表没有 iOS 的课程组概念，
     * 一门课在表里就是同一 `subjectId` 的全部课次，语义等价且不用升库版本。
     */
    fun deleteSubject(subjectId: String, timetableId: String): Boolean {
        val events = eventDao.getClassesOfSubjectSync(subjectId)
            .filter { it.timetableId == timetableId }
        if (events.isEmpty()) return false
        return delete(events.map { it.id })
    }

    /** Caller must wrap the check, event write and history update in one Room transaction. */
    fun restore(historyId: String, timetableId: String? = null): EventItem? {
        val entry = historyDao.getByIdSync(historyId) ?: return null
        if (timetableId != null && entry.timetableId != timetableId) return null
        val entries = history(entry.timetableId)
        val event = deletedEvent(entry) ?: return null
        val existing = eventDao.getEventsInIdsIncludingHiddenSync(listOf(event.id)).firstOrNull()
        if (status(entry, event, existing, entries) != DeletedEventStatus.AVAILABLE) return null
        event.hidden = 0
        if (existing == null) eventDao.insertEventSync(event) else eventDao.updateEventSync(event)
        check(historyDao.markDeletionRestoredSync(entry.id) == 1)
        append(event.timetableId, event.id, ACTION_RESTORE, before = existing, after = event)
        return event
    }

    fun deletedHistory(timetableId: String, personalOnly: Boolean = false): List<DeletedEventHistory> {
        val entries = history(timetableId)
        val deletions = entries.filter { it.action == ACTION_DELETE || it.action == ACTION_DELETE_RESTORED }
        val current = eventDao.getEventsInIdsIncludingHiddenSync(deletions.map { it.eventId })
            .associateBy { it.id }
        return deletions.mapNotNull { entry ->
            val event = deletedEvent(entry)
            if (personalOnly && (event == null || event.hidesInsteadOfDeleting())) return@mapNotNull null
            DeletedEventHistory(
                id = entry.id,
                name = event?.name,
                startsAt = event?.from?.time,
                deletedAt = entry.createdAt,
                status = status(entry, event, current[entry.eventId], entries),
            )
        }
    }

    private fun deletedEvent(entry: EventEditHistoryEntity): EventItem? = runCatching {
        if (entry.action != ACTION_DELETE && entry.action != ACTION_DELETE_RESTORED) return null
        val event = gson.fromJson(entry.beforeJson ?: return null, EventItem::class.java) ?: return null
        event.takeIf {
            it.id == entry.eventId && it.timetableId == entry.timetableId &&
                it.hidden == 0 && !it.name.isNullOrBlank() && it.from != null && it.to != null
        }
    }.getOrNull()

    private fun status(
        entry: EventEditHistoryEntity,
        event: EventItem?,
        existing: EventItem?,
        entries: List<EventEditHistoryEntity>,
    ): DeletedEventStatus {
        if (entry.action == ACTION_DELETE_RESTORED) return DeletedEventStatus.RESTORED
        if (event == null || entry.action != ACTION_DELETE) return DeletedEventStatus.UNAVAILABLE
        // Older releases did not mark the consumed deletion; later history still protects those rows.
        if (entries.any { it.eventId == entry.eventId && it.id != entry.id && it.createdAt >= entry.createdAt }) {
            return DeletedEventStatus.CHANGED
        }
        if (existing == null) return DeletedEventStatus.AVAILABLE
        if (existing.hidden != 1 || !existing.hidesInsteadOfDeleting()) return DeletedEventStatus.CHANGED
        fun storedContent(item: EventItem) = gson.toJsonTree(item).asJsonObject.apply {
            remove("hidden")
            remove("color") // Presentation-only; not stored in Room.
        }
        return if (storedContent(existing) == storedContent(event)) DeletedEventStatus.AVAILABLE
            else DeletedEventStatus.CHANGED
    }

    fun history(timetableId: String): List<EventEditHistoryEntity> =
        historyDao.historyOfTimetableSync(timetableId)

    private fun append(
        timetableId: String,
        eventId: String,
        action: String,
        before: EventItem?,
        after: EventItem?,
        receiptId: String? = null,
    ) {
        // Keep ordering unambiguous for a batch of deletions and for clocks moving backwards.
        val latest = history(timetableId).maxOfOrNull { it.createdAt }
        val recordedAt = maxOf(clock(), latest?.let { it + 1 } ?: Long.MIN_VALUE)
        historyDao.insert(
            EventEditHistoryEntity(
                id = receiptId ?: ids(),
                timetableId = timetableId,
                eventId = eventId,
                action = action,
                beforeJson = before?.let(gson::toJson),
                afterJson = after?.let(gson::toJson),
                createdAt = recordedAt,
            ),
        )
        val overflow = historyDao.countSync() - MAX_ENTRIES
        if (overflow > 0) historyDao.deleteOldestSync(overflow)
    }

    companion object {
        const val ACTION_CREATE = "CREATE"
        const val ACTION_UPDATE = "UPDATE"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_RESTORE = "RESTORE"
        const val ACTION_DELETE_RESTORED = "DELETE_RESTORED"
        const val MAX_ENTRIES = 200

        fun eventCodec(): Gson = GsonBuilder()
            .registerTypeAdapter(Timestamp::class.java, DateSerializer())
            .registerTypeAdapter(Timestamp::class.java, DateDeserializer())
            .create()
    }
}

internal fun EventItem.hidesInsteadOfDeleting(): Boolean =
    source == EventItem.SOURCE_EAS_IMPORT ||
        source.startsWith("${EventItem.SOURCE_FOLLOWED_SCHOOL}:") ||
        source.startsWith("${EventItem.SOURCE_COURSE_PLAN}:")
