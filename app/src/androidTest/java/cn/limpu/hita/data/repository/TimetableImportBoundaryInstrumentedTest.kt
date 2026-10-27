package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.TimetableImportFlowHarness.Companion.course
import cn.limpu.hita.data.repository.TimetableImportFlowHarness.Companion.date
import cn.limpu.hita.data.repository.TimetableImportFlowHarness.Companion.term
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableImportBoundaryInstrumentedTest {
    @Test fun mutableRequestInputsAreCapturedBeforeRemoteWait() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val date = date(); val requestedMillis = date.timeInMillis
            val selected = term(); val originalCode = EASTimetableCode.of(fixture.token.campus, selected)
            val schedule = Timetable().getDefaultTimeStructure().map { it.clone() }.toMutableList()
            val firstHour = schedule.first().from.hour
            fixture.fetchCourses = { captured ->
                entered.complete(Unit); release.await()
                assertEquals("1", captured.termCode)
                DataState(listOf(course()))
            }
            val task = async { fixture.importer.importTimetableOfTerm(fixture.session(), selected, date, schedule) }
            entered.await(); date.add(java.util.Calendar.MONTH, 1); selected.termCode = "2"; schedule.first().from.hour = 12
            release.complete(Unit)
            assertEquals(DataState.STATE.SUCCESS, task.await().state)
            val table = fixture.tables().single()
            assertEquals(originalCode, table.code)
            assertEquals(firstHour, table.scheduleStructure.first().from.hour)
            val expected = cn.limpu.hita.utils.TimeTools.getMonday(requestedMillis).timeInMillis
            assertEquals(expected, table.startTime.time)
        }
    }

    @Test fun deadlineWhileWaitingForMutationLockDoesNotWrite() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val holder = executor.submit { TimetableMutationLock().exclusive { held.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS))
                val task = async(Dispatchers.Default) { fixture.import(timeout = 100) }
                withTimeout(5_000) { while (fixture.importThread?.state != Thread.State.BLOCKED) delay(5) }
                delay(150); release.countDown(); holder.get(5, TimeUnit.SECONDS)
                assertEquals(DataState.STATE.FETCH_FAILED, task.await().state)
                assertTrue(fixture.tables().isEmpty())
            } finally { release.countDown(); holder.get(5, TimeUnit.SECONDS); executor.shutdownNow() }
        }
    }

    @Test fun deadlineAfterCommitBoundaryDoesNotReportAFalseRollback() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val reached = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean()
            fixture.onSql = { sql ->
                if (sql.contains("INSERT", true) && sql.contains("`events`", true) && once.compareAndSet(false, true)) {
                    reached.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
                }
            }
            val task = async(Dispatchers.Default) { fixture.import(timeout = 1_000) }
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS))
                delay(1_100); release.countDown()
                assertEquals(DataState.STATE.SUCCESS, task.await().state)
                assertEquals(1, fixture.tables().size); assertEquals(2, fixture.events().size)
            } finally { release.countDown(); task.await() }
        }
    }
}
