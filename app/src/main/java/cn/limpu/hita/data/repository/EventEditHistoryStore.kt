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
    fun delete(eventIds: List<String>): Boolean {
        val events = eventDao.getEventsInIdsIncludingHiddenSync(eventIds)
        if (events.isEmpty()) return false
        val (hide, remove) = events.partition { it.hidesInsteadOfDeleting() }
        if (hide.isNotEmpty()) eventDao.hideEventsInIdsSync(hide.map { it.id })
        if (remove.isNotEmpty()) eventDao.deleteEventsInIdsSync(remove.map { it.id })
        events.forEach { event ->
            append(event.timetableId, event.id, ACTION_DELETE, before = event, after = null)
        }
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

    fun restore(historyId: String): EventItem? {
        val entry = historyDao.getByIdSync(historyId) ?: return null
        val source = entry.beforeJson ?: entry.afterJson ?: return null
        val event = gson.fromJson(source, EventItem::class.java) ?: return null
        val existing = eventDao.getEventsInIdsIncludingHiddenSync(listOf(event.id)).firstOrNull()
        if (existing != null && existing.hidesInsteadOfDeleting()) {
            event.hidden = 0
            eventDao.updateEventSync(event)
        } else if (existing == null) {
            event.hidden = 0
            eventDao.insertEventSync(event)
        } else {
            event.hidden = 0
            eventDao.updateEventSync(event)
        }
        append(event.timetableId, event.id, ACTION_RESTORE, before = existing, after = event)
        return event
    }

    fun history(timetableId: String): List<EventEditHistoryEntity> =
        historyDao.historyOfTimetableSync(timetableId)

    private fun append(
        timetableId: String,
        eventId: String,
        action: String,
        before: EventItem?,
        after: EventItem?,
    ) {
        historyDao.insert(
            EventEditHistoryEntity(
                id = ids(),
                timetableId = timetableId,
                eventId = eventId,
                action = action,
                beforeJson = before?.let(gson::toJson),
                afterJson = after?.let(gson::toJson),
                createdAt = clock(),
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
