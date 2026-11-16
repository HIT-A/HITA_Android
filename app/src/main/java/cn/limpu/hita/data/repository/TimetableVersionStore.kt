package cn.limpu.hita.data.repository

import android.app.Application
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.*
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.feature.timetable.version.*
import com.google.gson.Gson
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Callable

internal data class CourseVersionContent(
    val format: Int = 1,
    val start: Long,
    val end: Long,
    val schedule: List<TimePeriodInDay>,
    val subjects: List<TermSubject>,
    val events: List<EventItem>,
) {
    fun lessons(visibleOnly: Boolean = true): List<VersionLesson> {
        val byId = subjects.associateBy { it.id }
        val duplicateCodes = subjects.filter { !it.code.isNullOrBlank() }.groupingBy { it.code }.eachCount()
        return events.filter { !visibleOnly || it.hidden == 0 }.map { event ->
            val subject = byId[event.subjectId]
            val code = subject?.code?.trim().orEmpty()
            val identity = if (code.isNotEmpty() && duplicateCodes[subject?.code] == 1) "code:$code"
                else "subject:${event.subjectId.ifBlank { event.id }}"
            VersionLesson(event.id, identity, event.name, event.source, event.from.time, event.to.time,
                event.place.orEmpty(), event.teacher.orEmpty(), event.fromNumber, event.lastNumber,
                Math.floorDiv(event.from.time - start, WEEK_MILLS).toInt() + 1)
        }
    }
}

internal data class TimetableVersionComparison(
    val version: TimetableVersionSummary,
    val currentRevision: Long,
    val diff: VersionDiff,
    val restoreDiff: VersionDiff,
    val configurationChanged: Boolean = false,
)

