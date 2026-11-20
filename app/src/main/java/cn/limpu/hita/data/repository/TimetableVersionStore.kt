package cn.limpu.hita.data.repository

import android.app.Application
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.*
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.feature.timetable.version.*
import cn.limpu.hita.utils.ColorTools
import com.google.gson.Gson
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Callable

internal data class CourseVersionContent(
    val format: Int = 2,
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
    val metadataChanged: Boolean = false,
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
    private fun writable(table: Timetable) = !isProjection(table) &&
        (!table.code.isNullOrBlank() || db.eventItemDao().versionClasses(table.id).any { it.isTeachingCourse() })

    fun content(id: String): CourseVersionContent? {
        val table = db.timetableDao().getTimetableByIdSync(id) ?: return null
        if (!writable(table)) return null
        val raw = db.eventItemDao().versionClasses(id).filter { it.isTeachingCourse() }
        TeachingVisibilityStore(db).rememberExisting(raw)
        val events = raw.map { gson.fromJson(gson.toJson(it), EventItem::class.java).apply { hidden = 0; color = 0 } }
        require(events.size <= MAX_LESSONS) { "课程数量超过版本容量" }
        val ids = events.mapTo(hashSetOf()) { it.subjectId }
        return CourseVersionContent(start = table.startTime.time, end = table.endTime.time,
            schedule = table.scheduleStructure, subjects = db.subjectDao().getSubjectsSync(id).filter { it.id in ids },
            events = events).let(::normalize)
    }

    fun allContent(): Map<String, CourseVersionContent> {
        detachLegacyPersonalSubjects()
        return db.timetableDao().getTimetablesSync()
            .filter(::writable).mapNotNull { table -> content(table.id)?.let { table.id to it } }.toMap()
    }

    /** Older "add a session" flows attached personal classes to a teaching-owned subject. */
    fun protectPersonalSubjects(table: String, teachingIds: Set<String>) =
        detachLegacyPersonalSubjects(mapOf(table to teachingIds))

    private fun detachLegacyPersonalSubjects(forced: Map<String, Set<String>> = emptyMap()) {
        db.timetableDao().getTimetablesSync().filterNot(::isProjection).forEach { table ->
            val rows = db.eventItemDao().allEventsIncludingHidden(table.id)
            val teachingIds = rows.filter { it.isTeachingCourse() }.mapTo(hashSetOf()) { it.subjectId }.apply { addAll(forced[table.id].orEmpty()) }
            rows.filter { it.source != EventItem.SOURCE_EAS_IMPORT && it.subjectId in teachingIds }
                .groupBy { it.subjectId }.forEach { (subjectId, personal) ->
                    val original = db.subjectDao().getSubjectsInIdsSync(listOf(subjectId)).singleOrNull() ?: return@forEach
                    val copy = gson.fromJson(gson.toJson(original), TermSubject::class.java).apply {
                        id = "personal:" + UUID.nameUUIDFromBytes("${table.id}|$subjectId".toByteArray(Charsets.UTF_8))
                    }
                    // An existing personal subject may already have its own color/metadata.
                    if (db.subjectDao().getSubjectsInIdsSync(listOf(copy.id)).isEmpty()) db.subjectDao().saveSubjectSync(copy)
                    db.eventItemDao().updateEventsSync(personal.onEach { it.subjectId = copy.id })
                }
        }
    }

    /** Must be inside the caller's transaction. Baseline is inserted only if a real mutation commits. */
    fun recordChanges(before: Map<String, CourseVersionContent>, reason: String) {
        val tables = db.timetableDao().getTimetablesSync().filter(::writable)
        tables.forEach { table ->
            if (reason == "SOURCE") {
                val current = db.eventItemDao().versionClasses(table.id).filter { it.isTeachingCourse() }
                db.eventItemDao().saveEvents(TeachingVisibilityStore(db).apply(before[table.id]?.events.orEmpty(), current, table.startTime.time))
            }
            val next = content(table.id) ?: return@forEach
            normalizeHistory(table)
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
        db.teachingVisibilityDao().prune()
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
        normalizeHistory(table)
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
            chosen.start != current.start || chosen.schedule != current.schedule,
            subjectMetadata(chosen) != subjectMetadata(current))
    })

    fun restore(id: String, versionId: String, expectedRevision: Long): Boolean = db.runInTransaction(Callable {
        detachLegacyPersonalSubjects()
        val list = list(id)
        check(list.first().revision == expectedRevision) { "课表已变化，请重新比较" }
        val table = requireNotNull(db.timetableDao().getTimetableByIdSync(id))
        val stored = requireNotNull(versions.get(owner(table), id, versionId)) { "版本已被清理" }
        val chosen = decode(stored.payload)
        protectPersonalSubjects(id, chosen.subjects.mapTo(hashSetOf()) { it.id })
        val current = requireNotNull(content(id))
        require(chosen.events.all { it.timetableId == id } && chosen.subjects.all { it.timetableId == id })
        if (fingerprint(current) == fingerprint(chosen)) return@Callable false
        // Snapshot ids must not replace an event or subject now belonging to a different timetable.
        check(db.eventItemDao().getEventsInIdsIncludingHiddenSync(chosen.events.map { it.id }).all {
            it.timetableId == id && it.isTeachingCourse()
        }) { "课程身份已变化" }
        check(db.subjectDao().getSubjectsInIdsSync(chosen.subjects.map { it.id }).all { it.timetableId == id })
        val restoredEvents = TeachingVisibilityStore(db).apply(
            db.eventItemDao().versionClasses(id).filter { it.isTeachingCourse() }, chosen.events, chosen.start)
        db.eventItemDao().deleteCourseFromTimetable(id)
        val existingSubjects = db.subjectDao().getSubjectsSync(id).associateBy { it.id }
        val restoredSubjects = chosen.subjects.map { snapshot ->
            snapshot.apply { color = existingSubjects[snapshot.id]?.color ?: ColorTools.colorForName(snapshot.name) }
        }
        db.subjectDao().saveSubjectsSync(restoredSubjects)
        db.eventItemDao().saveEvents(restoredEvents)
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
        require(result.format in 1..2 && result.events.size <= MAX_LESSONS)
        require(result.events.all { it.type == EventItem.TYPE.CLASS })
        return normalize(result)
    }

    /** Upgrade mixed v20 payloads without restoring personal events or old visibility values. */
    private fun normalize(value: CourseVersionContent): CourseVersionContent {
        val events = value.events.filter { it.isTeachingCourse() }.map {
            gson.fromJson(gson.toJson(it), EventItem::class.java).apply { hidden = 0; color = 0 }
        }
        val ids = events.mapTo(hashSetOf()) { it.subjectId }
        val subjects = value.subjects.filter { it.id in ids }.map {
            gson.fromJson(gson.toJson(it), TermSubject::class.java).apply { color = 0 }
        }
        return value.copy(format = 2, events = events, subjects = subjects)
    }

    private fun normalizeHistory(table: Timetable) {
        var previous: Pair<String, String>? = null
        versions.summaries(owner(table), table.id).asReversed().forEach { summary ->
            val stored = requireNotNull(versions.get(owner(table), table.id, summary.id))
            val value = decode(stored.payload)
            val hash = fingerprint(value)
            versions.normalize(stored.id, gson.toJson(value), hash)
            if (previous?.second == hash) versions.delete(requireNotNull(previous).first)
            previous = stored.id to hash
        }
    }

    private fun fingerprint(value: CourseVersionContent): String {
        val lessons = value.lessons(false).map { gson.toJson(it.copy(id = "", courseKey = "")) }.sorted()
        val subjects = subjectMetadata(value)
        return MessageDigest.getInstance("SHA-256").digest(
            gson.toJson(listOf(value.start, value.schedule, lessons, subjects)).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
    }

    private fun subjectMetadata(value: CourseVersionContent): List<String> =
        value.subjects.map { subject -> gson.toJsonTree(subject).asJsonObject.apply {
            listOf("id", "timetableId", "createdAt", "color", "teacher").forEach(::remove)
        }.toString() }.sorted()

    companion object {
        const val MAX_BYTES = 512 * 1024
        const val MAX_LESSONS = 5000
        fun isProjection(table: Timetable) = table.code.orEmpty().let { it.startsWith("FOLLOWED:") || it.startsWith("COURSE_PLAN:") }
        fun forApplication(app: Application, db: AppDatabase) = TimetableVersionStore(db,
            FollowedTeachingSectionStore.ownerKey(EasPreferenceSource(app).getEasToken()))
    }
}
