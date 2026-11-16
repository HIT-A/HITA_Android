package cn.limpu.hita.data.repository

import cn.limpu.hita.feature.timetable.version.*
import com.google.gson.Gson

data class MissingSourceLesson(val fingerprint: String, val firstSeen: Long, val lastSeen: Long, val observations: Int)
data class SourceTimetableState(
    val format: Int = 1,
    val accepted: List<MergeCourse>,
    val observed: List<MergeCourse>,
    val missing: List<MissingSourceLesson> = emptyList(),
    val courseKeys: Map<String, String>? = null,
)
data class SourceTimetableEvaluation(val state: SourceTimetableState, val updates: List<PendingCourseUpdate>)

/** Source-to-source comparison. Local user edits never participate in detection. */
object SourceTimetableDiff {
    private val gson = Gson()
    fun evaluate(
        state: SourceTimetableState?, incoming: List<MergeCourse>, now: Long,
        owner: String, timetableId: String, termId: String,
    ): SourceTimetableEvaluation {
        if (state == null) return SourceTimetableEvaluation(SourceTimetableState(accepted = incoming, observed = incoming,
            courseKeys = incoming.associate { it.subjectId to courseKey(it, incoming) }), emptyList())
        // Retain identity when metadata later supplies a course code. UUIDs rebuilt by a source
        // can still match through a unique code; two distinct teaching units must stay separate.
        val keys = state.courseKeys.orEmpty().toMutableMap()
        state.accepted.forEach { keys.putIfAbsent(it.subjectId, courseKey(it, state.accepted)) }
        incoming.forEach { course -> keys.getOrPut(course.subjectId) {
            val code = course.code?.trim().orEmpty()
            val match = state.accepted.singleOrNull { code.isNotEmpty() && it.code?.trim() == code }
                ?.takeIf { incoming.count { it.code?.trim() == code } == 1 }
            match?.let { keys.getValue(it.subjectId) } ?: courseKey(course, incoming)
        } }
        val before = lessons(state.accepted, keys)
        val after = lessons(incoming, keys)
        val changes = TimetableVersionDiff.compare(before, after).changes
        val oldMissing = state.missing.associateBy { it.fingerprint }
        val missing = mutableListOf<MissingSourceLesson>()
        val updates = mutableListOf<PendingCourseUpdate>()
        changes.groupBy { checkNotNull(it.before ?: it.after).courseKey }.forEach { (key, group) ->
            val old = state.accepted.firstOrNull { keys[it.subjectId] == key }
            val next = incoming.firstOrNull { keys[it.subjectId] == key } ?: requireNotNull(old).copy(lessons = emptyList())
            val occurrences = mutableMapOf<String, Int>()
            val rows = group.mapNotNull { change ->
                val a = change.before?.toMerge()
                val b = change.after?.toMerge()
                val kind = when (change.kind) {
                    VersionChangeKind.MOVED -> PendingChangeKind.LESSON_MOVED
                    VersionChangeKind.ADDED -> PendingChangeKind.LESSON_ADDED
                    VersionChangeKind.REMOVED -> PendingChangeKind.LESSON_REMOVED
                    VersionChangeKind.DETAILS -> PendingChangeKind.LESSON_DETAILS
                }
                val occurrenceKey = gson.toJson(listOf(kind, a, b))
                val index = occurrences.getOrDefault(occurrenceKey, 0)
                occurrences[occurrenceKey] = index + 1
                val fingerprint = CourseChangeConfirm.fingerprint("$owner|$timetableId|$termId|$key", kind,
                    index.toString(), a?.let(gson::toJson).orEmpty(), b?.let(gson::toJson).orEmpty())
                if (kind == PendingChangeKind.LESSON_REMOVED) {
                    val previous = oldMissing[fingerprint]
                    val record = if (previous == null) MissingSourceLesson(fingerprint, now, now, 1)
                        else previous.copy(lastSeen = now, observations = previous.observations + if (now > previous.lastSeen) 1 else 0)
                    missing += record
                    if (record.observations < TimetableRefreshMergePolicy.DECISION_OBSERVATIONS ||
                        now - record.firstSeen < TimetableRefreshMergePolicy.DECISION_WINDOW_MILLIS) return@mapNotNull null
                }
                PendingChangeRow(fingerprint, key, next.name, kind, index.toString(), 0,
                    b?.fromNumber ?: a?.fromNumber ?: 0, b?.lastNumber ?: a?.lastNumber ?: 0,
                    a?.let(gson::toJson).orEmpty(), b?.let(gson::toJson).orEmpty(), a, b)
            }
            if (rows.isNotEmpty()) updates += PendingCourseUpdate(termId, timetableId, key,
                old?.subjectId ?: next.subjectId, next.name, old == null, old, next, rows, owner)
        }
        return SourceTimetableEvaluation(state.copy(observed = incoming, missing = missing, courseKeys = keys), updates)
    }

    /** Applying or explicitly ignoring a proposal advances only those source occurrences. */
    fun resolve(state: SourceTimetableState, updates: List<PendingCourseUpdate>, handled: Set<String>): SourceTimetableState {
        val accepted = state.accepted.toMutableList()
        updates.forEach { update ->
            val rows = update.rows.filter { it.fingerprint in handled && it.isOccurrenceChange() }
            if (rows.isEmpty()) return@forEach
            val index = accepted.indexOfFirst { (state.courseKeys.orEmpty()[it.subjectId] ?: courseKey(it, accepted)) == update.courseKey }
            val current = accepted.getOrNull(index)
            val patched = CourseChangeConfirm.applyRows(current, update.incoming, handled, rows)
            if (index >= 0) accepted.removeAt(index)
            if (patched != null && patched.lessons.isNotEmpty()) accepted += patched
        }
        return state.copy(accepted = accepted, missing = state.missing.filterNot { it.fingerprint in handled })
    }

    private fun courseKey(course: MergeCourse, courses: List<MergeCourse>): String {
        val code = course.code?.trim().orEmpty()
        return if (code.isNotEmpty() && courses.count { it.code?.trim() == code } == 1) "code:$code"
            else "subject:${course.subjectId}"
    }
    private fun lessons(courses: List<MergeCourse>, keys: Map<String, String>) = courses.flatMap { course ->
        course.lessons.map { lesson -> VersionLesson("", keys.getValue(course.subjectId), lesson.name, "EAS_IMPORT",
            lesson.fromMillis, lesson.toMillis, lesson.place, lesson.teacher, lesson.fromNumber, lesson.lastNumber, lesson.weekOfTerm) }
    }.sortedWith(compareBy({ it.courseKey }, { it.start }, { it.end }, { it.place }, { it.teacher }))
    private fun VersionLesson.toMerge() = MergeLesson(name, place, teacher, start, end, period, length, week)
}

fun PendingChangeRow.isOccurrenceChange() = kind in setOf(PendingChangeKind.LESSON_MOVED,
    PendingChangeKind.LESSON_ADDED, PendingChangeKind.LESSON_REMOVED, PendingChangeKind.LESSON_DETAILS)
