package cn.limpu.hita.ui.eas.classroom

import android.os.Handler
import android.os.Looper
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.repository.EmptyClassroomQueryRequest
import com.limpu.component.data.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns collection and checks request ownership again when each queued result reaches Main. */
internal class EmptyClassroomQueryCoordinator(private val scope: CoroutineScope) {
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
        requestGeneration: Long,
        request: EmptyClassroomQueryRequest,
        deliver: (DataState<List<ClassroomItem>>) -> Unit,
    ) {
        if (!isCurrent(requestGeneration)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            request.states().collect { state ->
                ensureActive()
                // Cache and remote are distinct events. Do not coalesce them into postValue.
                main.post {
                    // A completed Flow may still have valid queued deliveries.
                    if (isCurrent(requestGeneration)) deliver(state)
                }
            }
        }
        currentJob = job
        job.start()
    }
}
