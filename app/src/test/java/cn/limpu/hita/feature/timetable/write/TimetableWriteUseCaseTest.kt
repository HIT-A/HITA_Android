package cn.limpu.hita.feature.timetable.write

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.ScheduleEventCreator
import cn.limpu.hita.data.repository.TimetableWriteOperation
import cn.limpu.hita.data.repository.TimetableWriteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Timestamp
import java.util.ArrayDeque
import java.util.concurrent.Executor

class TimetableWriteUseCaseTest {
    @Test
    fun addWaitsForPersistenceAndDispatchesItsResultOnlyThroughTheResultQueue() {
        val harness = Harness()
        val result = events()
        val subject = subject()
        harness.store.onWrite = { operation ->
            assertTrue(harness.trace.isEmpty())
            assertTrue(harness.results.isEmpty())
            assertEquals(0, harness.deliveries.size)
            assertSame(result, (operation as TimetableWriteOperation.Add).result)
            assertSame(subject, operation.subject)
            harness.trace += "write-complete"
            true
        }

        harness.useCase.add(result, subject, harness::recordResult)

        assertEquals(1, harness.repositoryQueue.size)
        assertTrue(harness.store.operations.isEmpty())
        assertTrue(harness.trace.isEmpty())
        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.deliveries.size)

        harness.repositoryQueue.runNext()

        assertEquals(listOf("write-complete", "refresh"), harness.trace)
        assertEquals(1, harness.store.operations.size)
        assertTrue(harness.results.isEmpty())
        assertEquals(1, harness.deliveries.size)

        harness.deliveries.runNext()

