package cn.limpu.hita.data.repository.chat

import cn.limpu.hita.data.model.chat.ChatMessageRecord
import cn.limpu.hita.data.model.chat.ChatSession
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One application-owned queue preserves submission order across ViewModels. Accepted persistence
 * completes even when its UI awaiter is cancelled. Closing stops acceptance and drains queued work.
 */
@Singleton
class ChatSessionRepository @Inject constructor(private val store: ChatSessionStore) : Closeable {
    private fun interface Operation { fun run() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<Operation>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (operation in operations) operation.run()
    }.also { job -> job.invokeOnCompletion { scope.cancel() } }

    val sessions get() = store.sessions

    fun create(session: ChatSession): Deferred<Unit> = submit { store.create(session) }
    fun latest(): Deferred<ChatSession?> = submit { store.latest() }
    fun restore(sessionId: String): Deferred<List<ChatMessageRecord>> = submit { store.restore(sessionId) }
    fun delete(session: ChatSession): Deferred<ChatSession?> = submit { store.delete(session) }

    fun append(sessionId: String, message: ChatMessageRecord): Deferred<Boolean> {
        val captured = message.copy(resourceCards = message.resourceCards.toList())
        return submit { store.append(sessionId, captured) }
    }

    private fun <T> submit(action: () -> T): Deferred<T> {
        val completion = CompletableDeferred<T>()
        val operation = Operation {
            try { completion.complete(action()) }
            catch (error: Throwable) { completion.completeExceptionally(error) }
        }
        if (operations.trySend(operation).isFailure) {
            completion.completeExceptionally(IllegalStateException("Chat store is closed"))
        }
        return completion
    }

    override fun close() { operations.close() }

    /** For owners with a shorter lifetime than the application, including tests. */
    suspend fun awaitClosed() { close(); worker.join() }
}
