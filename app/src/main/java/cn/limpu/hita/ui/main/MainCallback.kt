package cn.limpu.hita.ui.main

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The uncancellable legacy worker retains only this slot, which releases its consumer on cancel. */
internal class MainCallback<T>(callback: (T) -> Unit) : AutoCloseable {
    private val consumer = AtomicReference<((T) -> Unit)?>(callback)
    fun deliver(value: T) { consumer.getAndSet(null)?.invoke(value) }
    override fun close() { consumer.set(null) }
}

internal suspend fun <T> awaitMainCallback(start: ((T) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        val slot = MainCallback<T> { if (continuation.isActive) continuation.resume(it) }
        continuation.invokeOnCancellation { slot.close() }
        try {
            start(slot::deliver)
        } catch (error: Exception) {
            slot.close()
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }
