package cn.limpu.hita.data.work

import androidx.lifecycle.MutableLiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerFactory
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.repository.BackgroundTermConsumerHarness
import cn.limpu.hita.data.repository.BackgroundTermConsumerHarness.Companion.main
import cn.limpu.hita.data.repository.BackgroundTermConsumerHarness.Companion.score
import cn.limpu.hita.data.repository.BackgroundTermConsumerHarness.Companion.term
import com.limpu.component.data.DataState
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScoreReminderSessionTest {
    @Test fun originalPersistedClassNameRestoresWithThePublicConstructorAndKeepsInputOutput() {
        BackgroundTermConsumerHarness().use { h ->
            h.reminderStore.setEnabled(false)
            val className = "cn.limpu.hita.data.work.ScoreReminderWorker"
            assertEquals(className, OneTimeWorkRequestBuilder<ScoreReminderWorker>().build().workSpec.workerClassName)
            val input = Data.Builder().putString("legacy-input", "retained").build()
            val parameters = h.workerParameters(input)
            val worker = requireNotNull(WorkerFactory.getDefaultWorkerFactory().createWorkerWithDefaultFallback(
                h.application, className, parameters,
            ))
            assertEquals(parameters.id, worker.id)
            assertEquals(input, worker.inputData)
            assertEquals(2, worker.runAttemptCount)
            val result = worker.startWork().get(10, TimeUnit.SECONDS)
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(Data.EMPTY, result.outputData)
            assertTrue(h.calls.isEmpty())
        }
    }

    @Test fun firstBaselineUsesTheCapturedSessionAndDoesNotNotify() {
        BackgroundTermConsumerHarness().use { h ->
            val item = score()
            h.scores = { _, _, type ->
                assertEquals(cn.limpu.hita.data.source.web.service.EASService.TestType.NORMAL, type)
                MutableLiveData(DataState(listOf(item)))
            }
            val result = worker(h).startWork().get(10, TimeUnit.SECONDS)
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(Data.EMPTY, result.outputData)
            assertEquals(setOf(ScoreReminderPolicy.scoreKey(item)), h.reminderStore.getKnownScores(key("1")))
            assertTrue(h.notifications.isEmpty())
            assertEquals(listOf("terms", "scores:${term().id}"), h.calls.toList())
            h.tokens.forEach { assertSame(h.tokens.first(), it) }
            assertTrue(h.database.scoreCacheDao().getTermsSync("BENBU:student").isEmpty())
        }
    }

    @Test fun partialFailureRetriesAndCommitsOnlyTheSuccessfulTermWithoutDuplicateNotification() {
        BackgroundTermConsumerHarness().use { h ->
            val current = term("2").apply { isCurrent = true }
            val previous = term("1")
            val item = score()
            h.terms = { DataState(listOf(previous, current)) }
            h.reminderStore.setKnownScores(key("2"), emptySet())
            h.reminderStore.setKnownScores(key("1"), setOf("old-baseline"))
            h.scores = { term, _, _ ->
                if (term.termCode == "2") MutableLiveData(DataState(listOf(item)))
                else MutableLiveData(DataState(DataState.STATE.FETCH_FAILED, "local failure"))
            }
            assertEquals(ListenableWorker.Result.retry(), worker(h).startWork().get(10, TimeUnit.SECONDS))
            assertEquals(listOf(listOf(item)), h.notifications.toList())
            assertEquals(setOf(ScoreReminderPolicy.scoreKey(item)), h.reminderStore.getKnownScores(key("2")))
            assertEquals(setOf("old-baseline"), h.reminderStore.getKnownScores(key("1")))
            assertEquals(ListenableWorker.Result.retry(), worker(h).startWork().get(10, TimeUnit.SECONDS))
            assertEquals(1, h.notifications.size)
        }
    }

    @Test fun cancellingTheWorkManagerFutureCancelsTermRequestBeforeScores() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            h.terms = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            val future = worker(h).startWork()
            withTimeout(10_000) { entered.await() }
            assertTrue(future.cancel(true))
            withTimeout(10_000) { stopped.await() }
            assertTrue(future.isCancelled)
            assertEquals(listOf("terms"), h.calls.toList())
            assertNull(h.reminderStore.getKnownScores(key("1")))
            assertTrue(h.notifications.isEmpty())
        }
    }

    @Test fun stoppingDuringSecondScoreQueryDiscardsTheFirstTermsStagedBaseline() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            h.terms = { DataState(listOf(term("2").apply { isCurrent = true }, term("1"))) }
            val pending = BackgroundTermConsumerHarness.ObservedValue<DataState<List<CourseScoreItem>>>()
            h.scores = { term, _, _ ->
                if (term.termCode == "2") MutableLiveData(DataState(listOf(score()))) else pending
            }
            val worker = worker(h)
            val future = worker.startWork()
            withTimeout(10_000) { pending.active.await() }
            worker.stop(0)
            withTimeout(10_000) { pending.inactive.await() }
            assertCancelled(future)
            main { assertFalse(pending.hasObservers()) }
            assertNull(h.reminderStore.getKnownScores(key("2")))
            assertNull(h.reminderStore.getKnownScores(key("1")))
            assertTrue(h.notifications.isEmpty())
        }
    }

    @Test fun accountSwitchWhileScoresArePendingCancelsWithoutRetryNotificationOrBaselineWrite() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val pending = BackgroundTermConsumerHarness.ObservedValue<DataState<List<CourseScoreItem>>>()
            h.scores = { _, _, _ -> pending }
            h.reminderStore.setKnownScores(key("1"), emptySet())
            val future = worker(h).startWork()
            withTimeout(10_000) { pending.active.await() }
            h.switchAccount()
            withTimeout(10_000) { pending.inactive.await() }
            assertCancelled(future)
            main { pending.value = DataState(listOf(score())); assertFalse(pending.hasObservers()) }
            assertEquals(emptySet<String>(), h.reminderStore.getKnownScores(key("1")))
            assertNull(h.reminderStore.getKnownScores("BENBU:other-student|${term().id}"))
            assertTrue(h.notifications.isEmpty())
        }
    }

    @Test fun remoteFailureRemainsRetryAndUnexpectedExceptionRemainsExceptional() {
        BackgroundTermConsumerHarness().use { h ->
            h.terms = { DataState(DataState.STATE.TOKEN_INVALID) }
            assertEquals(ListenableWorker.Result.retry(), worker(h).startWork().get(10, TimeUnit.SECONDS))
            h.terms = { throw IllegalStateException("injected remote defect") }
            try {
                worker(h).startWork().get(10, TimeUnit.SECONDS)
                error("Unexpected exceptions must not become a success or an automatic retry")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is IllegalStateException)
                assertEquals("injected remote defect", error.cause?.message)
            }
            assertTrue(h.notifications.isEmpty())
            assertNull(h.reminderStore.getKnownScores(key("1")))
        }
    }

    private fun worker(h: BackgroundTermConsumerHarness) = ScoreReminderWorker(
        h.application, h.workerParameters(), h.repository,
    ) { items -> h.notifications += items.toList() }

    private fun key(code: String) = ScoreReminderPolicy.baselineKey("BENBU:student", term(code).id)

    private fun assertCancelled(future: com.google.common.util.concurrent.ListenableFuture<ListenableWorker.Result>) {
        try {
            future.get(10, TimeUnit.SECONDS)
            error("Cancellation must not return a retry/success result")
        } catch (_: CancellationException) {
            // WorkManager's future is cancelled when stop() cancels the CoroutineWorker job.
        } catch (error: ExecutionException) {
            assertTrue("Unexpected cancellation result: ${error.cause}", error.cause is CancellationException)
        }
    }
}
