package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.timetable.EventItem
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real complete new automatic import, including its exam transaction. */
@RunWith(AndroidJUnit4::class)
class AutomaticExamCommitInstrumentedTest {
    private fun exam(name: String) = ExamItem().apply {
        courseName = name; examDate = "2026-03-30"; examTime = "09:00-11:00"; examLocation = "A101"
    }

    @Test fun cancellationWhileWaitingForSQLiteDoesNotInsertExams() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val examReached = CompletableDeferred<Unit>(); val releaseExam = CompletableDeferred<Unit>()
            val examThread = AtomicReference<Thread>()
            fixture.fetchExams = {
                examReached.complete(Unit); releaseExam.await()
                examThread.set(Thread.currentThread())
                DataState(listOf(exam("first")))
            }
            val result = CompletableDeferred<AutoImportResult>()
            val task = fixture.importer.startAutoImportCurrentTimetable(true) { result.complete(it) }!!
            examReached.await()
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val holder = executor.submit { fixture.database.runInTransaction { held.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS)); releaseExam.complete(Unit)
                withTimeout(5_000) { while (examThread.get()?.stackTrace?.none {
                    it.className.contains("SQLiteConnectionPool") || (it.className.contains("RoomDatabase") && it.methodName.contains("beginTransaction"))
                } != false) delay(5) }
                task.cancel(); release.countDown(); holder.get(5, TimeUnit.SECONDS); task.join()
                assertEquals(AutoImportResult.FAILED, result.await())
                assertTrue(fixture.database.eventItemDao().getExamEventsSync().isEmpty())
                assertEquals("Earlier course commit remains committed", 2, fixture.events().count { it.type == EventItem.TYPE.CLASS })
            } finally { release.countDown(); task.join(); holder.get(5, TimeUnit.SECONDS); executor.shutdownNow() }
        }
    }

    @Test fun laterExamFailureRollsBackTheAutomaticBatch() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            fixture.tables()
            fixture.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second_exam BEFORE INSERT ON events WHEN NEW.name = '[考试] second' BEGIN SELECT RAISE(ABORT, 'second exam failure'); END")
            fixture.fetchExams = { DataState(listOf(exam("first"), exam("second"))) }
            val result = CompletableDeferred<AutoImportResult>()
            fixture.importer.startAutoImportCurrentTimetable(true) { result.complete(it) }!!.join()
            assertEquals(AutoImportResult.FAILED, result.await())
            assertTrue("Failed exam batch is completely retryable", fixture.database.eventItemDao().getExamEventsSync().isEmpty())
            assertEquals(2, fixture.events().count { it.type == EventItem.TYPE.CLASS })
            fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_second_exam")
            val retried = CompletableDeferred<AutoImportResult>()
            fixture.importer.startAutoImportCurrentTimetable(true) { retried.complete(it) }!!.join()
            assertEquals(AutoImportResult.SUCCESS, retried.await())
            assertEquals(2, fixture.database.eventItemDao().getExamEventsSync().size)
        }
    }
}
