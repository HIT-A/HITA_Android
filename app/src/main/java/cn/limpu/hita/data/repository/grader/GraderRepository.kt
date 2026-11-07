package cn.limpu.hita.data.repository.grader

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.grader.GraderTask
import cn.limpu.hita.data.source.preference.GraderPreferenceSource
import cn.limpu.hita.data.source.web.grader.GraderAuthenticationException
import cn.limpu.hita.data.source.web.grader.GraderInvalidResponseException
import cn.limpu.hita.data.source.web.grader.GraderNetworkException
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import cn.limpu.hita.data.source.web.grader.GraderWebSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

enum class GraderError { AUTHENTICATION, NETWORK, STORAGE, INVALID_RESPONSE }

data class GraderState(
    val accountId: String? = null,
    val accountName: String? = null,
    val tasks: List<GraderTask> = emptyList(),
    val ready: Boolean = false,
    val syncing: Boolean = false,
    val needsLogin: Boolean = false,
    val error: GraderError? = null,
)

@Singleton
class GraderRepository internal constructor(
    private val store: GraderStore,
    private val publicationDispatcher: CoroutineDispatcher,
) {
    @Inject constructor(application: Application) : this(
        GraderPreferenceSource(application).let { GraderStore(it::read, it::write) },
        Dispatchers.Main.immediate,
    )
    private val source by lazy { GraderWebSource() }
    private val mutex = Mutex()
    @Volatile private var generation = 0L
    private var sessionSuppressed = false
    private var current = GraderState()
    private val mutableState = MutableLiveData(current)
    val state: LiveData<GraderState> = mutableState

    suspend fun reload(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val snapshot = if (sessionSuppressed) store.disconnect().also { sessionSuppressed = false } else store.load()
                publish(snapshot.toState(syncing = current.syncing))
                true
            } catch (_: GraderStorageException) {
                publish(current.copy(ready = true, error = GraderError.STORAGE))
                false
            }
        }
    }

    suspend fun connect(cookieHeader: String): Boolean = synchronize(cookieHeader)
    suspend fun refresh(): Boolean = synchronize(null)

    suspend fun setCompleted(id: String, completed: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val accountId = current.accountId ?: return@withLock false
            if (sessionSuppressed) return@withLock false
            try {
                val before = store.load()
                if (before.account?.id != accountId || before.assignments.none { it.id == id }) return@withLock false
                val snapshot = store.setCompleted(accountId, id, completed)
                publish(snapshot.toState(syncing = current.syncing).copy(needsLogin = current.needsLogin))
                true
            } catch (_: GraderStorageException) {
                publish(current.copy(error = GraderError.STORAGE))
                false
            }
        }
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            generation++
            sessionSuppressed = true
            // Hide the previous account even if durable removal fails; never report false success.
            publish(GraderState(ready = true, needsLogin = true))
            try {
                store.disconnect()
                sessionSuppressed = false
                true
            } catch (_: GraderStorageException) {
                publish(current.copy(error = GraderError.STORAGE))
                false
            }
        }
    }

    private data class Operation(val generation: Long, val cookie: String, val expectedAccountId: String?)
    private class Superseded : Exception()

    private suspend fun synchronize(candidateCookie: String?): Boolean = withContext(Dispatchers.IO) {
        var startedGeneration: Long? = null
        try {
            val operation = mutex.withLock {
                val token = ++generation
                startedGeneration = token
                try {
                    val cookie: String
                    val expectedAccountId: String?
                    if (candidateCookie != null) {
                        sessionSuppressed = true
                        publish(GraderState(ready = true, syncing = true))
                        store.disconnect()
                        sessionSuppressed = false
                        cookie = GraderSessionPolicy.sessionCookie(candidateCookie) ?: throw GraderAuthenticationException()
                        expectedAccountId = null
                    } else {
                        if (sessionSuppressed) throw GraderStorageException()
                        val snapshot = store.load()
                        cookie = snapshot.cookie ?: throw GraderAuthenticationException()
                        expectedAccountId = snapshot.account?.id ?: throw GraderAuthenticationException()
                        publish(snapshot.toState(syncing = true))
                    }
                    Operation(token, cookie, expectedAccountId)
                } catch (_: GraderStorageException) {
                    publish(current.copy(ready = true, syncing = false, error = GraderError.STORAGE))
                    null
                } catch (_: GraderAuthenticationException) {
                    publish(current.copy(ready = true, syncing = false, needsLogin = true, error = GraderError.AUTHENTICATION))
                    null
                }
            } ?: return@withContext false

            try {
                val context = coroutineContext
                val remote = source.fetch(operation.cookie, operation.expectedAccountId) {
                    context.ensureActive()
                    if (generation != operation.generation) throw Superseded()
                }
                context.ensureActive()
                mutex.withLock {
                    if (generation != operation.generation) return@withLock false
                    val snapshot = store.replace(
                        remote.account,
                        operation.cookie,
                        remote.assignments,
                        remote.submittedAssignmentIds,
                    )
                    publish(snapshot.toState())
                    true
                }
            } catch (_: Superseded) {
                false
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutex.withLock {
                    if (generation != operation.generation) return@withLock false
                    val error = when (failure) {
                        is GraderAuthenticationException -> GraderError.AUTHENTICATION
                        is GraderNetworkException -> GraderError.NETWORK
                        is GraderStorageException -> GraderError.STORAGE
                        is GraderInvalidResponseException -> GraderError.INVALID_RESPONSE
                        else -> GraderError.INVALID_RESPONSE
                    }
                    publish(current.copy(syncing = false, needsLogin = current.needsLogin || error == GraderError.AUTHENTICATION, error = error))
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation == startedGeneration) publish(current.copy(syncing = false))
                }
            }
            throw cancelled
        }
    }

    private fun GraderSnapshot.toState(syncing: Boolean = false): GraderState = GraderState(
        accountId = account?.id,
        accountName = account?.name,
        tasks = visibleTasks(System.currentTimeMillis()),
        ready = true,
        syncing = syncing,
        needsLogin = account == null && !syncing,
    )

    /** Returning callers can immediately inspect state.value; no asynchronous postValue race. */
    private suspend fun publish(next: GraderState) {
        current = next
        withContext(NonCancellable + publicationDispatcher) { mutableState.value = next }
    }
}
