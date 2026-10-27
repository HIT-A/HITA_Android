package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TypedAgentBlockingAdapterTest {
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
    private fun provider(session: Session) = SimpleAgentProvider { session }

    @Test fun allTypedTerminalKindsKeepIdentityAndAlwaysDispose() {
        val terminals = listOf(AgentToolResult.Success("ok", 2), AgentToolResult.failure("business"),
            AgentToolResult.timeout(10, 2), AgentToolResult.cancelled(1))
        terminals.forEach { expected ->
            val session = Session(expected)
            assertSame(expected, TypedAgentBlockingAdapter.run(Unit, provider(session), {}))
            assertEquals(1, session.disposals)
        }
    }

    @Test fun outerDeadlineRemainsTypedTimeoutAndDisposesPendingWork() {
        val session = Session(null)
        val result = TypedAgentBlockingAdapter.run(Unit, provider(session), {}, timeoutMs = 1)
        assertEquals(AgentToolErrorType.TIMEOUT, result.errorType)
        assertEquals(1, session.disposals)
        assertEquals(1, session.cancellations)
    }

    @Test fun cleanupExceptionDoesNotReplaceAnAlreadySuccessfulOutcome() {
        var disposals = 0
        val provider = SimpleAgentProvider { object : AgentSession<Unit, String> {
            override fun run(input: Unit, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                onResult(AgentToolResult.success("committed")); return AgentCancellation.NONE
            }
            override fun dispose() { disposals++; error("cleanup") }
        } }
        assertEquals("committed", TypedAgentBlockingAdapter.run(Unit, provider, {}).data)
        assertEquals(1, disposals)
    }

    @Test fun synchronousSessionCancellationIsNotBusinessFailure() {
        var disposed = false
        val provider = SimpleAgentProvider { object : AgentSession<Unit, String> {
            override fun run(input: Unit, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                throw java.util.concurrent.CancellationException("stop")
            }
            override fun dispose() { disposed = true }
        } }
        assertEquals(AgentToolErrorType.CANCELLED, TypedAgentBlockingAdapter.run(Unit, provider, {}).errorType)
        assertTrue(disposed)
    }

    @Test fun interruptedWaitReturnsCancelledRestoresInterruptAndDisposes() {
        val session = Session(null)
        val result = AtomicReference<AgentToolResult<String>>()
        var interruptRestored = false
        val thread = Thread {
            result.set(TypedAgentBlockingAdapter.run(Unit, provider(session), {}))
            interruptRestored = Thread.currentThread().isInterrupted
        }
        thread.start()
        assertTrue(session.started.await(5, TimeUnit.SECONDS))
        thread.interrupt(); thread.join(5000)
        assertFalse(thread.isAlive)
        assertEquals(AgentToolErrorType.CANCELLED, result.get().errorType)
        assertTrue(interruptRestored)
        assertEquals(1, session.disposals)
        assertEquals(1, session.cancellations)
    }
}