/** All methods run off the main thread. Course writes and snapshots share the same SQLite transaction. */
internal class TimetableVersionStore(
    private val db: AppDatabase,
    private val accountKey: String = "local",
    private val gson: Gson = EventEditHistoryStore.eventCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val versions = db.timetableVersionDao()
    private fun owner(table: Timetable) = if (table.code.isNullOrBlank()) "local" else accountKey
    private fun writable(table: Timetable) = !isProjection(table)

    fun content(id: String): CourseVersionContent? {
        val table = db.timetableDao().getTimetableByIdSync(id) ?: return null
        if (!writable(table)) return null
        val events = db.eventItemDao().versionClasses(id)
        require(events.size <= MAX_LESSONS) { "课程数量超过版本容量" }
        val ids = events.mapTo(hashSetOf()) { it.subjectId }
        return CourseVersionContent(start = table.startTime.time, end = table.endTime.time,
            schedule = table.scheduleStructure, subjects = db.subjectDao().getSubjectsSync(id).filter { it.id in ids },
            events = events)
    }

    fun allContent(): Map<String, CourseVersionContent> = db.timetableDao().getTimetablesSync()
        .filter(::writable).mapNotNull { table -> content(table.id)?.let { table.id to it } }.toMap()

    /** Must be inside the caller's transaction. Baseline is inserted only if a real mutation commits. */
    fun recordChanges(before: Map<String, CourseVersionContent>, reason: String) {
        val tables = db.timetableDao().getTimetablesSync().filter(::writable)
        tables.forEach { table ->
            val next = content(table.id) ?: return@forEach
            val previous = before[table.id]
            if (previous == null) append(table, next, reason)
            else if (fingerprint(previous) != fingerprint(next)) {
                val latest = versions.summaries(owner(table), table.id).firstOrNull()
                if (latest?.fingerprint != fingerprint(next)) {
                    append(table, previous, "INITIAL")
                    append(table, next, reason)
                }
            }
        }
        versions.pruneDeletedTables()
        versions.pruneDeletedSources()
    }

    fun <T> commit(reason: String, block: () -> T): T = db.runInTransaction(Callable {
        val before = allContent()
        val result = block()
        recordChanges(before, reason)
        result
    })

    fun list(id: String): List<TimetableVersionSummary> = db.runInTransaction(Callable {
        val table = requireNotNull(db.timetableDao().getTimetableByIdSync(id))
        val current = requireNotNull(content(id)) { "只读课表不支持版本恢复" }
        append(table, current, "INITIAL")
        versions.summaries(owner(table), id)
    })

    fun compare(id: String, versionId: String): TimetableVersionComparison = db.runInTransaction(Callable {
        val summaries = list(id)
        val table = requireNotNull(db.timetableDao().getTimetableByIdSync(id))
        val stored = requireNotNull(versions.get(owner(table), id, versionId)) { "版本已被清理" }
        val chosen = decode(stored.payload)
        val current = requireNotNull(content(id))
        TimetableVersionComparison(summaries.first { it.id == versionId }, summaries.first().revision,
            TimetableVersionDiff.compare(chosen.lessons(), current.lessons()),
            TimetableVersionDiff.compare(current.lessons(), chosen.lessons()),
            chosen.start != current.start || chosen.schedule != current.schedule)
    })

    fun restore(id: String, versionId: String, expectedRevision: Long): Boolean = db.runInTransaction(Callable {
        val list = list(id)
        check(list.first().revision == expectedRevision) { "课表已变化，请重新比较" }
        val table = requireNotNull(db.timetableDao().getTimetableByIdSync(id))
        val stored = requireNotNull(versions.get(owner(table), id, versionId)) { "版本已被清理" }
        val chosen = decode(stored.payload)
        val current = requireNotNull(content(id))
        require(chosen.events.all { it.timetableId == id } && chosen.subjects.all { it.timetableId == id })
        if (fingerprint(current) == fingerprint(chosen)) return@Callable false
        // Snapshot ids must not replace an event or subject now belonging to a different timetable.
        check(db.eventItemDao().getEventsInIdsIncludingHiddenSync(chosen.events.map { it.id }).all {
            it.timetableId == id && it.type == EventItem.TYPE.CLASS
        }) { "课程身份已变化" }
        check(db.subjectDao().getSubjectsInIdsSync(chosen.subjects.map { it.id }).all { it.timetableId == id })
        db.eventItemDao().deleteVersionClasses(id)
        val existingSubjects = db.subjectDao().getSubjectsSync(id).associateBy { it.id }
        // Course versions do not roll back credits, resource codes, colors or other profile metadata.
        val restoredSubjects = chosen.subjects.map { snapshot ->
            existingSubjects[snapshot.id]?.apply { name = snapshot.name } ?: snapshot
        }
        db.subjectDao().saveSubjectsSync(restoredSubjects)
        db.eventItemDao().saveEvents(chosen.events)
        table.startTime = Timestamp(chosen.start)
        table.scheduleStructure = chosen.schedule
        val otherEnd = db.eventItemDao().getEventsOfTimetableSync(id).maxOfOrNull { it.to.time } ?: 0L
        table.endTime = Timestamp(maxOf(chosen.end, otherEnd))
        db.timetableDao().saveTimetableSync(table)
        append(table, requireNotNull(content(id)), "RESTORE", stored.id)
        true
    })

    private fun append(table: Timetable, value: CourseVersionContent, reason: String, restoredFrom: String? = null) {
        val key = owner(table)
        val latest = versions.summaries(key, table.id).firstOrNull()
        val fingerprint = fingerprint(value)
        if (latest?.fingerprint == fingerprint) return
        val payload = gson.toJson(value)
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "课程版本超过存储容量" }
        versions.insert(TimetableVersionEntity(UUID.randomUUID().toString(), key, table.id,
            (latest?.revision ?: 0) + 1, latest?.id, clock(), reason, restoredFrom, fingerprint, payload))
        versions.trim(key, table.id)
    }

    private fun decode(payload: String): CourseVersionContent {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val result = requireNotNull(gson.fromJson(payload, CourseVersionContent::class.java))
        require(result.format == 1 && result.events.size <= MAX_LESSONS)
        require(result.events.all { it.type == EventItem.TYPE.CLASS })
        return result
    }

    private fun fingerprint(value: CourseVersionContent): String {
        val visible = value.lessons(false).zip(value.events).map { (lesson, event) ->
            gson.toJson(listOf(lesson.copy(id = "", courseKey = ""), event.hidden))
        }.sorted()
        return MessageDigest.getInstance("SHA-256").digest(
            gson.toJson(listOf(value.start, value.schedule, visible)).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
        const val MAX_LESSONS = 5000
        fun isProjection(table: Timetable) = table.code.orEmpty().let { it.startsWith("FOLLOWED:") || it.startsWith("COURSE_PLAN:") }
        fun forApplication(app: Application, db: AppDatabase) = TimetableVersionStore(db,
            FollowedTeachingSectionStore.ownerKey(EasPreferenceSource(app).getEasToken()))
    }
}
