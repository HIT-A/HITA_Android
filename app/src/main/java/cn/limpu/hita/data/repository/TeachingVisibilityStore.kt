package cn.limpu.hita.data.repository

import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TeachingVisibilityEntity
import cn.limpu.hita.data.model.timetable.WEEK_MILLS
import cn.limpu.hita.feature.timetable.version.TimetableVersionDiff
import cn.limpu.hita.feature.timetable.version.VersionLesson
import java.util.UUID

/** Caller owns the course transaction. This preference is deliberately outside version payloads. */
internal class TeachingVisibilityStore(private val db: AppDatabase) {
    private val dao = db.teachingVisibilityDao()
    private fun key(e: EventItem) = listOf(e.timetableId, e.subjectId, e.from.time, e.to.time,
        e.fromNumber, e.lastNumber).joinToString("|")

    fun rememberExisting(events: List<EventItem>) {
        events.filter { it.isTeachingCourse() && it.hidden != 0 }.groupBy { it.timetableId }.forEach { (table, rows) ->
            val known = dao.all(table).mapTo(hashSetOf()) { it.occurrenceKey }
            rows.filter { key(it) !in known }.forEach {
                dao.save(TeachingVisibilityEntity(key(it), table, UUID.randomUUID().toString(), 1))
            }
        }
    }

    fun set(events: List<EventItem>, hidden: Boolean) {
        events.filter { it.isTeachingCourse() }.forEach { event ->
            val existing = dao.all(event.timetableId).firstOrNull { it.occurrenceKey == key(event) }
            val id = existing?.preferenceId ?: UUID.randomUUID().toString()
            val value = if (hidden) 1 else 0
            dao.setHidden(id, value)
            dao.save(TeachingVisibilityEntity(key(event), event.timetableId, id, value))
            db.eventItemDao().setHiddenSync(listOf(event.id), value)
        }
    }

    fun apply(before: List<EventItem>, incoming: List<EventItem>, start: Long): List<EventItem> {
        rememberExisting(before)
        val old = before.filter { it.isTeachingCourse() }
        val next = incoming.filter { it.isTeachingCourse() }
        if (next.isEmpty()) return incoming
        fun lesson(e: EventItem) = VersionLesson(e.id, e.subjectId, e.name, e.source, e.from.time,
            e.to.time, e.place.orEmpty(), e.teacher.orEmpty(), e.fromNumber, e.lastNumber,
            Math.floorDiv(e.from.time - start, WEEK_MILLS).toInt() + 1)
        val movedFrom = TimetableVersionDiff.compare(old.map(::lesson), next.map(::lesson)).changes
            .mapNotNull { change -> change.after?.let { a -> change.before?.let { b -> a.id to b.id } } }.toMap()
        val byId = old.associateBy { it.id }
        val byKey = old.associateBy(::key)
        next.forEach { event ->
            val preferences = dao.all(event.timetableId).associateBy { it.occurrenceKey }
            val previous = byId[event.id] ?: byKey[key(event)] ?: movedFrom[event.id]?.let(byId::get)
            val inherited = previous?.let { preferences[key(it)] }
            val exact = preferences[key(event)]
            val selected = inherited ?: exact
            if (selected != null) {
                if (exact != null && exact.preferenceId != selected.preferenceId) {
                    dao.merge(exact.preferenceId, selected.preferenceId, selected.hidden)
                }
                dao.save(TeachingVisibilityEntity(key(event), event.timetableId, selected.preferenceId, selected.hidden))
            }
            event.hidden = selected?.hidden ?: 0
        }
        return incoming
    }
}
