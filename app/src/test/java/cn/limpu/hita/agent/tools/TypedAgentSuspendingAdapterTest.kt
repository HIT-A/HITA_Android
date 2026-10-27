package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TypedAgentSuspendingAdapterTest {
    private class Session(val answer: AgentToolResult<String>?) : AgentSession<Unit, String> {
        var disposals = 0
        var cancellations = 0
        val started = CountDownLatch(1)
        override fun run(input: Unit, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
            started.countDown()
            answer?.let(onResult)
            return AgentCancellation { cancellations++ }
        }
        override fun dispose() { disposals++ }
    }
    private fun provider(session: AgentSession<Unit, String>) = SimpleAgentProvider { session }

    @Test fun allTypedTerminalKindsKeepIdentityAndAlwaysDispose() = runBlocking {
        val terminals = listOf(AgentToolResult.Success("ok", 2), AgentToolResult.failure("business"),
            AgentToolResult.timeout(10, 2), AgentToolResult.cancelled(1))
        terminals.forEach { expected ->
            val session = Session(expected)
            assertSame(expected, TypedAgentSuspendingAdapter.run(Unit, provider(session), {}))
            assertEquals(1, session.disposals)
        }
    }

    @Test fun outerDeadlineRemainsTypedTimeoutAndDisposesPendingWork() = runBlocking {
        val session = Session(null)
        val result = TypedAgentSuspendingAdapter.run(Unit, provider(session), {}, timeoutMs = 1)
        assertEquals(AgentToolErrorType.TIMEOUT, result.errorType)
        assertEquals(1, session.disposals)
        assertEquals(1, session.cancellations)
    }

    @Test fun cleanupExceptionDoesNotReplaceAnAlreadySuccessfulOutcome() = runBlocking {
        var disposals = 0
        val provider = SimpleAgentProvider { object : AgentSession<Unit, String> {
            override fun run(input: Unit, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                onResult(AgentToolResult.success("committed")); return AgentCancellation.NONE
            }
            override fun dispose() { disposals++; error("cleanup") }
        } }
        assertEquals("committed", TypedAgentSuspendingAdapter.run(Unit, provider, {}).data)
        assertEquals(1, disposals)
    }

    @Test fun synchronousSessionCancellationIsNotBusinessFailure() = runBlocking {
        var disposed = false
        val provider = SimpleAgentProvider { object : AgentSession<Unit, String> {
            override fun run(input: Unit, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                throw java.util.concurrent.CancellationException("stop")
            }
            override fun dispose() { disposed = true }
        } }
        assertEquals(AgentToolErrorType.CANCELLED, TypedAgentSuspendingAdapter.run(Unit, provider, {}).errorType)
        assertTrue(disposed)
    }

    @Test fun parentCancellationCancelsRequestAndDisposesSession() = runBlocking {
        val session = Session(null)
        val job = launch(Dispatchers.Default) {
            TypedAgentSuspendingAdapter.run(Unit, provider(session), {})
        }
        assertTrue(session.started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertEquals(1, session.disposals)
        assertEquals(1, session.cancellations)
    }

    @Test fun handleReturnedAfterParentCancellationIsCancelledExactlyOnce() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val disposals = AtomicInteger()
        val cancellations = AtomicInteger()
        val session = object : AgentSession<Unit, String> {
            override fun run(
                input: Unit,
                onTrace: (AgentTraceEvent) -> Unit,
                onResult: (AgentToolResult<String>) -> Unit,
            ): AgentCancellation {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                return AgentCancellation { cancellations.incrementAndGet() }
            }

            override fun dispose() {
                disposals.incrementAndGet()
                disposed.countDown()
            }
        }
        val job = launch(Dispatchers.Default) {
            TypedAgentSuspendingAdapter.run(Unit, provider(session), {})
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        assertTrue(disposed.await(5, TimeUnit.SECONDS))
        release.countDown()
        job.join()
        assertEquals(1, cancellations.get())
    }

    @Test fun duplicateAndLateCallbacksDoNotReplaceTheFirstTerminalResult() = runBlocking {
        val callback = AtomicReference<(AgentToolResult<String>) -> Unit>()
        val session = object : AgentSession<Unit, String> {
            override fun run(
                input: Unit,
                onTrace: (AgentTraceEvent) -> Unit,
                onResult: (AgentToolResult<String>) -> Unit,
            ): AgentCancellation {
                callback.set(onResult)
                onResult(AgentToolResult.success("first"))
                onResult(AgentToolResult.failure("duplicate"))
                return AgentCancellation.NONE
            }
        }
        val result = TypedAgentSuspendingAdapter.run(Unit, provider(session), {})
        assertEquals("first", result.data)
        callback.get()(AgentToolResult.success("late"))
        assertEquals("first", result.data)
    }
}
