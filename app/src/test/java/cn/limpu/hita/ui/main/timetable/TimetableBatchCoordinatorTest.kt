package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TimetableBatchCoordinatorTest {
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = mutableListOf<Pair<String, Continuation<List<EventItem>>>>()
        val writes = mutableListOf<Pair<List<EventItem>, (Result<Unit>) -> Unit>>()
        val deletes = mutableListOf<Pair<List<EventItem>, (Result<Unit>) -> Unit>>()
        val notices = mutableListOf<TimetableNotice>()
        var table: Timetable? = Timetable().apply { id = "table" }
        val coordinator = TimetableBatchCoordinator(scope,
            classes = { id -> suspendCoroutine { pending.add(id to it) } },
            timetable = { table },
            update = { values, result -> writes.add(values to result) },
            delete = { values, result -> deletes.add(values to result) },
        )
        var collector = scope.launch { coordinator.notices.collect { notices.add(it) } }
        val owner = coordinator.attach()
        fun open(id: String, edit: Boolean = true) {
            coordinator.request(owner, event(id), edit, TimetableBatchScope.THIS)
            pending.last().second.resume(listOf(event(id)))
        }
        fun close() = scope.cancel()
    }

    @Test fun replacedUncooperativeLoadCannotOpenOldEditor() = fixture { f ->
        f.coordinator.request(f.owner, event("A"), true, TimetableBatchScope.ALL)
        f.coordinator.request(f.owner, event("B"), true, TimetableBatchScope.ALL)
        f.pending[1].second.resume(listOf(event("B")))
        f.pending[0].second.resume(listOf(event("A")))
        assertEquals("B", f.coordinator.state.value.session?.events?.single()?.id)
        assertTrue(f.notices.isEmpty())
    }

    @Test fun closingOrDestroyingViewRejectsBothLateLoadAndOldViewActions() = fixture { f ->
        f.coordinator.request(f.owner, event("A"), true, TimetableBatchScope.THIS)
        f.coordinator.cancel()
        f.pending.single().second.resume(listOf(event("A")))
        assertNull(f.coordinator.state.value.session)
        f.coordinator.request(f.owner, event("B"), true, TimetableBatchScope.THIS)
        f.coordinator.detach(f.owner)
        val nextOwner = f.coordinator.attach()
        f.coordinator.detach(f.owner) // Destroying the former view cannot close the new view.
        f.pending.last().second.resume(listOf(event("B")))
        f.coordinator.request(f.owner, event("old"), true, TimetableBatchScope.THIS)
        assertTrue(f.coordinator.owns(nextOwner))
        assertEquals(2, f.pending.size)
        assertNull(f.coordinator.state.value.session)
    }

    @Test fun cancellationPropagatesToCancelableLoadWithoutFailureNotice() = runBlocking {
        val gate = CompletableDeferred<List<EventItem>>()
        var cancelled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = TimetableBatchCoordinator(scope,
            classes = { try { gate.await() } finally { cancelled = !currentCoroutineContext().isActive } },
            timetable = { Timetable() }, update = { _, _ -> }, delete = { _, _ -> })
        try {
            val owner = coordinator.attach()
            coordinator.request(owner, event("A"), true, TimetableBatchScope.ALL)
            coordinator.detach(owner)
            assertTrue(cancelled)
            assertNull(coordinator.state.value.session)
        } finally { scope.cancel() }
    }

    @Test fun successfulNoticeWaitsForPersistenceAndDuplicateSubmissionIsIgnored() = fixture { f ->
        f.open("A")
        f.coordinator.apply("new", null, null, emptyMap())
        f.coordinator.apply("duplicate", null, null, emptyMap())
        assertEquals(1, f.writes.size)
        assertEquals("new", f.writes.single().first.single().place)
        assertEquals("original", f.coordinator.state.value.session?.events?.single()?.place)
        assertTrue(f.coordinator.state.value.writing)
        assertTrue(f.notices.isEmpty())
        f.writes.single().second(Result.success(Unit))
        assertEquals(listOf(TimetableNotice.Updated(1, false)), f.notices)
        assertNull(f.coordinator.state.value.session)
        f.writes.single().second(Result.failure(IllegalStateException("duplicate")))
        assertEquals(1, f.notices.size)
    }

    @Test fun failedWriteKeepsEditorAndOldCompletionCannotCompleteRetry() = fixture { f ->
        f.open("A")
        f.coordinator.apply("new", null, null, emptyMap())
        f.writes[0].second(Result.failure(IllegalStateException("disk full")))
        assertEquals(TimetableNotice.Failure("disk full"), f.notices.single())
        assertEquals("original", f.coordinator.state.value.session?.events?.single()?.place)
        assertFalse(f.coordinator.state.value.writing)
        f.coordinator.apply("retry", null, null, emptyMap())
        f.writes[0].second(Result.success(Unit))
        assertTrue(f.coordinator.state.value.writing)
        assertEquals(1, f.notices.size)
        f.writes[1].second(Result.success(Unit))
        assertEquals(TimetableNotice.Updated(1, false), f.notices.last())
    }

    @Test fun oldWriteAndQueuedNoticeCannotReachReplacementViewOrSelection() = fixture { f ->
        f.open("A")
        f.coordinator.apply("new", null, null, emptyMap())
        f.open("B")
        f.writes.single().second(Result.success(Unit))
        assertEquals("B", f.coordinator.state.value.session?.events?.single()?.id)
        assertTrue(f.notices.isEmpty())
        f.collector.cancel()
        f.coordinator.apply("new B", null, null, emptyMap())
        f.writes.last().second(Result.success(Unit))
        f.coordinator.detach(f.owner)
        f.coordinator.attach()
        f.collector = f.scope.launch { f.coordinator.notices.collect { f.notices.add(it) } }
        assertTrue(f.notices.isEmpty())
    }

    @Test fun staleMenuAndFormCallbacksCannotActOnReplacementSelection() = fixture { f ->
        f.open("A")
        val oldForm = f.coordinator.state.value.session
        val oldMenu = requireNotNull(f.coordinator.selection(f.owner))
        f.open("B")
        f.coordinator.request(oldMenu, event("stale"), true, TimetableBatchScope.ALL)
        f.coordinator.apply(oldForm, "stale room", null, null, emptyMap())
        f.coordinator.delete(oldForm)
        f.coordinator.cancel(oldForm)
        assertEquals("B", f.coordinator.state.value.session?.events?.single()?.id)
        assertEquals(2, f.pending.size)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.deletes.isEmpty())
    }

    @Test fun missingTargetAndReadonlyAreReportedWithoutOpeningOrWriting() = fixture { f ->
        f.table = null
        f.open("A")
        assertEquals(TimetableNotice.MissingTarget, f.notices.single())
        assertNull(f.coordinator.state.value.session)
        val projection = event("projection").apply { source = "${EventItem.SOURCE_FOLLOWED_SCHOOL}:school" }
        f.coordinator.request(f.owner, projection, true, TimetableBatchScope.ALL)
        assertEquals(TimetableNotice.ReadOnly, f.notices.last())
        assertEquals(1, f.pending.size)
        f.coordinator.apply("unsafe", null, null, emptyMap())
        f.coordinator.delete()
        assertTrue(f.writes.isEmpty())
        assertTrue(f.deletes.isEmpty())
    }

    @Test fun deleteUsesPersistenceCallbackAndStaysRetryableOnFailure() = fixture { f ->
        f.open("A", edit = false)
        f.coordinator.delete()
        assertTrue(f.coordinator.state.value.writing)
        f.deletes.single().second(Result.failure(IllegalStateException("locked")))
        assertNotNull(f.coordinator.state.value.session)
        assertEquals(TimetableNotice.Failure("locked"), f.notices.single())
        f.coordinator.delete()
        f.deletes.last().second(Result.success(Unit))
        assertNull(f.coordinator.state.value.session)
        assertTrue(f.writes.isEmpty())
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    companion object {
        private fun event(id: String) = EventItem().apply { this.id = id; subjectId = id; timetableId = "table"; place = "original" }
    }
}
