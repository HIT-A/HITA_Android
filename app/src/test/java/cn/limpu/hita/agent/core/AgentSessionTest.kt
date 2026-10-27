package cn.limpu.hita.agent.core

import org.junit.Assert.*
import org.junit.Test

class AgentSessionTest {
    private val clock = ManualAgentScheduler()
    private class Engine : AgentEngine<String, String> {
        val calls = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val callbacks = mutableMapOf<String, (AgentToolResult<String>) -> Unit>()
        var duringRun: (String) -> Unit = {}
        override fun run(input: String, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
            calls += input
            callbacks[input] = onResult
            duringRun(input)
            return AgentCancellation { cancelled += input }
        }
    }
    private val engine = Engine()
    private val session = OrchestratorAgentSession(AgentOrchestrator(engine), clock)
    private val results = mutableListOf<Pair<String, AgentToolResult<String>>>()
    private fun run(id: String) = session.run(id, {}) { results += id to it }

    @Test fun replacementCancelsOldWorkAndKeepsNewResult() {
        run("old"); clock.runCurrent()
        run("new"); clock.runCurrent()
        engine.callbacks.getValue("old")(AgentToolResult.success("late"))
        engine.callbacks.getValue("new")(AgentToolResult.success("current"))
        clock.runCurrent()
        assertEquals(listOf("old"), engine.cancelled)
        assertEquals(listOf("old", "new"), results.map { it.first })
        assertEquals(AgentToolErrorType.CANCELLED, results.first().second.errorType)
        assertEquals("current", results.last().second.data)
    }

    @Test fun disposeDuringRetryCancelsExecutorAndProducesOneCancelledTerminal() {
        var calls = 0
        val tool = object : AgentTool<Unit, String> {
            override val name = "read"
            override val retrySafety = AgentRetrySafety.IDEMPOTENT
            override fun execute(input: Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                calls++; onResult(AgentToolResult.failure("retry")); return AgentCancellation.NONE
            }
        }
        val executor = AgentToolExecutor(clock)
        val runner = object : AgentEngine<Unit, String> {
            override fun run(input: Unit, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit) =
                executor.execute(tool, input, AgentToolExecutionPolicy(retryDelayMs = 20), onTrace, onResult)
        }
        val session = OrchestratorAgentSession(AgentOrchestrator(runner), clock)
        val terminal = mutableListOf<AgentToolResult<String>>()
        session.run(Unit, {}, terminal::add); clock.runCurrent()
        session.dispose(); session.dispose(); clock.advanceBy(100)
        assertEquals(1, calls)
        assertEquals(AgentToolErrorType.CANCELLED, terminal.single().errorType)
        assertEquals(0, clock.pending)
    }

    @Test fun disposeBeforeQueuedStartAndRunAfterDisposeNeverLaunchEngine() {
        run("before"); session.dispose(); run("after"); clock.runCurrent()
        assertTrue(engine.calls.isEmpty())
        assertEquals(2, results.size)
        assertTrue(results.all { it.second.errorType == AgentToolErrorType.CANCELLED })
    }

    @Test fun cancellationCallbackReentryDoesNotCancelNewerRun() {
        session.run("old", {}) {
            results += "old" to it
            run("reentrant")
        }
        clock.runCurrent(); run("replaced"); clock.runCurrent()
        engine.callbacks.getValue("reentrant")(AgentToolResult.success("newest")); clock.runCurrent()
        assertEquals(listOf("old", "reentrant"), engine.calls)
        assertEquals("newest", results.last().second.data)
        assertEquals(listOf("old"), engine.cancelled)
        assertEquals(1, results.count { it.first == "replaced" })
    }

    @Test fun engineReentryBeforeReturningHandleCancelsItsOwnOldWork() {
        engine.duringRun = { if (it == "old") run("new") }
        run("old"); clock.runCurrent()
        engine.callbacks.getValue("new")(AgentToolResult.Success("new", 2)); clock.runCurrent()
        assertEquals(listOf("old"), engine.cancelled)
        assertEquals(2, results.last().second.attempt)
        assertEquals(AgentToolErrorType.CANCELLED, results.first().second.errorType)
    }

    @Test fun synchronousCompletionObserverMayRunAgainWithoutLosingOwnership() {
        engine.duringRun = { engine.callbacks.getValue(it)(AgentToolResult.success(it)) }
        session.run("first", {}) { run("second") }
        clock.runCurrent()
        assertEquals(listOf("first", "second"), engine.calls)
        assertEquals("second", results.single().second.data)
        assertTrue(engine.cancelled.isEmpty())
    }

    @Test fun duplicateResultsAndThrowingObserverNeverReplayEngine() {
        var delivered = 0
        session.run("only", {}) { delivered++; error("observer") }; clock.runCurrent()
        repeat(2) { engine.callbacks.getValue("only")(AgentToolResult.success("same")) }
        clock.runCurrent()
        assertEquals(1, delivered)
        assertEquals(listOf("only"), engine.calls)
    }

    @Test fun engineCallbackThenThrowPreservesFirstSuccess() {
        engine.duringRun = {
            engine.callbacks.getValue(it)(AgentToolResult.success("first"))
            error("after callback")
        }
        run("callback"); clock.runCurrent()
        assertEquals("first", results.single().second.data)
    }

    @Test fun disposeFromStartTraceStopsAlreadyQueuedToolStart() {
        var calls = 0
        val tool = object : AgentTool<Unit, String> {
            override val name = "write"
            override fun execute(input: Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                calls++; return AgentCancellation.NONE
            }
        }
        val executor = AgentToolExecutor(clock)
        val engine = object : AgentEngine<Unit, String> {
            override fun run(input: Unit, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                onTrace(AgentTraceEvent("start", "start"))
                return executor.execute(tool, input, onTrace = onTrace, onResult = onResult)
            }
        }
        val session = OrchestratorAgentSession(AgentOrchestrator(engine), clock)
        val terminal = mutableListOf<AgentToolResult<String>>()
        session.run(Unit, { session.dispose() }, terminal::add)
        clock.advanceBy(100)
        assertEquals(0, calls)
        assertEquals(AgentToolErrorType.CANCELLED, terminal.single().errorType)
        assertEquals(0, clock.pending)
    }

    @Test fun synchronousEngineFailureIsTypedAndSanitized() {
        engine.duringRun = { error("password=secret") }
        run("throw"); clock.runCurrent()
        assertEquals(AgentToolErrorType.TOOL_FAILURE, results.single().second.errorType)
        assertFalse(results.single().second.error.orEmpty().contains("secret"))
    }
}
