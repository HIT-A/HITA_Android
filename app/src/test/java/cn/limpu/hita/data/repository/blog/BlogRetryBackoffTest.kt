package cn.limpu.hita.data.repository.blog

import cn.limpu.hita.data.repository.BlogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * PERF-02 regression: feed retry backoff must suspend rather than block the executing thread.
 *
 * The pre-fix code called `Thread.sleep(1500 * n)`, so a bounded single-thread executor spent
 * 4.5 s parked and every queued article read waited behind it.
 */
class BlogRetryBackoffTest {

    @Test
    fun `retry uses whole attempt budget then surfaces the last failure`() = runBlocking {
        val attempts = AtomicInteger(0)
        val failure = IllegalStateException("rss has no items")
        val thrown = runCatching {
            BlogRepository.retryWithBackoff(attempts = 3, backoffMillis = 1) {
                attempts.incrementAndGet()
                throw failure
            }
        }.exceptionOrNull()
        assertEquals(3, attempts.get())
        assertEquals(failure, thrown)
    }

    @Test
    fun `retry returns the first successful attempt without further attempts`() = runBlocking {
        val attempts = AtomicInteger(0)
        val value = BlogRepository.retryWithBackoff(attempts = 3, backoffMillis = 1) {
            if (attempts.incrementAndGet() == 3) "feed" else throw IllegalStateException("HTTP 503")
        }
        assertEquals("feed", value)
        assertEquals(3, attempts.get())
    }

    /** A single-thread executor must stay usable during the backoff window. */
    @Test
    fun `backoff does not occupy the single worker thread`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            runBlocking {
                val retryFinished = CompletableDeferred<Unit>()
                launch(dispatcher) {
                    runCatching {
                        BlogRepository.retryWithBackoff<String>(attempts = 3, backoffMillis = 300) {
                            throw IllegalStateException("HTTP 503")
                        }
                    }
                    retryFinished.complete(Unit)
                }
                // The retry now sits in a 300 ms backoff. A task queued behind it must run
                // immediately; with `Thread.sleep` it could only run after all backoff finished.
                val secondaryStarted = CompletableDeferred<Unit>()
                launch(dispatcher) { secondaryStarted.complete(Unit) }
                withTimeout(100) { secondaryStarted.await() }
                assertFalse("the worker thread was blocked by the backoff", retryFinished.isCompleted)
                withTimeout(2_000) { retryFinished.await() }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling a refresh stops the retry chain immediately`() = runBlocking {
        val attempts = AtomicInteger(0)
        val firstAttemptFailed = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            BlogRepository.retryWithBackoff(
                attempts = 3,
                backoffMillis = 10_000,
                onAttemptFailed = { _, _ -> firstAttemptFailed.complete(Unit) },
            ) {
                attempts.incrementAndGet()
                throw IllegalStateException("HTTP 503")
            }
        }
        withTimeout(2_000) { firstAttemptFailed.await() }
        val startedAt = System.nanoTime()
        job.cancel()
        job.join()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals("cancellation must abandon the pending attempt", 1, attempts.get())
        assertTrue("cancellation waited for the 10 s backoff instead of interrupting it (${elapsedMillis} ms)", elapsedMillis < 1_000)
    }
}
