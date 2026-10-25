package cn.limpu.hita.data.repository

private const val FP_SEP = "\u001f"

enum class PendingChangeKind {
    PLACE,
    TEACHER,
    TIME,
    WEEKS,
    SLOT_ADDED,
    SLOT_REMOVED,
    ADDED_COURSE,
    RENAME,
}

data class PendingChangeRow(
    val fingerprint: String,
    val courseKey: String,
    val name: String,
    val kind: PendingChangeKind,
    val slotKey: String,
    val dow: Int,
    val fromNumber: Int,
    val lastNumber: Int,
    val before: String,
    val after: String,
)

data class PendingCourseUpdate(
    val termId: String = "",
    val timetableId: String = "",
    val courseKey: String,
    val subjectId: String,
    val name: String,
    val isAdded: Boolean,
    val local: MergeCourse?,
    val incoming: MergeCourse,
    val rows: List<PendingChangeRow>,
) {
    fun withTerm(termId: String, timetableId: String): PendingCourseUpdate =
        copy(termId = termId, timetableId = timetableId)

    fun filterRows(ignored: Set<String>): PendingCourseUpdate =
        copy(rows = rows.filterNot { it.fingerprint in ignored })
}

object CourseChangeConfirm {
    fun fingerprint(
        courseKey: String,
        kind: PendingChangeKind,
        slotKey: String,
        before: String,
        after: String,
    ): String = listOf(courseKey, kind.name, slotKey, before, after).joinToString(FP_SEP)

    fun rowsForChange(
        courseKey: String,
        local: MergeCourse,
        incoming: MergeCourse,
        change: CourseChange,
    ): List<PendingChangeRow> {
        val rows = mutableListOf<PendingChangeRow>()
        if (change.previousName != null) {
            rows += row(
                courseKey = courseKey,
                name = incoming.name,
                kind = PendingChangeKind.RENAME,
                slotKey = "",
                before = change.previousName,
                after = incoming.name,
            )
        }
        for (slot in change.slotChanges) {
            val slotKey = "${slot.dow}|${slot.fromNumber}|${slot.lastNumber}"
            when (slot.kind) {
                SlotChangeKind.ADDED -> rows += row(
                    courseKey, incoming.name, PendingChangeKind.SLOT_ADDED, slotKey,
                    slot.dow, slot.fromNumber, slot.lastNumber, "", describeSlot(slot),
                )
                SlotChangeKind.REMOVED -> rows += row(
                    courseKey, incoming.name, PendingChangeKind.SLOT_REMOVED, slotKey,
                    slot.dow, slot.fromNumber, slot.lastNumber, describeSlot(slot), "",
                )
                SlotChangeKind.ADJUSTED -> {
                    if (slot.placeBefore != null) {
                        rows += row(
                            courseKey, incoming.name, PendingChangeKind.PLACE, slotKey,
                            slot.dow, slot.fromNumber, slot.lastNumber, slot.placeBefore, slot.place,
                        )
                    }
                    if (slot.teacherBefore != null) {
                        rows += row(
                            courseKey, incoming.name, PendingChangeKind.TEACHER, slotKey,
                            slot.dow, slot.fromNumber, slot.lastNumber, slot.teacherBefore, slot.teacher,
                        )
                    }
                    if (slot.clockBefore != null || slot.lessonCountBefore != null) {
                        val before = slot.clockBefore ?: slot.lessonCountBefore?.toString().orEmpty()
                        val after = if (slot.clockBefore != null) slot.clock else slot.lessonCount.toString()
                        rows += row(
                            courseKey, incoming.name, PendingChangeKind.TIME, slotKey,
                            slot.dow, slot.fromNumber, slot.lastNumber, before, after,
                        )
                    }
                    if (slot.weeksBefore != null) {
                        rows += row(
                            courseKey, incoming.name, PendingChangeKind.WEEKS, slotKey,
                            slot.dow, slot.fromNumber, slot.lastNumber, slot.weeksBefore, slot.weeks,
                        )
                    }
                }
            }
        }
        return rows
    }

    fun addedCourse(incoming: MergeCourse): PendingCourseUpdate {
        val key = TimetableRefreshMergePolicy.courseKey(incoming.name, incoming.code)
        val row = row(
            courseKey = key,
            name = incoming.name,
            kind = PendingChangeKind.ADDED_COURSE,
            slotKey = "",
            before = "",
            after = incoming.name,
        )
        return PendingCourseUpdate(
            courseKey = key,
            subjectId = incoming.subjectId,
            name = incoming.name,
            isAdded = true,
            local = null,
            incoming = incoming,
            rows = listOf(row),
        )
    }

    fun updatedCourse(local: MergeCourse, incoming: MergeCourse, change: CourseChange): PendingCourseUpdate {
        val key = TimetableRefreshMergePolicy.courseKey(local.name, local.code)
        return PendingCourseUpdate(
            courseKey = key,
            subjectId = local.subjectId,
            name = incoming.name,
            isAdded = false,
            local = local,
            incoming = incoming.copy(subjectId = local.subjectId),
            rows = rowsForChange(key, local, incoming, change),
        )
    }

