package cn.limpu.hita.agent.core

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface AgentSession<I, O> {
    fun run(
        input: I,
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (AgentToolResult<O>) -> Unit,
    ): AgentCancellation
    fun dispose() {}
}

interface AgentProvider<I, O> { fun createSession(): AgentSession<I, O> }

/** Each run owns its cancellation; replacing a run cannot cancel a reentrant newer run. */
class OrchestratorAgentSession<I, O>(
    private val orchestrator: AgentOrchestrator<I, O>,
    private val scheduler: AgentExecutionScheduler = MainAgentExecutionScheduler(),
) : AgentSession<I, O> {
    private val lock = Any()
    private var disposed = false
    private var active: Run? = null

    override fun run(input: I, onTrace: (AgentTraceEvent) -> Unit, onResult: (AgentToolResult<O>) -> Unit): AgentCancellation {
        val run = Run(input, onTrace, onResult)
        val previous = synchronized(lock) {
            if (disposed) run.cancelled.set(true)
            active.also { if (!disposed) active = run }
        }
        previous?.cancel()
        scheduler.schedule(0, run::start)
        return run
    }

    override fun dispose() {
        val previous = synchronized(lock) { disposed = true; active.also { active = null } }
        previous?.cancel()
    }

    private inner class Run(val input: I, val onTrace: (AgentTraceEvent) -> Unit, val onResult: (AgentToolResult<O>) -> Unit) : AgentCancellation {
        val cancelled = AtomicBoolean(false)
        private var terminal = false
        private val work = AtomicReference(AgentCancellation.NONE)

        fun start() {
            if (terminal) return
            if (cancelled.get()) { deliver(AgentToolResult.cancelled()); return }
            try {
                val handle = orchestrator.run(input, { trace -> scheduler.schedule(0) {
                    if (!terminal && !cancelled.get()) notifyAgentObserver { onTrace(trace) }
                } }, { result -> scheduler.schedule(0) { deliver(result) } })
                work.set(handle)
                if (cancelled.get()) { cancelWork(); deliver(AgentToolResult.cancelled()) }
            } catch (_: CancellationException) {
                scheduler.schedule(0) { deliver(AgentToolResult.cancelled()) }
            } catch (error: Exception) {
                scheduler.schedule(0) { deliver(AgentToolResult.failure(error.message ?: "智能体执行失败")) }
            }
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                // Set the executor cancellation flag before an already queued start can run.
                cancelWork()
                scheduler.schedule(0) { if (!terminal) deliver(AgentToolResult.cancelled()) }
            }
        }

        private fun cancelWork() {
            val handle = work.getAndSet(AgentCancellation.NONE)
            notifyAgentObserver { handle.cancel() }
        }

        private fun deliver(result: AgentToolResult<O>) {
            if (terminal) return
            terminal = true
            synchronized(lock) { if (active === this) active = null }
            notifyAgentObserver { onResult(if (cancelled.get()) AgentToolResult.cancelled(result.attempt) else result) }
        }
    }
}

class SimpleAgentProvider<I, O>(private val sessionFactory: () -> AgentSession<I, O>) : AgentProvider<I, O> {
    override fun createSession(): AgentSession<I, O> = sessionFactory()
}
