package cn.limpu.hita.ui.eas

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.repository.EasQuerySession
import com.limpu.component.data.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A finite request belongs to this selected source and is cancelled when it is detached. */
internal class FiniteEasQueryLiveData<T>(
    private val scope: CoroutineScope,
    private val session: EasQuerySession,
    private val query: suspend () -> DataState<T>,
) : LiveData<DataState<T>>() {
    private var job: Job? = null
    private var complete = false
    private var generation = 0L

    override fun onActive() {
        super.onActive()
        if (complete || job?.isActive == true || !session.isCurrent()) return
        val request = ++generation
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                session.whileCurrent { query() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DataState(DataState.STATE.FETCH_FAILED, error.message)
            }
            if (isActive && request == generation && session.isCurrent()) {
                complete = true
                value = result
            }
        }.also { it.start() }
    }

    override fun onInactive() {
        generation++
        job?.cancel()
        job = null
        super.onInactive()
    }
}
