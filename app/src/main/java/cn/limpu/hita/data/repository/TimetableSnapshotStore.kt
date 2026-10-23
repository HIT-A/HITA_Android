package cn.limpu.hita.data.repository

import android.content.Context
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.UUID

enum class TimetableSnapshotKind {
    BEFORE_REFRESH,
    IMPORTED,
    BEFORE_RESTORE,
    BEFORE_USER_ADOPT
}

data class TimetableVersionSnapshot(
    val id: String,
    val ownerKey: String,
    val termId: String,
    val createdAtMillis: Long,
    val kind: TimetableSnapshotKind,
    val timetable: SnapshotTimetable,
    val subjects: List<SnapshotSubject>,
    val events: List<SnapshotEvent>,
    val fingerprint: String
) {
    val courseCount: Int
        get() = subjects.size

    val lessonCount: Int
        get() = events.size
}

data class SnapshotTimetable(
    val id: String,
    val name: String,
    val code: String,
    val startMillis: Long,
    val endMillis: Long,
    val createdAtMillis: Long,
    val schedule: List<FollowedTimePeriod>
)

data class SnapshotSubject(
    val id: String,
    val name: String,
    val timetableId: String,
    val type: String,
    val field: String?,
    val selectCategory: String?,
    val nature: String?,
    val credit: Float,
    val school: String?,
    val countInSpa: Boolean,
    val code: String?,
    val key: String?,
    val createdAtMillis: Long,
    val color: Int
)

data class SnapshotEvent(
    val id: String,
    val type: String,
    val source: String,
    val name: String,
    val place: String?,
    val teacher: String?,
    val subjectId: String,
    val timetableId: String,
    val fromMillis: Long,
    val toMillis: Long,
    val fromNumber: Int,
    val lastNumber: Int,
    val createdAtMillis: Long
)

