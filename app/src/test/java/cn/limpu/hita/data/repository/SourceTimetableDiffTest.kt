package cn.limpu.hita.data.repository

import org.junit.Assert.*
import org.junit.Test

class SourceTimetableDiffTest {
    private val day = 86400000L
    private fun lesson(start: Long = 1000) = MergeLesson("数学", "A101", "教师", start, start + 100, 1, 2, 3)
    private fun course(vararg lessons: MergeLesson) = MergeCourse("subject", "数学", "MATH", lessons.toList())
    private fun evaluate(state: SourceTimetableState?, incoming: List<MergeCourse>, time: Long = 1) =
        SourceTimetableDiff.evaluate(state, incoming, time, "owner", "table", "term")

    @Test fun initialSourceEstablishesBaselineWithoutInventingChanges() {
        assertTrue(evaluate(null, listOf(course(lesson()))).updates.isEmpty())
    }
    @Test fun moveIsOneAtomicProposal() {
        val old = listOf(course(lesson()))
        val result = evaluate(SourceTimetableState(accepted = old, observed = old), listOf(course(lesson(3000))))
        val row = result.updates.single().rows.single()
        assertEquals(PendingChangeKind.LESSON_MOVED, row.kind)
        val applied = CourseChangeConfirm.applyRows(old.single(), result.updates.single().incoming,
            setOf(row.fingerprint), listOf(row))
        assertEquals(listOf(lesson(3000)), applied?.lessons)
    }
    @Test fun missingLessonNeedsThreeObservationsAndFortyEightHours() {
        val old = listOf(course(lesson()))
        var state = SourceTimetableState(accepted = old, observed = old)
        val first = evaluate(state, emptyList(), 1)
        assertTrue(first.updates.isEmpty())
        state = evaluate(first.state, emptyList(), day).state
        assertTrue(evaluate(state, emptyList(), day + 1).updates.isEmpty())
        val due = evaluate(state, emptyList(), 2 * day + 1)
        assertEquals(PendingChangeKind.LESSON_REMOVED, due.updates.single().rows.single().kind)
    }
    @Test fun repeatedSameObservationTimestampDoesNotIncreaseMissingCount() {
        val old = listOf(course(lesson()))
        val first = evaluate(SourceTimetableState(accepted = old, observed = old), emptyList())
        assertEquals(1, evaluate(first.state, emptyList()).state.missing.single().observations)
    }
    @Test fun returnedLessonCancelsMissingCandidate() {
        val old = listOf(course(lesson()))
        val first = evaluate(SourceTimetableState(accepted = old, observed = old), emptyList())
        val returned = evaluate(first.state, old, 3 * day)
        assertTrue(returned.state.missing.isEmpty())
        assertTrue(returned.updates.isEmpty())
    }
    @Test fun ignoringAChangeAdvancesSourceWithoutChangingLocalCourse() {
        val original = course(lesson())
        val result = evaluate(SourceTimetableState(accepted = listOf(original), observed = listOf(original)), listOf(course(lesson(3000))))
        val handled = result.updates.flatMap { it.rows }.map { it.fingerprint }.toSet()
        val state = SourceTimetableDiff.resolve(result.state, result.updates, handled)
        assertTrue(evaluate(state, state.observed, 5000).updates.isEmpty())
        assertEquals(listOf(lesson()), original.lessons)
    }
    @Test fun changedLocalOccurrenceRejectsStaleMoveRatherThanOverwriting() {
        val old = listOf(course(lesson()))
        val result = evaluate(SourceTimetableState(accepted = old, observed = old), listOf(course(lesson(3000))))
        val update = result.updates.single()
        assertThrows(IllegalStateException::class.java) {
            CourseChangeConfirm.applyRows(course(lesson(2000)), update.incoming,
                update.rows.map { it.fingerprint }.toSet(), update.rows)
        }
    }
    @Test fun resolvingAnAdditionDoesNotLoseUnconfirmedMissingCandidate() {
        val old = listOf(course(lesson()))
        val added = MergeCourse("physics", "物理", "PHYSICS", listOf(lesson(3000).copy(name = "物理")))
        val result = evaluate(SourceTimetableState(accepted = old, observed = old), listOf(added))
        val handled = result.updates.flatMap { it.rows }.map { it.fingerprint }.toSet()
        val state = SourceTimetableDiff.resolve(result.state, result.updates, handled)
        assertEquals(1, state.missing.size)
        assertTrue(state.accepted.any { it.code == "MATH" })
    }
    @Test fun resolvingAnotherProposalKeepsPendingFingerprintStable() {
        val old = listOf(course(lesson()))
        val physics = MergeCourse("physics", "物理", "PHYSICS", listOf(lesson(6000).copy(name = "物理")))
        val incoming = listOf(course(lesson(3000)), physics)
        val result = evaluate(SourceTimetableState(accepted = old, observed = old), incoming)
        val move = result.updates.first { it.subjectId == "subject" }.rows.single().fingerprint
        val added = result.updates.first { it.subjectId == "physics" }.rows.single().fingerprint
        val state = SourceTimetableDiff.resolve(result.state, result.updates, setOf(added))
        assertEquals(move, evaluate(state, incoming, 100).updates.single().rows.single().fingerprint)
    }
    @Test fun codeEnrichmentKeepsSourceIdentityAndDoesNotInventAdditions() {
        val original = course(lesson()).copy(code = null)
        val state = evaluate(null, listOf(original)).state
        val enriched = original.copy(code = "MATH")
        val evaluation = evaluate(state, listOf(enriched))
        assertTrue(evaluation.updates.isEmpty())
        val moved = evaluate(evaluation.state, listOf(enriched.copy(lessons = listOf(lesson(3000)))))
        assertEquals(PendingChangeKind.LESSON_MOVED, moved.updates.single().rows.single().kind)
        val handled = moved.updates.single().rows.map { it.fingerprint }.toSet()
        val accepted = SourceTimetableDiff.resolve(moved.state, moved.updates, handled)
        assertTrue(evaluate(accepted, moved.state.observed).updates.isEmpty())
    }
    @Test fun regeneratedSourceSubjectIdStillMatchesUniqueCourseCode() {
        val original = course(lesson())
        val state = evaluate(null, listOf(original)).state
        assertTrue(evaluate(state, listOf(original.copy(subjectId = "regenerated"))).updates.isEmpty())
    }
    @Test fun olderBaselineWithoutIdentityBindingsCanResolveAnExistingProposal() {
        val original = course(lesson())
        val state = SourceTimetableState(accepted = listOf(original), observed = listOf(original), courseKeys = null)
        val move = evaluate(state, listOf(course(lesson(3000))))
        val handled = move.updates.single().rows.map { it.fingerprint }.toSet()
        val accepted = SourceTimetableDiff.resolve(state, move.updates, handled)
        assertEquals(listOf(lesson(3000)), accepted.accepted.single().lessons)
    }
    @Test fun sourceComparisonDoesNotDependOnLocalDeletionOrRestore() {
        val old = listOf(course(lesson()))
        val state = SourceTimetableState(accepted = old, observed = old)
        // Local courses deliberately are not an argument to the source comparison.
        assertTrue(evaluate(state, old, 5 * day).updates.isEmpty())
    }
}
