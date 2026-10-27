package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.core.notifyAgentObserver
import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.agent.core.AgentTraceEvent
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Transitional adapter for the synchronous ReAct interface; 3.A2 owns its suspend migration. */
internal object TypedAgentBlockingAdapter {
    fun <I, O> run(
        input: I,
        provider: AgentProvider<I, O>,
        onTrace: (AgentTraceEvent) -> Unit,
        timeoutMs: Long = 30_000,
    ): AgentToolResult<O> {
        require(timeoutMs in 1..86_400_000L)
        val session = provider.createSession()
        val result = AtomicReference<AgentToolResult<O>>()
        val latch = CountDownLatch(1)
        var request = AgentCancellation.NONE
        try {
            request = session.run(input, onTrace) { response ->
                if (result.compareAndSet(null, response)) latch.countDown()
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                if (result.compareAndSet(null, AgentToolResult.timeout(timeoutMs, 0))) {
                    request.cancel()
                }
            }
        } catch (_: InterruptedException) {
            if (result.compareAndSet(null, AgentToolResult.cancelled())) request.cancel()
            Thread.currentThread().interrupt()
        } catch (_: CancellationException) {
            if (result.compareAndSet(null, AgentToolResult.cancelled())) request.cancel()
        } catch (error: Exception) {
            result.compareAndSet(null, AgentToolResult.failure(error.message ?: "工具执行失败"))
        } finally {
            // Cleanup failure must not replace a known terminal outcome or invite replay.
            notifyAgentObserver { session.dispose() }
        }
        return checkNotNull(result.get())
    }
}
