package cn.limpu.hita.feature.timetable.version

import org.junit.Assert.*
import org.junit.Test

class TimetableVersionDiffTest {
    private fun lesson(id: String = "lesson", key: String = "code:math", start: Long = 1000) =
        VersionLesson(id, key, "高等数学", "EAS_IMPORT", start, start + 100, week = 3)

    @Test fun unchangedContentIgnoresRandomOccurrenceIds() {
        assertTrue(TimetableVersionDiff.compare(listOf(lesson("a")), listOf(lesson("b"))).changes.isEmpty())
    }
    @Test fun duplicateOccurrencesAreComparedAsAMultiset() {
        val diff = TimetableVersionDiff.compare(listOf(lesson("a"), lesson("b")), listOf(lesson("c")))
        assertEquals(1, diff.count(VersionChangeKind.REMOVED))
        assertEquals(0, diff.count(VersionChangeKind.ADDED))
    }
    @Test fun stableOccurrenceIdSupportsCrossWeekMove() {
        val before = lesson()
        val after = before.copy(start = 5000, end = 5100, week = 5)
        val diff = TimetableVersionDiff.compare(listOf(before), listOf(after))
        assertEquals(listOf(VersionChange(VersionChangeKind.MOVED, before, after)), diff.changes)
    }
    @Test fun uniqueUnmatchedUnitInSameWeekCanBeMovedWithoutId() {
        val diff = TimetableVersionDiff.compare(listOf(lesson("")), listOf(lesson("", start = 3000)))
        assertEquals(1, diff.count(VersionChangeKind.MOVED))
        assertEquals(1, diff.changes.size)
    }
    @Test fun ambiguousMultipleMovesAreNotPairedByNearestTime() {
        val diff = TimetableVersionDiff.compare(listOf(lesson("", start = 1000), lesson("", start = 2000)),
            listOf(lesson("", start = 3000), lesson("", start = 4000)))
        assertEquals(0, diff.count(VersionChangeKind.MOVED))
        assertEquals(2, diff.count(VersionChangeKind.REMOVED))
        assertEquals(2, diff.count(VersionChangeKind.ADDED))
    }
    @Test fun sameNameAcrossTeachingClassesIsNotAMove() {
        val diff = TimetableVersionDiff.compare(listOf(lesson(id = "", key = "class:a")), listOf(lesson(id = "", key = "class:b", start = 3000)))
        assertEquals(0, diff.count(VersionChangeKind.MOVED))
        assertEquals(2, diff.changes.size)
    }
    @Test fun placeOnlyChangeIsNotATimeMove() {
        val a = lesson()
        val diff = TimetableVersionDiff.compare(listOf(a), listOf(a.copy(place = "A101")))
        assertEquals(1, diff.count(VersionChangeKind.DETAILS))
        assertEquals(0, diff.count(VersionChangeKind.MOVED))
    }
    @Test fun reverseComparisonInvertsAddedAndRemoved() {
        val a = lesson()
        val b = lesson("b", "code:physics", 3000)
        assertEquals(1, TimetableVersionDiff.compare(listOf(a), listOf(a, b)).count(VersionChangeKind.ADDED))
        assertEquals(1, TimetableVersionDiff.compare(listOf(a, b), listOf(a)).count(VersionChangeKind.REMOVED))
    }
    @Test fun changedTeacherAndNoOccurrenceIdDoesNotInventAMove() {
        val diff = TimetableVersionDiff.compare(listOf(lesson("")), listOf(lesson("", start = 3000).copy(teacher = "另一位教师")))
        assertEquals(0, diff.count(VersionChangeKind.MOVED))
    }
}
