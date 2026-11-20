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
    /** A known current (possibly expired) account; a deliberate local disconnect hides it. */
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
    private var pendingChange: SessionChange? = null
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

    suspend fun connect(username: String, password: String, expectedAccountId: String? = null): Boolean =
        synchronize(Credentials(username, password, expectedAccountId))

    /** Background recovery may only replace the same expired connection, never wake a disconnected one. */
    suspend fun reconnect(username: String, password: String, expectedAccountId: String): Boolean =
        synchronize(Credentials(username, password, expectedAccountId), recovering = true)

    suspend fun refresh(): Boolean = synchronize(null)

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            generation++
            pendingChange = SessionChange.DISCONNECT
            var error: GeniusTaError? = null
            try {
                loadSnapshot()
                true
            } catch (_: GeniusTaStorageException) {
                error = GeniusTaError.STORAGE
                false
            } finally {
                // Even a failed commit hides the account until the durable change can be retried.
                publish(GeniusTaState(ready = true, needsLogin = true, error = error))
            }
        }
    }

    suspend fun setCompleted(accountId: String, id: String, completed: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (current.accountId != accountId) return@withLock false
            try {
                loadSnapshot() // Retry any pending session change before committing completion.
                val snapshot = store.setCompleted(accountId, id, completed) ?: return@withLock false
                publish(snapshot.toState(syncing = current.syncing, error = current.error))
                true
            } catch (_: GeniusTaStorageException) {
                publish(current.copy(error = GeniusTaError.STORAGE))
                false
            }
        }
    }

    private suspend fun synchronize(credentials: Credentials?, recovering: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val expectedAccountId = credentials?.expectedAccountId
        var token: Long? = null
        var verifiedAccountId: String? = null
        try {
            val existing = mutex.withLock {
                try {
                    if (recovering) {
                        if (current.syncing) return@withContext false
                        val snapshot = loadSnapshot()
                        if (snapshot.activeAccountId != requireNotNull(expectedAccountId) ||
                            snapshot.session?.expired() == false
                        ) return@withContext false
                        token = ++generation
                        publish(snapshot.toState(syncing = true))
                        null
                    } else if (credentials != null) {
                        token = ++generation
                        publish(GeniusTaState(ready = true, syncing = true))
                        coroutineContext.ensureActive()
                        // Retry a failed user disconnect before the replacement can retire any active session.
                        if (pendingChange == SessionChange.DISCONNECT) loadSnapshot()
                        pendingChange = SessionChange.REPLACE
                        loadSnapshot()
                        null
                    } else {
                        // A refresh cannot supersede explicit login or background recovery.
                        if (current.syncing && (current.accountId == null || current.needsLogin)) return@withContext false
                        val snapshot = loadSnapshot()
                        if (snapshot.disconnected) {
                            publish(snapshot.toState())
                            return@withContext false
                        }
                        val accountId = snapshot.activeAccountId
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
            checkActive()
            if (expectedAccountId != null && expectedAccountId != connection.accountId) {
                throw GeniusTaAuthenticationException()
            }
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
                publishFailure(error, connecting = credentials != null, recovering = recovering, verifiedAccountId = verifiedAccountId)
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

    private suspend fun publishFailure(
        error: GeniusTaError,
        connecting: Boolean,
        recovering: Boolean,
        verifiedAccountId: String?,
    ) {
        try {
            val snapshot = when {
                connecting && !recovering && verifiedAccountId != null -> store.activateCached(verifiedAccountId)
                !connecting && error == GeniusTaError.AUTHENTICATION -> {
                    pendingChange = SessionChange.EXPIRE
                    loadSnapshot()
                }
                else -> null
            }
            publish(snapshot?.toState(error = error) ?: current.copy(syncing = false, needsLogin = connecting || current.needsLogin, error = error))
        } catch (_: GeniusTaStorageException) {
            publish(current.copy(syncing = false, needsLogin = true, error = GeniusTaError.STORAGE))
        }
    }

    /** If durable storage fails, retry the change instead of reusing the old session in this process. */
    private fun loadSnapshot(): GeniusTaSnapshot {
        val snapshot = when (pendingChange) {
            SessionChange.DISCONNECT -> store.disconnect()
            SessionChange.REPLACE -> store.clearForReplacement()
            SessionChange.EXPIRE -> store.expireSession()
            null -> return store.load()
        }
        pendingChange = null
        return snapshot
    }

    private fun GeniusTaSnapshot.toState(syncing: Boolean = false, error: GeniusTaError? = null): GeniusTaState = GeniusTaState(
        accountId = activeAccountId,
        tasks = tasks,
        ready = true,
        syncing = syncing,
        needsLogin = disconnected || session?.expired() != false,
        error = error,
    )

    private suspend fun publish(next: GeniusTaState) {
        current = next
        withContext(NonCancellable + publicationDispatcher) { mutableState.value = next }
    }

    private enum class SessionChange { DISCONNECT, REPLACE, EXPIRE }
    private class Credentials(val username: String, val password: String, val expectedAccountId: String?)
    private class Superseded : Exception()
}
