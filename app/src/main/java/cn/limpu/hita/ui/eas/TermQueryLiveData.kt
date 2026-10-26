package cn.limpu.hita.ui.eas

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EasQuerySession
import cn.limpu.hita.data.repository.TermQueryRequest
import com.limpu.component.data.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** VM-owned finite queries; inactivity defers refresh without cancelling work already started. */
internal class TermQueryLiveData(
    private val scope: CoroutineScope,
    sessions: StateFlow<EasQueryScope>,
    private val createRequest: () -> TermQueryRequest?,
    initiallyRequested: Boolean = false,
    private val transform: (EasQuerySession, DataState<List<TermItem>>) -> DataState<List<TermItem>> = { _, state -> state },
    private val onSessionChanged: () -> Unit = {},
    private val onResult: (EasQuerySession, DataState<List<TermItem>>) -> Unit = { _, _ -> },
) : LiveData<DataState<List<TermItem>>>() {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var requested = initiallyRequested
    private var pending = initiallyRequested
    private var queryJob: Job? = null
    private var currentScope = sessions.value

    init {
        // A token can change between the snapshot and subscription while a VM is being built.
        // Queue the first callback until all of the owning VM's fields have been initialized.
        scope.launch(Dispatchers.Main) {
            sessions.collect { next ->
                if (next != currentScope) {
                    currentScope = next
                    invalidate()
                    onSessionChanged()
                    value = DataState(DataState.STATE.NOTHING)
                    pending = requested
                    startIfActive()
                }
            }
        }
    }

    fun refresh() {
        requested = true
        pending = true
        invalidate()
        startIfActive()
    }

    private fun invalidate() {
        generation++
        queryJob?.cancel()
        queryJob = null
    }

    override fun onActive() {
        super.onActive()
        startIfActive()
    }

    private fun startIfActive() {
        if (!pending || !hasActiveObservers() || !scope.isActive) return
        pending = false
        val request = createRequest() ?: return
        val ownedGeneration = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            request.states().collect { state ->
                // Deliver every cache/remote event separately; completed jobs still own queued events.
                val deliver = Runnable {
                    if (scope.isActive && generation == ownedGeneration && request.session.isCurrent()) {
                        val visible = transform(request.session, state)
                        onResult(request.session, visible)
                        if (scope.isActive && generation == ownedGeneration && request.session.isCurrent()) {
                            value = visible
                        }
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else main.post(deliver)
            }
        }
        queryJob = job
        job.start()
    }
}

internal fun DataState<List<TermItem>>.mapTerms(transform: (List<TermItem>) -> List<TermItem>): DataState<List<TermItem>> =
    DataState<List<TermItem>>(state, message).also { copy ->
        copy.data = data?.let(transform)
        copy.fromCache = fromCache
        copy.listAction = listAction
        copy.stateRetried = stateRetried
    }

internal fun reconcileSelectedTerm(terms: List<TermItem>, selected: TermItem?): TermItem? =
    terms.firstOrNull { it.yearCode == selected?.yearCode && it.termCode == selected?.termCode }
        ?: terms.firstOrNull { it.isCurrent } ?: terms.firstOrNull()
