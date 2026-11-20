package cn.limpu.hita.feature.timetable.version

/** Platform-independent occurrence identity. An empty id means the source has no stable occurrence id. */
data class VersionLesson(
    val id: String,
    val courseKey: String,
    val name: String,
    val source: String,
    val start: Long,
    val end: Long,
    val place: String = "",
    val teacher: String = "",
    val period: Int = 0,
    val length: Int = 0,
    val week: Int = 0,
)

enum class VersionChangeKind { MOVED, REMOVED, ADDED, DETAILS }
data class VersionChange(val kind: VersionChangeKind, val before: VersionLesson?, val after: VersionLesson?)
data class VersionDiff(val changes: List<VersionChange>) {
    fun count(kind: VersionChangeKind) = changes.count { it.kind == kind }
}

object TimetableVersionDiff {
    fun compare(before: List<VersionLesson>, after: List<VersionLesson>): VersionDiff {
        require(before.size <= 5000 && after.size <= 5000) { "课次比较超过容量" }
        val old = before.toMutableList()
        val new = after.toMutableList()
        val result = mutableListOf<VersionChange>()
        // Multiset cancellation: duplicate occurrences must retain their multiplicity.
        old.toList().forEach { a ->
            val i = new.indexOfFirst { sameContent(a, it) }
            if (i >= 0) { old.remove(a); new.removeAt(i) }
        }
        fun pair(a: VersionLesson, b: VersionLesson) {
            old.remove(a); new.remove(b)
            result += VersionChange(
                if (a.start != b.start || a.end != b.end) VersionChangeKind.MOVED else VersionChangeKind.DETAILS,
                a, b,
            )
        }
        old.toList().forEach { a ->
            val matches = new.filter { it.source == a.source &&
                ((a.id.isNotBlank() && it.id == a.id) || (it.courseKey == a.courseKey && it.start == a.start && it.end == a.end)) }
            if (matches.size == 1 && old.count { x -> x.source == a.source &&
                    ((a.id.isNotBlank() && x.id == a.id) || (x.courseKey == a.courseKey && x.start == a.start && x.end == a.end)) } == 1) {
                pair(a, matches.single())
            }
        }
        // Without occurrence ids, only a unique unmatched teaching unit in the same week is paired.
        // Never zip multiple removed/added occurrences by nearest time.
        old.toList().forEach { a ->
            val candidates = new.filter { it.courseKey == a.courseKey && it.source == a.source &&
                a.week > 0 && it.week == a.week && it.end - it.start == a.end - a.start && it.teacher == a.teacher }
            val peers = old.filter { it.courseKey == a.courseKey && it.source == a.source && it.week == a.week }
            if (peers.size == 1 && candidates.size == 1) pair(a, candidates.single())
        }
        result += old.map { VersionChange(VersionChangeKind.REMOVED, it, null) }
        result += new.map { VersionChange(VersionChangeKind.ADDED, null, it) }
        return VersionDiff(result.sortedWith(compareBy({ checkNotNull(it.after ?: it.before).start }, { it.kind.ordinal })))
    }

    fun sameContent(a: VersionLesson, b: VersionLesson): Boolean {
        val stable = a.id.isNotBlank() && a.id == b.id && a.source == b.source
        return a.copy(id = "", courseKey = if (stable) "" else a.courseKey) ==
            b.copy(id = "", courseKey = if (stable) "" else b.courseKey)
    }
}