        assertEquals(listOf("write-complete", "refresh", "result"), harness.trace)
        assertTrue(harness.results.single().isSuccess)
        assertEquals(0, harness.repositoryQueue.size)
        assertEquals(0, harness.deliveries.size)
    }

    @Test
    fun writeFailureReachesTheCallbackUnchangedWithoutRequestingRefresh() {
        val harness = Harness()
        val failure = IllegalStateException("database rejected write")
        harness.store.onWrite = { throw failure }

        harness.useCase.add(events(), onResult = harness::recordResult)
        harness.repositoryQueue.runNext()

        assertSame(failure, harness.writeFailures.single())
        assertEquals(1, harness.store.operations.size)
        assertTrue(harness.trace.isEmpty())
        assertTrue(harness.results.isEmpty())
        assertTrue(harness.refreshFailures.isEmpty())

        harness.deliveries.runNext()

        assertSame(failure, harness.results.single().exceptionOrNull())
        assertEquals(listOf("result"), harness.trace)
    }

    @Test
    fun writeFailureWithoutACallbackIsStillReportedAndNeverRefreshes() {
        val harness = Harness()
        val failure = IllegalArgumentException("invalid target")
        harness.store.onWrite = { throw failure }

        harness.useCase.delete(listOf(event("event")))
        harness.repositoryQueue.runAll()

        assertSame(failure, harness.writeFailures.single())
        assertEquals(1, harness.store.operations.size)
        assertTrue(harness.trace.isEmpty())
        assertEquals(0, harness.deliveries.size)
    }

    @Test
    fun refreshFailurePreservesTheSuccessfulWriteResultWithoutRepeatingTheWrite() {
        val harness = Harness()
        val failure = IllegalStateException("widget refresh failed")
        harness.refresh = {
            harness.trace += "refresh"
            throw failure
        }

        harness.useCase.add(events(), onResult = harness::recordResult)
        harness.repositoryQueue.runAll()
        harness.deliveries.runAll()

        assertTrue(harness.results.single().isSuccess)
        assertEquals(1, harness.store.operations.size)
        assertEquals(listOf("write", "refresh", "result"), harness.trace)
        assertSame(failure, harness.refreshFailures.single())
        assertTrue(harness.writeFailures.isEmpty())
        assertEquals(0, harness.repositoryQueue.size)
    }

    @Test
    fun synchronousAddThrowsTheOriginalWriteFailureToTheAgentWithoutRefreshing() {
        val harness = Harness()
        val failure = IllegalStateException("database rejected write")
        harness.store.onWrite = { throw failure }

        val thrown = assertThrows(IllegalStateException::class.java) {
            harness.useCase.addSync(events())
        }

        assertSame(failure, thrown)
        assertEquals(1, harness.store.operations.size)
        assertTrue(harness.trace.isEmpty())
        assertTrue(harness.refreshFailures.isEmpty())
        assertEquals(0, harness.repositoryQueue.size)
        assertEquals(0, harness.deliveries.size)
    }

    @Test
    fun synchronousAddCompletesWritingAndRefreshOnTheCallingThreadBeforeReturning() {
        val harness = Harness()
        val caller = Thread.currentThread()
        val result = events()
        val subject = subject()
        harness.store.onWrite = { operation ->
            assertSame(caller, Thread.currentThread())
            assertSame(result, (operation as TimetableWriteOperation.Add).result)
            assertSame(subject, operation.subject)
            harness.trace += "write"
            true
        }
        harness.refresh = {
            assertSame(caller, Thread.currentThread())
            assertEquals(listOf("write"), harness.trace)
            harness.trace += "refresh"
        }

        harness.useCase.addSync(result, subject)
        harness.trace += "returned"

        assertEquals(listOf("write", "refresh", "returned"), harness.trace)
        assertEquals(1, harness.store.operations.size)
        assertEquals(0, harness.repositoryQueue.size)
        assertEquals(0, harness.deliveries.size)
    }

    @Test
    fun synchronousAddDoesNotReportACommittedWriteAsFailedWhenRefreshThrows() {
        val harness = Harness()
        val failure = IllegalStateException("widget refresh failed")
        harness.refresh = {
            harness.trace += "refresh"
            throw failure
        }

        harness.useCase.addSync(events())

        assertEquals(1, harness.store.operations.size)
        assertEquals(listOf("write", "refresh"), harness.trace)
        assertSame(failure, harness.refreshFailures.single())
        assertTrue(harness.writeFailures.isEmpty())
    }

    @Test
    fun emptyAsyncOperationsReturnSuccessWithoutQueuingWritesOrRefreshing() {
        val harness = Harness()
        val emptyResult = ScheduleEventCreator.Result(emptyList(), timetable())

        harness.useCase.add(emptyResult, subject(), harness::recordResult)
        harness.useCase.update(emptyList(), harness::recordResult)
        harness.useCase.delete(emptyList(), harness::recordResult)
        harness.useCase.deleteSubjects(emptyList(), harness::recordResult)

        assertTrue(harness.store.operations.isEmpty())
        assertEquals(0, harness.repositoryQueue.size)
        assertTrue(harness.trace.isEmpty())
        assertTrue(harness.results.isEmpty())
        assertEquals(4, harness.deliveries.size)

        harness.deliveries.runAll()

        assertEquals(4, harness.results.size)
        assertTrue(harness.results.all { it.isSuccess })
        assertEquals(List(4) { "result" }, harness.trace)
    }

    @Test
    fun emptySynchronousAddDoesNotWriteOrRefresh() {
        val harness = Harness()

        harness.useCase.addSync(ScheduleEventCreator.Result(emptyList(), timetable()), subject())

        assertTrue(harness.store.operations.isEmpty())
        assertTrue(harness.trace.isEmpty())
        assertEquals(0, harness.repositoryQueue.size)
        assertEquals(0, harness.deliveries.size)
    }

    @Test
    fun aMissingTimetableReportedByTheStoreCompletesWithoutRefresh() {
        val harness = Harness()
        harness.store.onWrite = { false }

        harness.useCase.changeStartDate(timetable(), 42L, harness::recordResult)
        harness.repositoryQueue.runNext()

        assertEquals(1, harness.store.operations.size)
        assertTrue(harness.trace.isEmpty())
        assertTrue(harness.results.isEmpty())

        harness.deliveries.runNext()

        assertTrue(harness.results.single().isSuccess)
        assertEquals(listOf("result"), harness.trace)
    }

    @Test
    fun writesUseTheProvidedRepositoryQueueInOrderWithOtherRepositoryWork() {
        val harness = Harness()
        harness.repositoryQueue.execute { harness.trace += "earlier-repository-work" }

        harness.useCase.add(events(), onResult = harness::recordResult)
        harness.repositoryQueue.execute { harness.trace += "later-repository-work" }

        assertTrue(harness.trace.isEmpty())
        harness.repositoryQueue.runAll()

        assertEquals(
            listOf("earlier-repository-work", "write", "refresh", "later-repository-work"),
            harness.trace,
        )
        assertTrue(harness.results.isEmpty())
        harness.deliveries.runAll()
        assertTrue(harness.results.single().isSuccess)
    }

    @Test
    fun subjectDeletionWaitsForColorResetOnTheSubjectQueueAndCannotBeRestoredByIt() {
        val harness = Harness()
        val subjectQueue = TaskQueue()
        val persistedColors = mutableMapOf("subject" to 1)
        subjectQueue.execute {
            harness.trace += "reset-read"
            val colorsToReplace = persistedColors.mapValues { (_, color) -> color + 1 }
            persistedColors.putAll(colorsToReplace)
            harness.trace += "reset-write"
        }
        harness.store.onWrite = { operation ->
            assertEquals(listOf("reset-read", "reset-write"), harness.trace)
            assertEquals(2, persistedColors.getValue("subject"))
            (operation as TimetableWriteOperation.DeleteSubjects).subjects.forEach {
                persistedColors.remove(it.id)
            }
            harness.trace += "delete"
            true
        }
        val useCase = TimetableWriteUseCase(
            store = harness.store,
            refreshWidgets = {
                assertTrue(persistedColors.isEmpty())
                harness.trace += "refresh"
            },
            deliverResult = { harness.deliveries.execute(it) },
            onWriteFailure = { harness.writeFailures += it },
            onRefreshFailure = { harness.refreshFailures += it },
            enqueueWrite = { subjectQueue.execute(it) },
        )

        useCase.deleteSubjects(listOf(subject()), harness::recordResult)

        assertEquals("整门课删除应复用课程队列", 0, harness.repositoryQueue.size)
        assertEquals(2, subjectQueue.size)
        harness.repositoryQueue.runAll()
        assertTrue(harness.store.operations.isEmpty())
        assertTrue(harness.trace.isEmpty())

        subjectQueue.runNext()

        assertEquals(listOf("reset-read", "reset-write"), harness.trace)
        assertEquals(2, persistedColors.getValue("subject"))
        assertTrue(harness.store.operations.isEmpty())
        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.deliveries.size)

        subjectQueue.runNext()

        assertEquals(listOf("reset-read", "reset-write", "delete", "refresh"), harness.trace)
        assertTrue(persistedColors.isEmpty())
        assertEquals(1, harness.store.operations.size)
        assertEquals(0, subjectQueue.size)
        assertEquals(0, harness.repositoryQueue.size)
        assertTrue(harness.results.isEmpty())
        assertEquals(1, harness.deliveries.size)

        harness.deliveries.runNext()

        assertTrue(harness.results.single().isSuccess)
        assertEquals(listOf("reset-read", "reset-write", "delete", "refresh", "result"), harness.trace)
        assertTrue(harness.writeFailures.isEmpty())
        assertTrue(harness.refreshFailures.isEmpty())
    }

    @Test
    fun updateSnapshotsTheCollectionBeforeTheCallerClearsIt() {
        val harness = Harness()
        val first = event("first")
        val second = event("second")
        val selected = mutableListOf(first, second)

        harness.useCase.update(selected)
        selected.clear()
        harness.repositoryQueue.runNext()

        val operation = harness.store.operations.single() as TimetableWriteOperation.Update
        assertEquals(listOf("first", "second"), operation.events.map { it.id })
        assertSame(first, operation.events[0])
        assertSame(second, operation.events[1])
        assertEquals(listOf("write", "refresh"), harness.trace)
    }

    @Test
    fun deleteCapturesIdsBeforeTheCallerChangesTheSelectionOrItsObjects() {
        val harness = Harness()
        val first = event("first")
        val second = event("second")
        val selected = mutableListOf(first, second)

        harness.useCase.delete(selected)
        first.id = "changed-after-submit"
        selected.clear()
        harness.repositoryQueue.runNext()

        val operation = harness.store.operations.single() as TimetableWriteOperation.Delete
        assertEquals(listOf("first", "second"), operation.eventIds)
        assertEquals(listOf("write", "refresh"), harness.trace)
    }

    @Test
    fun deleteSubjectsSnapshotsTheCollectionBeforeTheCallerClearsIt() {
        val harness = Harness()
        val first = subject().apply { id = "first" }
        val second = subject().apply { id = "second" }
        val selected = mutableListOf(first, second)

        harness.useCase.deleteSubjects(selected)
        selected.clear()
        harness.repositoryQueue.runNext()

        val operation = harness.store.operations.single() as TimetableWriteOperation.DeleteSubjects
        assertEquals(listOf("first", "second"), operation.subjects.map { it.id })
        assertSame(first, operation.subjects[0])
        assertSame(second, operation.subjects[1])
        assertEquals(listOf("write", "refresh"), harness.trace)
    }

    @Test
    fun startDateAndStructureEditsUseTheSameCompletionAndRefreshBoundary() {
        val harness = Harness()
        val table = timetable()
        val period = TimePeriodInDay(TimeInDay(8, 30), TimeInDay(10, 15))

        harness.useCase.changeStartDate(table, 42L, harness::recordResult)
        harness.useCase.changeStructure(table, period, 2, harness::recordResult)

        assertTrue(harness.store.operations.isEmpty())
        assertTrue(harness.trace.isEmpty())
        harness.repositoryQueue.runAll()

        val startDate = harness.store.operations[0] as TimetableWriteOperation.ChangeStartDate
        assertSame(table, startDate.timetable)
        assertEquals(42L, startDate.startTime)
        val structure = harness.store.operations[1] as TimetableWriteOperation.ChangeStructure
        assertSame(table, structure.timetable)
        assertSame(period, structure.period)
        assertEquals(2, structure.position)
        assertEquals(listOf("write", "refresh", "write", "refresh"), harness.trace)
        assertTrue(harness.results.isEmpty())

        harness.deliveries.runAll()

        assertEquals(2, harness.results.size)
        assertTrue(harness.results.all { it.isSuccess })
    }

    private class Harness {
        val repositoryQueue = TaskQueue()
        val deliveries = TaskQueue()
        val store = RecordingStore(repositoryQueue)
        val trace = mutableListOf<String>()
        val results = mutableListOf<Result<Unit>>()
        val writeFailures = mutableListOf<Throwable>()
        val refreshFailures = mutableListOf<Throwable>()
        var refresh: () -> Unit = { trace += "refresh" }
        val useCase = TimetableWriteUseCase(
            store = store,
            refreshWidgets = { refresh() },
            deliverResult = { deliveries.execute(it) },
            onWriteFailure = { writeFailures += it },
            onRefreshFailure = { refreshFailures += it },
        )

        init {
            store.onWrite = { trace += "write"; true }
        }

        fun recordResult(result: Result<Unit>) {
            trace += "result"
            results += result
        }
    }

    private class RecordingStore(private val executor: Executor) : TimetableWriteStore {
        val operations = mutableListOf<TimetableWriteOperation>()
        var onWrite: (TimetableWriteOperation) -> Boolean = { true }

        override fun enqueueWrite(block: () -> Unit) {
            executor.execute(block)
        }

        override fun writeSync(operation: TimetableWriteOperation): Boolean {
            operations += operation
            return onWrite(operation)
        }
    }

    private class TaskQueue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "Expected a queued task" }
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private fun events() = ScheduleEventCreator.Result(listOf(event("event")), timetable())

    private fun event(id: String) = EventItem().apply {
        this.id = id
        timetableId = "table"
        from = Timestamp(100L)
        to = Timestamp(200L)
    }

    private fun timetable() = Timetable().apply { id = "table" }

    private fun subject() = TermSubject().apply { id = "subject"; timetableId = "table" }
}
