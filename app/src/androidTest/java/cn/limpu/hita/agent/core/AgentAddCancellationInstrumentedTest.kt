package cn.limpu.hita.agent.core

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.agent.timetable.AddTimetableArrangementTool
import cn.limpu.hita.agent.timetable.ArrangementInput
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.*
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AgentAddCancellationInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repository: TimetableRepository
    private val from = 1_788_134_400_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = TimetableRepository(app, db, DisplayScopeSelector { error("Explicit timetable only") })
        db.timetableDao().saveTimetablesSync(listOf(Timetable().apply {
            id = "typed-agent-table"; name = "test"; startTime = Timestamp(from); endTime = Timestamp(from + 86_400_000)
        }))
    }
    @After fun cleanup() { db.close() }

    private fun input() = TimetableAgentInput(app, TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT,
        timetableId = "typed-agent-table", arrangement = ArrangementInput("one activity", from, from + 60_000))

    private fun useCase(store: TimetableWriteStore) = TimetableWriteUseCase(store, {}, { it() }, { throw it }, { throw it })

    @Test fun cancelWhileWaitingForMutationLockPreventsAllEventWrites() {
        val thread = AtomicReference<Thread>()
        val ready = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<AgentToolResult<TimetableAgentOutput>>()
        val tool = AddTimetableArrangementTool({ repository }, { _, _ ->
            thread.set(Thread.currentThread()); ready.countDown(); useCase(repository)
        })
        TimetableMutationLock().exclusive {
            val handle = tool.execute(input()) { result.set(it); completed.countDown() }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("The real repository write must be waiting on the held mutation lock", Thread.State.BLOCKED, thread.get().state)
            handle.cancel()
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(AgentToolErrorType.CANCELLED, result.get().errorType)
        assertTrue(db.eventItemDao().getEventsOfTimetableSync("typed-agent-table").isEmpty())
    }

    @Test fun guardedDefaultCreationChecksCancellationAfterWaitingForLock() {
        db.timetableDao().deleteTimetablesInIdsSync(listOf("typed-agent-table"))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            started.countDown()
            try {
                repository.ensureDefaultCustomTimetableSync {
                    if (cancelled.get()) throw java.util.concurrent.CancellationException("cancelled")
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { completed.countDown() }
        }
        TimetableMutationLock().exclusive {
            thread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, thread.state)
            cancelled.set(true)
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is java.util.concurrent.CancellationException)
        assertTrue(db.timetableDao().getTimetablesSync().isEmpty())
    }

    @Test fun cancelAfterCommitKeepsExactlyOneCommittedEventAndSuppressesSuccess() {
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<AgentToolResult<TimetableAgentOutput>>()
        val store = object : TimetableWriteStore {
            override fun enqueueWrite(block: () -> Unit) = repository.enqueueWrite(block)
            override fun writeSync(operation: TimetableWriteOperation) = repository.writeSync(operation)
            override fun writeSync(operation: TimetableWriteOperation, beforeWrite: () -> Unit): Boolean {
                val written = repository.writeSync(operation, beforeWrite)
                committed.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return written
            }
        }
        val tool = AddTimetableArrangementTool({ repository }, { _, _ -> useCase(store) })
        val handle = tool.execute(input()) { result.set(it); completed.countDown() }
        try {
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            handle.cancel()
        } finally { release.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(AgentToolErrorType.CANCELLED, result.get().errorType)
        assertEquals(1, db.eventItemDao().getEventsOfTimetableSync("typed-agent-table").size)
    }
}
