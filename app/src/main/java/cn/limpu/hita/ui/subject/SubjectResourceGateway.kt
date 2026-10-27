package cn.limpu.hita.ui.subject

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.AgentToolException
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.agent.subject.SubjectReadmeAgentFactory
import cn.limpu.hita.agent.subject.SubjectReadmeAgentInput
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.resource.CourseReadmeData
import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.repository.ExternalResourceRepository
import cn.limpu.hita.data.repository.HoaRepository
import cn.limpu.hita.utils.CourseResourceLinker
import com.limpu.component.data.DataState
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Captured before any asynchronous work; campus is the academic campus, even when HOA uses SZ. */
data class SubjectResourceKey(
    val subjectId: String,
    val code: String?,
    val name: String,
    val campus: EASToken.Campus,
    val hoaCampus: String,
)

fun interface SubjectRequest {
    fun cancel()
}

interface SubjectCandidateGateway {
    fun agent(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit): SubjectRequest
    fun direct(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit): SubjectRequest
}

interface SubjectResourceGateway : SubjectCandidateGateway {
    fun readme(repo: String, campus: String, result: (Result<CourseReadmeData>) -> Unit): SubjectRequest
    fun search(query: String, result: (Result<List<ExternalCourseItem>>) -> Unit): SubjectRequest
    fun directory(path: String, source: ResourceSource, result: (Result<List<ExternalResourceEntry>>) -> Unit): SubjectRequest
}

/** Legacy bridge owns no Activity. Cancel detaches observation and suppresses queued delivery.
 * Already running legacy candidate HTTP calls may still finish after cancellation.
 */
class AndroidSubjectResourceGateway @Inject constructor(
    private val hoa: HoaRepository,
    private val external: ExternalResourceRepository,
) : SubjectResourceGateway {
    private val main = Handler(Looper.getMainLooper())

    override fun agent(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit): SubjectRequest {
        val owner = RequestOwner()
        val session = SubjectReadmeAgentFactory.createProvider().createSession()
        val delivery = MainDelivery(main, result) {
            session.dispose()
            owner.close()
        }
        session.run(
            SubjectReadmeAgentInput(owner, key.subjectId, key.code, key.name, key.hoaCampus),
            onTrace = {},
            onResult = { response ->
                delivery.finish(when (response) {
                    is AgentToolResult.Success -> Result.success(response.value.candidates)
                    is AgentToolResult.Failure -> Result.failure(AgentToolException(response))
                })
            },
        )
        return delivery
    }

    override fun direct(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit): SubjectRequest {
        val owner = RequestOwner()
        var request = AgentCancellation.NONE
        val delivery = MainDelivery(main, result) { request.cancel(); owner.close() }
        request = CourseResourceLinker.resolveCandidates(owner, key.code, key.name, key.hoaCampus) {
            delivery.finish(Result.success(it))
        }
        return delivery
    }

    override fun readme(repo: String, campus: String, result: (Result<CourseReadmeData>) -> Unit): SubjectRequest =
        observeSubjectResource(hoa.getCourseReadme(repo, campus), result)

    override fun search(query: String, result: (Result<List<ExternalCourseItem>>) -> Unit): SubjectRequest =
        observeSubjectResource(external.searchCourses(query), result)

    override fun directory(path: String, source: ResourceSource, result: (Result<List<ExternalResourceEntry>>) -> Unit): SubjectRequest =
        observeSubjectResource(external.listDirectory(path, source), result)

    private class RequestOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        fun close() { lifecycle.currentState = Lifecycle.State.DESTROYED }
    }

    private class MainDelivery<T>(
        private val main: Handler,
        private var callback: ((Result<T>) -> Unit)?,
        private val cleanup: () -> Unit,
    ) : SubjectRequest {
        private val active = AtomicBoolean(true)
        fun finish(result: Result<T>) {
            main.post {
                if (active.compareAndSet(true, false)) {
                    val deliver = callback
                    callback = null
                    cleanup()
                    deliver?.invoke(result)
                }
            }
        }
        override fun cancel() {
            if (active.compareAndSet(true, false)) {
                callback = null
                cleanup()
            }
        }
    }
}

/** Observe on the main thread, remove the source before delivering even synchronous cached values. */
internal fun <T> observeSubjectResource(live: LiveData<DataState<T>>, callback: (Result<T>) -> Unit): SubjectRequest {
    var active = true
    lateinit var observer: Observer<DataState<T>>
    observer = Observer { state ->
        if (active && state.state != DataState.STATE.NOTHING && state.state != DataState.STATE.LOADING) {
            active = false
            live.removeObserver(observer)
            val value = state.data
            callback(if (state.state == DataState.STATE.SUCCESS && value != null) Result.success(value)
                else Result.failure(IllegalStateException(state.message.orEmpty())))
        }
    }
    live.observeForever(observer)
    return SubjectRequest {
        active = false
        live.removeObserver(observer)
    }
}
