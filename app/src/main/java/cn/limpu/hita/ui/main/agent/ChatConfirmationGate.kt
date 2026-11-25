package cn.limpu.hita.ui.main.agent

import cn.limpu.hita.agent.tools.ConfirmationGate
import cn.limpu.hita.agent.tools.ConfirmationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** One outstanding dialog, shared across requests. Tools may ask from a dispatcher other than Main. */
internal class ChatConfirmationGate(private val timeoutMs: Long = 120_000) {
    data class Owner(val sessionId: String, val requestId: Long)

    // Reference identity matters: even two identical prompts in one request need separate consent.
    class Pending(val owner: Owner, val request: ConfirmationRequest)

    private class Waiter(val pending: Pending, val job: Job, val onApproved: () -> Unit) {
        val decision = CompletableDeferred<ConfirmationGate.Decision>()
    }

    private val lock = Any()
    private val _state = MutableStateFlow<Pending?>(null)
    val state: StateFlow<Pending?> = _state.asStateFlow()
    private var outstanding: Waiter? = null

    fun bind(owner: Owner, onApproved: () -> Unit = {}): ConfirmationGate = ConfirmationGate { request ->
        withTimeoutOrNull(timeoutMs) {
            val job = checkNotNull(currentCoroutineContext()[Job])
            val waiter = Waiter(Pending(owner, request), job, onApproved)
            synchronized(lock) {
                job.ensureActive()
                if (outstanding != null) return@withTimeoutOrNull ConfirmationGate.Decision.UNAVAILABLE
                outstanding = waiter
                _state.value = waiter.pending
            }
            try {
                waiter.decision.await()
            } finally {
                take { it === waiter }
            }
        } ?: ConfirmationGate.Decision.UNAVAILABLE
    }

    fun resolve(pending: Pending, visibleSessionId: String?, approved: Boolean): Boolean {
        val waiter = take { it.pending === pending && pending.owner.sessionId == visibleSessionId && it.job.isActive }
            ?: return false
        if (approved) waiter.onApproved()
        waiter.decision.complete(if (approved) ConfirmationGate.Decision.APPROVED else ConfirmationGate.Decision.REJECTED)
        return true
    }

    fun cancel(owner: Owner) {
        take { it.pending.owner == owner }?.decision?.complete(ConfirmationGate.Decision.REJECTED)
    }

    fun cancelAll(sessionId: String? = null) {
        take { sessionId == null || it.pending.owner.sessionId == sessionId }
            ?.decision?.complete(ConfirmationGate.Decision.REJECTED)
    }

    private inline fun take(matches: (Waiter) -> Boolean): Waiter? = synchronized(lock) {
        val waiter = outstanding?.takeIf(matches) ?: return@synchronized null
        outstanding = null
        _state.value = null
        waiter
    }
}

internal fun ChatConfirmationGate.Pending.isVisibleIn(sessionId: String?, foreground: Boolean): Boolean =
    foreground && owner.sessionId == sessionId