internal class TimetableSnapshotStore(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context.applicationContext),
) {
    private data class Payload(
        val version: Int = 1,
        val snapshots: List<TimetableVersionSnapshot> = emptyList()
    )

    private val appContext = context.applicationContext
    private val timetableDao = database.timetableDao()
    private val eventDao = database.eventItemDao()
    private val subjectDao = database.subjectDao()
    private val gson = Gson()
    private val directory = File(appContext.filesDir, "timetable_snapshots")
    private val payloadFile = File(directory, "v1.json")
    private val lock = Any()

    fun capture(
        ownerKey: String,
        term: TermItem,
        timetable: Timetable,
        kind: TimetableSnapshotKind
    ): TimetableVersionSnapshot? = synchronized(lock) {
        val events = eventDao.getImportedClassEventsOfTimetableSync(timetable.id)
        if (events.isEmpty()) return@synchronized null
        val subjectIds = events.mapTo(hashSetOf()) { it.subjectId }
        val subjects = subjectDao.getSubjectsSync(timetable.id).filter { it.id in subjectIds }
        val snapshotTimetable = timetable.toSnapshot()
        val snapshotSubjects = subjects.map { it.toSnapshot() }
        val snapshotEvents = events.map { it.toSnapshot() }
        val fingerprint = fingerprint(snapshotTimetable, snapshotSubjects, snapshotEvents)
        val current = payload().snapshots.toMutableList()
        val latest = current.filter { it.ownerKey == ownerKey && it.termId == term.id }
            .maxByOrNull { it.createdAtMillis }
        if (latest?.fingerprint == fingerprint) return@synchronized latest

        val snapshot = TimetableVersionSnapshot(
            id = UUID.randomUUID().toString(),
            ownerKey = ownerKey,
            termId = term.id,
            createdAtMillis = System.currentTimeMillis(),
            kind = kind,
            timetable = snapshotTimetable,
            subjects = snapshotSubjects,
            events = snapshotEvents,
            fingerprint = fingerprint
        )
        current += snapshot
        val retained = current.groupBy { it.ownerKey to it.termId }.values.flatMap { group ->
            group.sortedByDescending { it.createdAtMillis }.take(MAX_PER_TERM)
        }
        persist(Payload(snapshots = retained))
        snapshot
    }

    fun snapshots(ownerKey: String, termId: String): List<TimetableVersionSnapshot> = synchronized(lock) {
        payload().snapshots.filter { it.ownerKey == ownerKey && it.termId == termId }
            .sortedByDescending { it.createdAtMillis }
    }

    fun restore(ownerKey: String, snapshotId: String): TimetableVersionSnapshot = synchronized(lock) {
        val snapshot = payload().snapshots.firstOrNull {
            it.ownerKey == ownerKey && it.id == snapshotId
        } ?: error("课表版本不存在或不属于当前账号")
        database.runInTransaction {
            val current = timetableDao.getTimetableByIdSync(snapshot.timetable.id)
            if (current != null) {
                val termParts = snapshot.termId.split('-')
                val term = TermItem(
                    yearCode = termParts.take(2).joinToString("-"),
                    yearName = termParts.take(2).joinToString("-"),
                    termCode = termParts.lastOrNull().orEmpty(),
                    termName = ""
                )
                capture(ownerKey, term, current, TimetableSnapshotKind.BEFORE_RESTORE)
            }
            val oldSubjectIds = eventDao.getImportedClassEventsOfTimetableSync(snapshot.timetable.id)
                .mapTo(hashSetOf()) { it.subjectId }
            eventDao.deleteCourseFromTimetable(snapshot.timetable.id)
            if (oldSubjectIds.isNotEmpty()) {
                subjectDao.deleteUnusedSubjectsInTimetableSync(snapshot.timetable.id, oldSubjectIds.toList())
            }
            val restored = snapshot.timetable.toEntity().apply {
                // Source snapshots restore courses, not current local display management.
                if (current != null) {
                    name = current.name
                    groupId = current.groupId
                }
            }
            timetableDao.saveTimetableSync(restored)
            subjectDao.saveSubjectsSync(snapshot.subjects.map { it.toEntity() })
            eventDao.saveEvents(snapshot.events.map { it.toEntity() })
        }
        snapshot
    }

    private fun payload(): Payload {
        if (!payloadFile.isFile) return Payload()
        return runCatching { gson.fromJson(payloadFile.readText(), Payload::class.java) }
            .getOrNull()
            ?.takeIf { it.version == 1 }
            ?: Payload()
    }

    private fun persist(payload: Payload) {
        directory.mkdirs()
        val temporary = File(directory, "v1.json.tmp")
        temporary.writeText(gson.toJson(payload))
        if (!temporary.renameTo(payloadFile)) {
            payloadFile.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private fun fingerprint(
        timetable: SnapshotTimetable,
        subjects: List<SnapshotSubject>,
        events: List<SnapshotEvent>
    ): String {
        val canonical = buildString {
            append(timetable.startMillis).append('|').append(timetable.endMillis).append('|')
            subjects.sortedBy { it.id }.forEach {
                append(it.id).append('|').append(it.name).append('|').append(it.code).append('|')
            }
            events.sortedWith(compareBy<SnapshotEvent> { it.fromMillis }.thenBy { it.id }).forEach {
                append(it.subjectId).append('|').append(it.fromMillis).append('|').append(it.toMillis)
                    .append('|').append(it.place).append('|').append(it.teacher).append('|')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun Timetable.toSnapshot() = SnapshotTimetable(
        id = id,
        name = name.orEmpty(),
        code = code.orEmpty(),
        startMillis = startTime.time,
        endMillis = endTime.time,
        createdAtMillis = createdAt.time,
        schedule = scheduleStructure.map {
            FollowedTimePeriod(it.from.hour, it.from.minute, it.to.hour, it.to.minute)
        }
    )

    private fun TermSubject.toSnapshot() = SnapshotSubject(
        id, name, timetableId, type.name, field, selectCategory, nature, credit, school,
        countInSPA, code, key, createdAt.time, color
    )

    private fun EventItem.toSnapshot() = SnapshotEvent(
        id, type.name, source, name, place, teacher, subjectId, timetableId,
        from.time, to.time, fromNumber, lastNumber, createdAt.time
    )

    private fun SnapshotTimetable.toEntity() = Timetable().also {
        it.id = id
        it.name = name
        it.code = code
        it.startTime = Timestamp(startMillis)
        it.endTime = Timestamp(endMillis)
        it.createdAt = Timestamp(createdAtMillis)
        it.scheduleStructure = schedule.map { period ->
            TimePeriodInDay(
                TimeInDay(period.fromHour, period.fromMinute),
                TimeInDay(period.toHour, period.toMinute)
            )
        }
    }

    private fun SnapshotSubject.toEntity() = TermSubject().also {
        it.id = id
        it.name = name
        it.timetableId = timetableId
        it.type = runCatching { TermSubject.TYPE.valueOf(type) }.getOrDefault(TermSubject.TYPE.COM_A)
        it.field = field
        it.selectCategory = selectCategory
        it.nature = nature
        it.credit = credit
        it.school = school
        it.countInSPA = countInSpa
        it.code = code
        it.key = key
        it.createdAt = Timestamp(createdAtMillis)
        it.color = color
    }

    private fun SnapshotEvent.toEntity() = EventItem().also {
        it.id = id
        it.type = runCatching { EventItem.TYPE.valueOf(type) }.getOrDefault(EventItem.TYPE.CLASS)
        it.source = source
        it.name = name
        it.place = place
        it.teacher = teacher
        it.subjectId = subjectId
        it.timetableId = timetableId
        it.from = Timestamp(fromMillis)
        it.to = Timestamp(toMillis)
        it.fromNumber = fromNumber
        it.lastNumber = lastNumber
        it.createdAt = Timestamp(createdAtMillis)
    }

    companion object {
        private const val MAX_PER_TERM = 12
    }
}
