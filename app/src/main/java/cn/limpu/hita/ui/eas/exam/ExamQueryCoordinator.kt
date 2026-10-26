package cn.limpu.hita.ui.eas.exam

import android.os.Handler
import android.os.Looper
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.repository.ExamQueryRequest
import com.limpu.component.data.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Eager page requests; each main-thread delivery belongs to exactly one accepted refresh. */
internal class ExamQueryCoordinator(private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var currentJob: Job? = null

    fun cancel(): Long {
        generation++
        currentJob?.cancel()
        currentJob = null
        return generation
    }

    fun isCurrent(requestGeneration: Long): Boolean =
        scope.isActive && requestGeneration == generation

    fun start(
        request: ExamQueryRequest,
        deliver: (DataState<List<ExamItem>>) -> Unit,
    ) {
        cancel()
        if (!scope.isActive) return
        val requestGeneration = generation
        var pendingState: DataState<List<ExamItem>>? = null
        var deliveryPosted = false
        fun post(state: DataState<List<ExamItem>>) {
            pendingState = state
            if (deliveryPosted) return
            deliveryPosted = true
            main.post {
                deliveryPosted = false
                val latest = pendingState
                pendingState = null
                if (latest != null && isCurrent(requestGeneration)) {
                    deliver(latest)
                }
            }
        }
        request.initialState?.let(::post)
        // Install ownership before immediate query completion can reach a callback.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val state = request.execute()
            ensureActive()
            post(state)
        }
        currentJob = job
        job.start()
    }
}
