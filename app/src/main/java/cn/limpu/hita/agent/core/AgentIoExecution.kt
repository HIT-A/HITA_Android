package cn.limpu.hita.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a finite IO operation. A synchronous DB transaction already entered may still commit. */
internal fun <T> launchAgentIo(
    onResult: (AgentToolResult<T>) -> Unit,
    block: suspend CoroutineScope.() -> AgentToolResult<T>,
): AgentCancellation {
    val delivered = AtomicBoolean(false)
    fun deliver(result: AgentToolResult<T>) {
        if (delivered.compareAndSet(false, true)) notifyAgentObserver { onResult(result) }
    }
    val job = CoroutineScope(Dispatchers.IO).launch {
        val result = try {
            ensureActive()
            block().also { ensureActive() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AgentToolResult.failure(error.message ?: "工具执行失败")
        }
        deliver(result)
    }
    // Includes cancellation before the coroutine body starts.
    job.invokeOnCompletion { if (it is CancellationException) deliver(AgentToolResult.cancelled()) }
    return AgentCancellation { job.cancel() }
}
