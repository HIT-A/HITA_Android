package cn.limpu.hita.data.repository.geniusta

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.homework.ImportedTodo
import cn.limpu.hita.data.source.preference.GeniusTaPreferenceSource
import cn.limpu.hita.data.source.web.geniusta.GeniusTaAuthenticationException
import cn.limpu.hita.data.source.web.geniusta.GeniusTaConnection
import cn.limpu.hita.data.source.web.geniusta.GeniusTaInvalidResponseException
import cn.limpu.hita.data.source.web.geniusta.GeniusTaNetworkException
import cn.limpu.hita.data.source.web.geniusta.GeniusTaWebSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public enum class GeniusTaError { AUTHENTICATION, NETWORK, STORAGE, INVALID_RESPONSE }

public data class GeniusTaState(
    val accountId: String? = null,
    val tasks: List<ImportedTodo> = emptyList(),
    val ready: Boolean = false,
    val syncing: Boolean = false,
    val needsLogin: Boolean = false,
    val error: GeniusTaError? = null,
)

@Singleton
class GeniusTaRepository internal constructor(
    private val store: GeniusTaStore,
    private val source: GeniusTaWebSource,
    private val publicationDispatcher: CoroutineDispatcher,
) {
    @Inject constructor(application: Application) : this(
        GeniusTaPreferenceSource(application).let { GeniusTaStore(it::read, it::write) },
        GeniusTaWebSource(),
        Dispatchers.Main.immediate,
    )

    private val mutex = Mutex()
    /** Disk and publication are serialized; network reads never hold the mutex. */
    @Volatile private var generation = 0L
    private var pendingRemoval: Removal? = null
    private var current = GeniusTaState()
    private val mutableState = MutableLiveData(current)
    val state: LiveData<GeniusTaState> = mutableState

    suspend fun reload(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val snapshot = loadSnapshot()
                if (!current.syncing) publish(snapshot.toState())
                true
            } catch (_: GeniusTaStorageException) {
                publish(current.copy(ready = true, error = GeniusTaError.STORAGE))
                false
            }
        }
    }

    suspend fun connect(username: String, password: String): Boolean = synchronize(Credentials(username, password))

    suspend fun refresh(): Boolean = synchronize(null)

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            generation++
            pendingRemoval = Removal.DISCONNECT
            // Don't expose the preceding account if the encrypted commit fails.
            publish(GeniusTaState(ready = true, needsLogin = true))
            try {
                loadSnapshot()
                true
            } catch (_: GeniusTaStorageException) {
                publish(current.copy(error = GeniusTaError.STORAGE))
                false
            }
        }
    }

    suspend fun setCompleted(accountId: String, id: String, completed: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (current.accountId != accountId) return@withLock false
            try {
                loadSnapshot() // Retry any failed clearing before committing completion.
                val snapshot = store.setCompleted(accountId, id, completed) ?: return@withLock false
                publish(snapshot.toState(syncing = current.syncing, error = current.error))
                true
            } catch (_: GeniusTaStorageException) {
                publish(current.copy(error = GeniusTaError.STORAGE))
                false
            }
        }
    }

    private suspend fun synchronize(credentials: Credentials?): Boolean = withContext(Dispatchers.IO) {
        var token: Long? = null
        var verifiedAccountId: String? = null
        try {
            val existing = mutex.withLock {
                try {
                    if (credentials != null) {
                        token = ++generation
                        pendingRemoval = Removal.DISCONNECT
                        publish(GeniusTaState(ready = true, syncing = true))
                        loadSnapshot()
                        null
                    } else {
                        // A refresh without an established session cannot override an in-flight login.
                        if (current.syncing && current.accountId == null) return@withContext false
                        val snapshot = loadSnapshot()
                        val accountId = snapshot.accountId
                        val session = snapshot.session
                        if (accountId == null || session == null) {
                            publish(snapshot.toState(error = GeniusTaError.AUTHENTICATION))
                            return@withContext false
                        }
                        token = ++generation
                        publish(snapshot.toState(syncing = true))
                        GeniusTaConnection(accountId, session)
                    }
                } catch (_: GeniusTaStorageException) {
                    publish(current.copy(ready = true, syncing = false, needsLogin = current.needsLogin || credentials != null, error = GeniusTaError.STORAGE))
                    return@withContext false
                }
            }

            val context = coroutineContext
            val checkActive = {
                context.ensureActive()
                if (token != generation) throw Superseded()
            }
            checkActive()
            val connection = existing ?: requireNotNull(credentials).let { source.login(it.username, it.password) }
            checkActive()
            source.verify(connection)
            verifiedAccountId = connection.accountId
            val assignments = source.assignments(connection.session, checkActive)
            mutex.withLock {
                checkActive()
                publish(store.replace(connection, assignments).toState())
                true
            }
        } catch (_: Superseded) {
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = when (failure) {
                is GeniusTaAuthenticationException -> GeniusTaError.AUTHENTICATION
                is GeniusTaNetworkException -> GeniusTaError.NETWORK
                is GeniusTaStorageException -> GeniusTaError.STORAGE
                is GeniusTaInvalidResponseException -> GeniusTaError.INVALID_RESPONSE
                else -> throw failure
            }
            mutex.withLock {
                if (token != generation) return@withLock false
                publishFailure(error, connecting = credentials != null, verifiedAccountId = verifiedAccountId)
                false
            }
        } finally {
            // Cancellation can also occur while acquiring the lock in the failure handler.
            if (token != null && token == generation) withContext(NonCancellable) {
                mutex.withLock {
                    if (token == generation && current.syncing) {
                        publish(current.copy(syncing = false, needsLogin = current.needsLogin || current.accountId == null))
                    }
                }
            }
        }
    }

    private suspend fun publishFailure(error: GeniusTaError, connecting: Boolean, verifiedAccountId: String?) {
        try {
            val snapshot = when {
                connecting && verifiedAccountId != null -> store.activateCached(verifiedAccountId)
                !connecting && error == GeniusTaError.AUTHENTICATION -> {
                    pendingRemoval = Removal.EXPIRE
                    loadSnapshot()
                }
                else -> null
            }
            publish(snapshot?.toState(error = error) ?: current.copy(syncing = false, needsLogin = connecting || current.needsLogin, error = error))
        } catch (_: GeniusTaStorageException) {
            publish(current.copy(syncing = false, needsLogin = true, error = GeniusTaError.STORAGE))
        }
    }

    /** If durable clearing fails, don't reuse the old session in this process. */
    private fun loadSnapshot(): GeniusTaSnapshot {
        val snapshot = when (pendingRemoval) {
            Removal.DISCONNECT -> store.disconnect()
            Removal.EXPIRE -> store.expireSession()
            null -> return store.load()
        }
        pendingRemoval = null
        return snapshot
    }

    private fun GeniusTaSnapshot.toState(syncing: Boolean = false, error: GeniusTaError? = null): GeniusTaState = GeniusTaState(
        accountId = accountId,
        tasks = tasks,
        ready = true,
        syncing = syncing,
        needsLogin = session?.expired() != false,
        error = error,
    )

    private suspend fun publish(next: GeniusTaState) {
        current = next
        withContext(NonCancellable + publicationDispatcher) { mutableState.value = next }
    }

    private enum class Removal { DISCONNECT, EXPIRE }
    private class Credentials(val username: String, val password: String)
    private class Superseded : Exception()
}
