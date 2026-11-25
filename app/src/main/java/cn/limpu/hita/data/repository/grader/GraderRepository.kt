package cn.limpu.hita.data.repository.grader

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.grader.GraderSummaryItem
import cn.limpu.hita.data.model.homework.ImportedTodo
import cn.limpu.hita.data.source.preference.GraderPreferenceSource
import cn.limpu.hita.data.source.web.grader.GraderAssignmentDetail
import cn.limpu.hita.data.source.web.grader.GraderAuthenticationException
import cn.limpu.hita.data.source.web.grader.GraderInvalidResponseException
import cn.limpu.hita.data.source.web.grader.GraderFileReadException
import cn.limpu.hita.data.source.web.grader.GraderNetworkException
import cn.limpu.hita.data.source.web.grader.GraderRemoteSnapshot
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import cn.limpu.hita.data.source.web.grader.GraderSubmissionPolicy
import cn.limpu.hita.data.source.web.grader.GraderSubmitRemote
import cn.limpu.hita.data.source.web.grader.GraderUploadStorageException
import cn.limpu.hita.data.source.web.grader.GraderUploadFile
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

/** 提交作业的失败分类；界面按类给出不同提示。 */
enum class GraderSubmitError {
    AUTHENTICATION,
    NETWORK,
    INVALID_RESPONSE,
    SESSION_CHANGED,
    UNCONFIRMED,
    STORAGE,
    FILE_READ,

    /** 本地预检不通过（超出大小上限、扩展名不符或服务端已关闭提交）。 */
    REJECTED,

    /** 已有一次提交在进行中。 */
    BUSY,

    /** 校外无法直连作业存储：需要校园网或 aTrust 客户端。 */
    STORAGE_UNREACHABLE,
}

data class GraderState(
    val accountId: String? = null,
    val accountName: String? = null,
    val tasks: List<ImportedTodo> = emptyList(),
    val summaryItems: List<GraderSummaryItem> = emptyList(),
    val ready: Boolean = false,
    val syncing: Boolean = false,
    val needsLogin: Boolean = false,
    val error: GraderError? = null,
)

