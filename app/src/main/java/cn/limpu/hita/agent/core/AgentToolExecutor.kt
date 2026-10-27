package cn.limpu.hita.agent.core

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class AgentToolExecutionPolicy(
    val timeoutMs: Long = 4500L,
    val retryCount: Int = 1,
    val retryDelayMs: Long = 150L,
) {
    init {
        // Finite bounds also prevent Handler time arithmetic and attempt counter overflow.
        require(timeoutMs in 1..MAX_DELAY_MS) { "timeoutMs must be within one day" }
        require(retryDelayMs in 0..MAX_DELAY_MS) { "retryDelayMs must be within one day" }
        require(retryCount in 0..100) { "retryCount must be between 0 and 100" }
    }
    companion object { const val MAX_DELAY_MS = 86_400_000L }
}

class AgentToolExecutor(
    private val scheduler: AgentExecutionScheduler = MainAgentExecutionScheduler(),
) {
    /** All traces/results run serially on scheduler; cancel may be called from any thread. */
    fun <I, O> execute(
        tool: AgentTool<I, O>,
        input: I,
        policy: AgentToolExecutionPolicy = AgentToolExecutionPolicy(),
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (AgentToolResult<O>) -> Unit,
    ): AgentCancellation = Execution(tool, input, policy, onTrace, onResult).also { it.start() }

    private inner class Execution<I, O>(
        val tool: AgentTool<I, O>, val input: I, val policy: AgentToolExecutionPolicy,
        val onTrace: (AgentTraceEvent) -> Unit, val onResult: (AgentToolResult<O>) -> Unit,
    ) : AgentCancellation {
        private val cancelled = AtomicBoolean(false)
        private var terminal = false
        private var attempt = 0
        private var current: Attempt? = null
        private var scheduled = AgentCancellation.NONE
        private val maxAttempts = if (tool.retrySafety == AgentRetrySafety.IDEMPOTENT) policy.retryCount + 1 else 1

        private inner class Attempt {
            var finished = false
            var timer = AgentCancellation.NONE
            var work = AgentCancellation.NONE
        }

        fun start() { scheduled = scheduler.schedule(0, ::runAttempt) }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) scheduler.schedule(0) {
                if (!terminal) finish(AgentToolResult.cancelled(attempt))
            }
        }

        private fun trace(stage: String, message: String, payload: String) = notifyAgentObserver {
            onTrace(AgentTraceEvent(stage, message, AgentTraceSanitizer.sanitizePayload(payload)))
        }

        private fun runAttempt() {
            if (terminal) return
            if (cancelled.get()) { finish(AgentToolResult.cancelled(attempt)); return }
            attempt++
            val owner = Attempt().also { current = it }
            trace("tool_attempt", "正在执行工具：${tool.name}", "attempt=$attempt/$maxAttempts")
            if (cancelled.get()) { finish(AgentToolResult.cancelled(attempt)); return }
            owner.timer = scheduler.schedule(policy.timeoutMs) {
                accept(owner, AgentToolResult.timeout(policy.timeoutMs, attempt))
            }
            try {
                owner.work = tool.execute(input) { result ->
                    // Always enqueue: an observer exception must never re-enter tool.execute.
                    scheduler.schedule(0) { accept(owner, result) }
                }
                if (cancelled.get()) finish(AgentToolResult.cancelled(attempt))
            } catch (_: CancellationException) {
                scheduler.schedule(0) { accept(owner, AgentToolResult.cancelled(attempt)) }
            } catch (error: Exception) {
                scheduler.schedule(0) { accept(owner, AgentToolResult.failure(error.message ?: "工具执行失败")) }
            }
        }

        private fun accept(owner: Attempt, result: AgentToolResult<O>) {
            if (terminal || current !== owner || owner.finished) return
            if (cancelled.get()) { finish(AgentToolResult.cancelled(attempt)); return }
            owner.finished = true
            owner.timer.cancel()
            val delivered = when (result) {
                is AgentToolResult.Success -> result.atAttempt(attempt)
                is AgentToolResult.Failure -> result.copy(
                    message = AgentTraceSanitizer.sanitizeError(result.message), attempt = attempt,
                )
            }
            if (delivered is AgentToolResult.Failure) {
                // Stop the old attempt before a retry; legacy IO may still finish after cancel.
                cancelWork(owner)
                val retry = delivered.kind != AgentToolErrorType.CANCELLED && attempt < maxAttempts
                trace(if (delivered.kind == AgentToolErrorType.TIMEOUT) "tool_timeout" else "tool_failure",
                    delivered.message, "tool=${tool.name},attempt=$attempt/$maxAttempts,retry=$retry")
                if (cancelled.get()) { finish(AgentToolResult.cancelled(attempt)); return }
                if (retry) { scheduled = scheduler.schedule(policy.retryDelayMs, ::runAttempt); return }
            }
            finish(delivered)
        }

        private fun cancelWork(owner: Attempt) {
            val work = owner.work
            owner.work = AgentCancellation.NONE
            notifyAgentObserver { work.cancel() }
        }

        private fun finish(result: AgentToolResult<O>) {
            if (terminal) return
            terminal = true
            scheduled.cancel()
            current?.let {
                it.finished = true
                it.timer.cancel()
                if (result.errorType != null) cancelWork(it)
            }
            current = null
            notifyAgentObserver { onResult(result) }
        }
    }
}
