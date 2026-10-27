package cn.limpu.hita.agent.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AgentToolExecutorTest {
    private val clock = ManualAgentScheduler()
    private val executor = AgentToolExecutor(clock)
    private val results = mutableListOf<AgentToolResult<String>>()
    private val policy = AgentToolExecutionPolicy(timeoutMs = 10, retryCount = 1, retryDelayMs = 5)

    private class Tool(
        override val retrySafety: AgentRetrySafety = AgentRetrySafety.NEVER,
        val invoke: (Int, (AgentToolResult<String>) -> Unit) -> Unit = { _, _ -> },
    ) : AgentTool<Unit, String> {
        override val name = "test"
        var calls = 0
        var cancellations = 0
        val callbacks = mutableListOf<(AgentToolResult<String>) -> Unit>()
        override fun execute(input: Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
            callbacks += onResult
            invoke(++calls, onResult)
            return AgentCancellation { cancellations++ }
        }
    }

    private fun start(tool: Tool) = executor.execute(tool, Unit, policy, {}, results::add)

    @Test fun synchronousSuccessAndDuplicateCallbacksHaveOneTerminalResultAndNoTimer() {
        val tool = Tool { _, callback ->
            callback(AgentToolResult.success("first"))
            callback(AgentToolResult.failure("duplicate"))
        }
        start(tool)
        assertTrue(results.isEmpty())
        clock.runCurrent()
        clock.advanceBy(100)
        assertEquals(listOf(AgentToolResult.Success("first", 1)), results)
        assertEquals(1, tool.calls)
        assertEquals(0, clock.pending)
    }

    @Test fun synchronousThrowIsTerminalFailureWithoutAbandonedTimeoutRetry() {
        val tool = Tool { _, _ -> error("password=secret failed") }
        start(tool)
        clock.advanceBy(100)
        assertEquals(1, tool.calls)
        assertEquals(AgentToolErrorType.TOOL_FAILURE, results.single().errorType)
        assertFalse(results.single().error.orEmpty().contains("secret"))
        assertEquals(0, clock.pending)
    }

    @Test fun callbackThenThrowKeepsFirstTerminalSignal() {
        start(Tool { _, callback -> callback(AgentToolResult.success("committed")); error("after callback") })
        clock.advanceBy(100)
        assertEquals("committed", results.single().data)
    }

    @Test fun throwingObserversCannotRepeatASuccessfulSideEffect() {
        val tool = Tool(AgentRetrySafety.IDEMPOTENT) { _, callback -> callback(AgentToolResult.success("ok")) }
        executor.execute(tool, Unit, policy, { error("trace observer") }) {
            results += it
            error("result observer")
        }
        clock.advanceBy(100)
        assertEquals(1, tool.calls)
        assertEquals(1, results.size)
    }

    @Test fun idempotentReadRetriesBusinessFailureAndKeepsFinalAttempt() {
        val tool = Tool(AgentRetrySafety.IDEMPOTENT) { count, callback ->
            callback(if (count == 1) AgentToolResult.failure("temporary") else AgentToolResult.success("read"))
        }
        start(tool)
        clock.advanceBy(5)
        assertEquals(2, tool.calls)
        assertEquals(1, tool.cancellations)
        assertEquals(AgentToolResult.Success("read", 2), results.single())
        assertEquals(0, clock.pending)
    }

    @Test fun timedOutAttemptsCancelWorkAndRejectLateCallbacks() {
        val tool = Tool(AgentRetrySafety.IDEMPOTENT)
        start(tool)
        clock.advanceBy(10)
        tool.callbacks.first()(AgentToolResult.success("stale"))
        clock.advanceBy(5)
        assertTrue(results.isEmpty())
        assertEquals(2, tool.calls)
        clock.advanceBy(10)
        tool.callbacks.last()(AgentToolResult.success("late"))
        clock.runCurrent()
        assertEquals(AgentToolErrorType.TIMEOUT, results.single().errorType)
        assertEquals(2, results.single().attempt)
        assertEquals(2, tool.cancellations)
        assertEquals(0, clock.pending)
    }

    @Test fun defaultNonIdempotentWriteIsNeverReplayedOnTimeout() {
        val tool = Tool()
        start(tool)
        clock.advanceBy(100)
        assertEquals(1, tool.calls)
        assertEquals(AgentToolErrorType.TIMEOUT, results.single().errorType)
    }

    @Test fun cancellationBeforeStartDoesNotInvokeTool() {
        val tool = Tool()
        val handle = start(tool)
        handle.cancel(); handle.cancel()
        clock.advanceBy(100)
        assertEquals(0, tool.calls)
        assertEquals(AgentToolErrorType.CANCELLED, results.single().errorType)
        assertEquals(0, results.single().attempt)
        assertEquals(0, clock.pending)
    }

    @Test fun cancellationOfActiveWorkCancelsToolAndSuppressesLateSuccess() {
        val tool = Tool(AgentRetrySafety.IDEMPOTENT)
        val handle = start(tool)
        clock.runCurrent()
        handle.cancel()
        tool.callbacks.single()(AgentToolResult.success("late"))
        clock.advanceBy(100)
        assertEquals(AgentToolErrorType.CANCELLED, results.single().errorType)
        assertEquals(1, tool.cancellations)
        assertEquals(1, tool.calls)
        assertEquals(0, clock.pending)
    }

    @Test fun cancelDuringRetryDelayPreventsNextInvocation() {
        val tool = Tool(AgentRetrySafety.IDEMPOTENT) { _, callback -> callback(AgentToolResult.failure("temporary")) }
        val handle = start(tool)
        clock.runCurrent()
        handle.cancel()
        clock.advanceBy(100)
        assertEquals(1, tool.calls)
        assertEquals(AgentToolErrorType.CANCELLED, results.single().errorType)
        assertEquals(0, clock.pending)
    }

    @Test fun toolCancellationAndThrownCancellationNeverRetry() {
        for (throws in listOf(false, true)) {
            results.clear()
            val tool = Tool(AgentRetrySafety.IDEMPOTENT) { _, callback ->
                if (throws) throw CancellationException("cancel")
                callback(AgentToolResult.cancelled())
            }
            start(tool); clock.advanceBy(100)
            assertEquals(1, tool.calls)
            assertEquals(AgentToolErrorType.CANCELLED, results.single().errorType)
        }
    }

    @Test fun exhaustedFailuresKeepTypeAttemptAndSanitizeTraceAndMessage() {
        val traces = mutableListOf<AgentTraceEvent>()
        val tool = Tool(AgentRetrySafety.IDEMPOTENT) { _, callback -> callback(AgentToolResult.failure("access_token=private failure")) }
        executor.execute(tool, Unit, policy, traces::add, results::add)
        clock.advanceBy(100)
        assertEquals(AgentToolErrorType.TOOL_FAILURE, results.single().errorType)
        assertEquals(2, results.single().attempt)
        assertFalse(traces.joinToString().contains("private"))
        assertFalse(results.single().error.orEmpty().contains("private"))
    }

    @Test fun invalidAndOverflowPoliciesAreRejectedIncludingCopy() {
        val invalid = listOf<() -> AgentToolExecutionPolicy>(
            { policy.copy(timeoutMs = 0) }, { policy.copy(timeoutMs = -1) },
            { policy.copy(timeoutMs = Long.MAX_VALUE) }, { policy.copy(retryDelayMs = -1) },
            { policy.copy(retryDelayMs = Long.MAX_VALUE) }, { policy.copy(retryCount = -1) },
            { policy.copy(retryCount = Int.MAX_VALUE) },
        )
        invalid.forEach { assertTrue(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
    }

    @Test fun resultMappingPreservesFailureMetadataAndRejectsNullSuccess() {
        val timeout = AgentToolResult.timeout(10, 2)
        assertSame(timeout, timeout.map { "unused" })
        assertEquals(AgentToolResult.Success(4, 2), AgentToolResult.Success("read", 2).map { it.length })
        assertTrue(runCatching { AgentToolResult.success<String?>(null) }.exceptionOrNull() is IllegalArgumentException)
    }
}
