package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.agent.core.AgentTraceEvent
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Bridges the callback based typed agent API to a cancellable coroutine.
 *
 * The old ReAct callers waited on a CountDownLatch.  That blocked the caller
 * thread and could not observe cancellation until the timeout elapsed.  This
 * bridge keeps the typed result and trace callbacks, while making the parent
 * coroutine own the request and session lifetime.
 */
internal object TypedAgentSuspendingAdapter {
    suspend fun <I, O> run(
        input: I,
        provider: AgentProvider<I, O>,
        onTrace: (AgentTraceEvent) -> Unit,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): AgentToolResult<O> {
        require(timeoutMs in 1..MAX_TIMEOUT_MS)

        val session = provider.createSession()
        // A session may return its cancellation handle after the parent has
        // already been cancelled. Keep the handle pending until either side
        // claims it, so that cancellation is delivered exactly once.
        val request = AtomicReference<AgentCancellation?>(null)
        val cancellationRequested = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)

        fun dispose() {
            if (disposed.compareAndSet(false, true)) {
                runCatching { session.dispose() }
            }
        }

        fun cancelRequest() {
            if (cancellationRequested.compareAndSet(false, true)) {
                runCatching { request.getAndSet(null)?.cancel() }
                dispose()
            }
        }

        fun installRequest(handle: AgentCancellation) {
            request.set(handle)
            if (cancellationRequested.get()) {
                runCatching { request.getAndSet(null)?.cancel() }
            }
        }

        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<AgentToolResult<O>> { continuation ->
                    val deliver: (AgentToolResult<O>) -> Unit = { response ->
                        if (continuation.isActive) {
                            runCatching { continuation.resume(response) }
                        }
                    }

                    continuation.invokeOnCancellation { cancelRequest() }

                    try {
                        installRequest(session.run(input, onTrace, deliver))
                    } catch (cancelled: CancellationException) {
                        if (continuation.isActive) {
                            continuation.resume(AgentToolResult.cancelled())
                        } else {
                            cancelRequest()
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) {
                            continuation.resume(
                                AgentToolResult.failure(error.message ?: "工具执行失败")
                            )
                        }
                    }
                }
            }
            result ?: run {
                cancelRequest()
                AgentToolResult.timeout(timeoutMs, 0)
            }
        } finally {
            dispose()
        }
    }

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 86_400_000L
}