    fun applyRows(
        local: MergeCourse?,
        incoming: MergeCourse,
        adopted: Set<String>,
        rows: List<PendingChangeRow>,
    ): MergeCourse? {
        if (local == null) {
            return if (rows.any { it.kind == PendingChangeKind.ADDED_COURSE && it.fingerprint in adopted }) {
                incoming
            } else {
                null
            }
        }
        val adoptedKinds = rows.filter { it.fingerprint in adopted }.groupBy { it.slotKey }
        val rename = rows.firstOrNull { it.kind == PendingChangeKind.RENAME && it.fingerprint in adopted }
        val localBySlot = local.lessons.groupBy { slotKeyOf(it) }.toMutableMap()
        val incomingBySlot = incoming.lessons.groupBy { slotKeyOf(it) }

        val result = mutableListOf<MergeLesson>()
        val seen = HashSet<String>()
        for ((slotKey, localLessons) in localBySlot) {
            val kinds = adoptedKinds[slotKey].orEmpty().map { it.kind }.toSet()
            if (PendingChangeKind.SLOT_REMOVED in kinds) continue
            seen += slotKey
            val incomingLessons = incomingBySlot[slotKey].orEmpty()
            result += patchSlot(localLessons, incomingLessons, kinds)
        }
        for ((slotKey, incomingLessons) in incomingBySlot) {
            if (slotKey in seen) continue
            val kinds = adoptedKinds[slotKey].orEmpty().map { it.kind }.toSet()
            if (PendingChangeKind.SLOT_ADDED in kinds) {
                result += incomingLessons
            }
        }
        val name = rename?.after ?: local.name
        return MergeCourse(
            subjectId = local.subjectId,
            name = name,
            code = incoming.code ?: local.code,
            lessons = result.sortedBy { it.fromMillis },
        )
    }

    private fun patchSlot(
        localLessons: List<MergeLesson>,
        incomingLessons: List<MergeLesson>,
        kinds: Set<PendingChangeKind>,
    ): List<MergeLesson> {
        if (incomingLessons.isEmpty()) return localLessons
        val adoptTime = PendingChangeKind.TIME in kinds
        val adoptWeeks = PendingChangeKind.WEEKS in kinds
        val adoptPlace = PendingChangeKind.PLACE in kinds
        val adoptTeacher = PendingChangeKind.TEACHER in kinds
        if (!adoptTime && !adoptWeeks && !adoptPlace && !adoptTeacher) return localLessons

        val incomingByWeek = incomingLessons.groupBy { it.weekOfTerm }
        val localWeeks = localLessons.map { it.weekOfTerm }.toSet()
        val result = mutableListOf<MergeLesson>()
        for ((week, locals) in localLessons.groupBy { it.weekOfTerm }) {
            val incoming = incomingByWeek[week].orEmpty()
            if (incoming.isEmpty()) {
                if (!adoptWeeks || week == 0) result += locals
                continue
            }
            val remaining = incoming.indices.toMutableSet()
            val matches = mutableMapOf<Int, Int>()
            // Reserve unchanged time ranges first, so a moved session cannot steal another session.
            locals.forEachIndexed { index, lesson ->
                val exact = remaining.firstOrNull {
                    incoming[it].fromMillis == lesson.fromMillis && incoming[it].toMillis == lesson.toMillis
                }
                if (exact != null) {
                    matches[index] = exact
                    remaining.remove(exact)
                }
            }
            val unmatchedLocal = locals.indices.filterNot { it in matches }
                .sortedWith(compareBy({ locals[it].fromMillis }, { locals[it].toMillis }))
            val unmatchedIncoming = remaining.sortedWith(compareBy({ incoming[it].fromMillis }, { incoming[it].toMillis }))
            unmatchedLocal.zip(unmatchedIncoming).forEach { (localIndex, incomingIndex) ->
                matches[localIndex] = incomingIndex
                remaining.remove(incomingIndex)
            }
            locals.forEachIndexed { index, lesson ->
                val replacement = matches[index]?.let { incoming[it] }
                if (replacement != null) {
                    result += lesson.copy(
                        fromMillis = if (adoptTime) replacement.fromMillis else lesson.fromMillis,
                        toMillis = if (adoptTime) replacement.toMillis else lesson.toMillis,
                        fromNumber = if (adoptTime) replacement.fromNumber else lesson.fromNumber,
                        lastNumber = if (adoptTime) replacement.lastNumber else lesson.lastNumber,
                        place = if (adoptPlace) replacement.place else lesson.place,
                        teacher = if (adoptTeacher) replacement.teacher else lesson.teacher,
                        name = replacement.name.ifBlank { lesson.name },
                    )
                } else if (!adoptTime) {
                    result += lesson
                }
            }
            // TIME includes accepting changes to the number of sessions within an existing week.
            if (adoptTime) result += remaining.map { incoming[it] }
        }
        if (adoptWeeks) result += incomingLessons.filter { it.weekOfTerm !in localWeeks }
        return result
    }

    private fun describeSlot(slot: LessonSlotChange): String =
        listOf(
            "${slot.dow}|${slot.fromNumber}-${slot.lastNumber}",
            slot.clock,
            slot.weeks,
            slot.place,
            slot.teacher,
        ).filter { it.isNotBlank() }.joinToString(" ")

    private fun slotKeyOf(lesson: MergeLesson): String =
        "${dowOf(lesson.fromMillis)}|${lesson.fromNumber}|${lesson.lastNumber}"

    private fun dowOf(millis: Long): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = millis
        calendar.firstDayOfWeek = java.util.Calendar.MONDAY
        return if (calendar.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) {
            7
        } else {
            calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
        }
    }

    private fun row(
        courseKey: String,
        name: String,
        kind: PendingChangeKind,
        slotKey: String,
        dow: Int = 0,
        fromNumber: Int = 0,
        lastNumber: Int = 0,
        before: String,
        after: String,
    ): PendingChangeRow = PendingChangeRow(
        fingerprint = fingerprint(courseKey, kind, slotKey, before, after),
        courseKey = courseKey,
        name = name,
        kind = kind,
        slotKey = slotKey,
        dow = dow,
        fromNumber = fromNumber,
        lastNumber = lastNumber,
        before = before,
        after = after,
    )
}