@Singleton
class GraderRepository internal constructor(
    private val store: GraderStore,
    private val publicationDispatcher: CoroutineDispatcher,
    private val submitRemote: GraderSubmitRemote = GraderWebSource(),
    private val fetchRemote: (GraderSessionPolicy.Entry, String, String?, () -> Unit) -> GraderRemoteSnapshot,
) {
    @Inject constructor(application: Application) : this(
        store = GraderPreferenceSource(application).let { GraderStore(it::read, it::write) },
        publicationDispatcher = Dispatchers.Main.immediate,
        fetchRemote = kotlin.run {
            val source by lazy { GraderWebSource() }
            return@run { entry, cookie, expectedAccountId, checkActive ->
                source.fetch(entry, cookie, expectedAccountId, checkActive)
            }
        },
    )

    private val mutex = Mutex()

    /** 提交是长耗时操作，单独加锁避免并发提交；不复用状态锁，否则会卡住刷新与登录。 */
    private val submitMutex = Mutex()

    /** Only status codes and exception types; never raw responses or signed URLs. */
    @Volatile
    var lastSubmitDiagnostic: String? = null
        private set
    @Volatile private var generation = 0L
    @Volatile private var sessionRevision = 0L
    val readCoordinator = GraderReadCoordinator(::reload) { generation }
    private var pendingChange: SessionChange? = null
    private var current = GraderState()
    private val mutableState = MutableLiveData(current)
    val state: LiveData<GraderState> = mutableState

    suspend fun reload(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val snapshot = loadSnapshot()
                if (!current.syncing) publish(snapshot.toState())
                true
            } catch (_: GraderStorageException) {
                publish(current.copy(ready = true, error = GraderError.STORAGE))
                false
            }
        }
    }

    internal suspend fun connect(
        cookieHeader: String,
        expectedAccountId: String? = null,
        entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT,
    ): Boolean = synchronize(cookieHeader, expectedAccountId, entry)

    suspend fun refresh(): Boolean = synchronize(null, null, null)

    /** Load details only for the account that opened the submission page. */
    internal suspend fun submissionDetail(accountId: String, assignmentId: String): GraderAssignmentDetail? =
        withContext(Dispatchers.IO) {
            val session = try {
                mutex.withLock { submissionSession(accountId) }
            } catch (_: GraderStorageException) {
                return@withContext null
            } ?: return@withContext null
            val assignment = session.snapshot.assignments.firstOrNull { it.id == assignmentId && it.published }
                ?: return@withContext null
            val detail = try {
                submitRemote.assignmentDetail(session.snapshot.entry, session.cookie, assignmentId, assignment.title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                GraderAssignmentDetail(
                    assignment.id, assignment.title, assignment.description, assignment.maxFileSize,
                    assignment.allowedExtensions, assignment.submitAllowed,
                )
            }
            detail.takeIf { it.id == assignmentId && session.revision == sessionRevision }
        }

    /**
     * 提交作业：申请上传目标 → 上传文件 → 收尾 → 建提交记录。
     *
     * 返回 null 表示成功；成功后把该作业立即记入本地提交集合，界面无需等下一次完整刷新。
     * 本地预检（大小 / 扩展名 / 是否允许提交）在这里复验一次，避免绕过界面直接调用。
     */
    internal suspend fun submit(
        accountId: String,
        assignmentId: String,
        file: GraderUploadFile,
        onProgress: (uploaded: Long, total: Long) -> Unit = { _, _ -> },
    ): GraderSubmitError? = withContext(Dispatchers.IO) {
        if (!submitMutex.tryLock()) return@withContext GraderSubmitError.BUSY
        try {
            lastSubmitDiagnostic = null
            val session = try {
                mutex.withLock { submissionSession(accountId) }
            } catch (_: GraderStorageException) {
                return@withContext GraderSubmitError.STORAGE
            } ?: return@withContext GraderSubmitError.SESSION_CHANGED
            val assignment = session.snapshot.assignments.firstOrNull { it.id == assignmentId && it.published }
                ?: return@withContext GraderSubmitError.INVALID_RESPONSE
            val entry = session.snapshot.entry
            val cookie = session.cookie
            val context = coroutineContext
            val checkActive: () -> Unit = {
                context.ensureActive()
                if (sessionRevision != session.revision) throw Superseded()
            }
            var creatingRecord = false
            try {
                checkActive()
                val detail = submitRemote.assignmentDetail(entry, cookie, assignmentId, assignment.title)
                checkActive()
                if (detail.id != assignmentId) return@withContext GraderSubmitError.INVALID_RESPONSE
                if (GraderSubmissionPolicy.violation(detail, file.name, file.sizeBytes) != null) {
                    return@withContext GraderSubmitError.REJECTED
                }
                val target = submitRemote.requestUpload(entry, cookie, file.name, assignmentId)
                checkActive()
                submitRemote.upload(entry, cookie, target, file, checkActive, { uploaded ->
                    onProgress(uploaded, file.sizeBytes)
                })
                checkActive()
                submitRemote.finishUpload(entry, cookie, target.ident)
                checkActive()
                creatingRecord = true
                submitRemote.createSubmission(entry, cookie, assignmentId, target.ident)
                // A confirmed external write must not become "retry" if the screen went away
                // or committing the local cache fails.
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (sessionRevision == session.revision) {
                            try {
                                store.markSubmitted(accountId, assignmentId)?.let { updated ->
                                    // A refresh may have read the server before its new record existed.
                                    if (current.syncing) generation++
                                    publish(updated.toState())
                                }
                            } catch (_: GraderStorageException) {
                                publish(current.copy(error = GraderError.STORAGE))
                            }
                        }
                    }
                }
                null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is Superseded) {
                    GraderSubmitError.SESSION_CHANGED
                } else if (failure is GraderAuthenticationException) {
                    mutex.withLock {
                        if (sessionRevision == session.revision) {
                            publish(current.copy(needsLogin = true, error = GraderError.AUTHENTICATION))
                        }
                    }
                    GraderSubmitError.AUTHENTICATION
                } else if (failure is GraderFileReadException) {
                    GraderSubmitError.FILE_READ
                } else if (failure is GraderUploadStorageException) {
                    GraderSubmitError.STORAGE_UNREACHABLE
                } else if (failure is GraderNetworkException) {
                    lastSubmitDiagnostic = "HTTP ${failure.code}"
                    if (creatingRecord && (failure.code == 0 || failure.code == 408 || failure.code >= 500)) {
                        GraderSubmitError.UNCONFIRMED
                    } else GraderSubmitError.NETWORK
                } else {
                    lastSubmitDiagnostic = failure.javaClass.simpleName
                    if (creatingRecord) GraderSubmitError.UNCONFIRMED else GraderSubmitError.INVALID_RESPONSE
                }
            }
        } finally {
            submitMutex.unlock()
        }
    }

    suspend fun setCompleted(accountId: String, id: String, completed: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (pendingChange != null || current.accountId != accountId) return@withLock false
            try {
                val snapshot = store.setCompleted(accountId, id, completed) ?: return@withLock false
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
            sessionRevision++
            pendingChange = SessionChange.DISCONNECT
            var error: GraderError? = null
            try {
                loadSnapshot()
                true
            } catch (_: GraderStorageException) {
                error = GraderError.STORAGE
                false
            } finally {
                // Even a failed commit hides the account until the durable change can be retried.
                publish(GraderState(ready = true, needsLogin = true, error = error))
            }
        }
    }

    private fun loadSnapshot(): GraderSnapshot {
        val snapshot = when (pendingChange) {
            SessionChange.DISCONNECT -> store.disconnect()
            SessionChange.REPLACE -> store.clearForReplacement()
            null -> return store.load()
        }
        pendingChange = null
        return snapshot
    }

    private data class SubmissionSession(val snapshot: GraderSnapshot, val cookie: String, val revision: Long)

    /** Called while holding [mutex], just like reads and replacement of the active session. */
    private fun submissionSession(accountId: String): SubmissionSession? {
        val snapshot = loadSnapshot()
        if (snapshot.activeAccount?.id != accountId || current.needsLogin) return null
        return SubmissionSession(snapshot, snapshot.cookie ?: return null, sessionRevision)
    }

    private enum class SessionChange { DISCONNECT, REPLACE }

    private data class Operation(
        val generation: Long,
        val entry: GraderSessionPolicy.Entry,
        val cookie: String,
        val expectedAccountId: String?,
    )
    private class Superseded : Exception()

    private suspend fun synchronize(
        candidateCookie: String?,
        candidateAccountId: String?,
        candidateEntry: GraderSessionPolicy.Entry?,
    ): Boolean = withContext(Dispatchers.IO) {
        var startedGeneration: Long? = null
        try {
            val operation = mutex.withLock {
                // A refresh cannot supersede an explicit login while no account is shown.
                if (candidateCookie == null && current.syncing && current.accountId == null) return@withContext false
                val token = ++generation
                startedGeneration = token
                try {
                    val entry: GraderSessionPolicy.Entry
                    val cookie: String
                    val expectedAccountId: String?
                    if (candidateCookie != null) {
                        sessionRevision++
                        publish(GraderState(ready = true, syncing = true))
                        coroutineContext.ensureActive()
                        // 新入口可能与旧会话不同，旧 cookie 不能跨入口复用。
                        val candidate = candidateEntry ?: GraderSessionPolicy.Entry.DIRECT
                        // Retry a failed user disconnect before the replacement can retire any active session.
                        if (pendingChange == SessionChange.DISCONNECT) loadSnapshot()
                        pendingChange = SessionChange.REPLACE
                        loadSnapshot()
                        cookie = GraderSessionPolicy.cookieHeader(candidate, candidateCookie)
                            ?: throw GraderAuthenticationException()
                        entry = candidate
                        expectedAccountId = candidateAccountId
                    } else {
                        val snapshot = loadSnapshot()
                        if (snapshot.disconnected) {
                            publish(snapshot.toState())
                            return@withContext false
                        }
                        entry = snapshot.entry
                        cookie = snapshot.cookie ?: throw GraderAuthenticationException()
                        expectedAccountId = snapshot.activeAccount?.id ?: throw GraderAuthenticationException()
                        publish(snapshot.toState(syncing = true))
                    }
                    Operation(token, entry, cookie, expectedAccountId)
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
                val remote = fetchRemote(operation.entry, operation.cookie, operation.expectedAccountId) {
                    context.ensureActive()
                    if (generation != operation.generation) throw Superseded()
                }
                context.ensureActive()
                mutex.withLock {
                    if (generation != operation.generation) return@withLock false
                    context.ensureActive()
                    val snapshot = store.replace(
                        remote.account,
                        operation.cookie,
                        remote.assignments,
                        remote.submittedAssignmentIds,
                        operation.entry,
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
                    if (generation == startedGeneration) {
                        publish(current.copy(syncing = false, needsLogin = current.needsLogin || current.accountId == null))
                    }
                }
            }
            throw cancelled
        }
    }

    private fun GraderSnapshot.toState(syncing: Boolean = false): GraderState = GraderState(
        accountId = activeAccount?.id,
        accountName = activeAccount?.name,
        tasks = todoTasks(),
        summaryItems = summaryItems(),
        ready = true,
        syncing = syncing,
        needsLogin = activeAccount == null && !syncing,
    )

    /** Returning callers can immediately inspect state.value; no asynchronous postValue race. */
    private suspend fun publish(next: GraderState) {
        current = next
        withContext(NonCancellable + publicationDispatcher) { mutableState.value = next }
    }
}
