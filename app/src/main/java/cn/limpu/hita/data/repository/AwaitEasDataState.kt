package cn.limpu.hita.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.limpu.component.data.DataState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Temporary cancellable boundary for metadata/import APIs scheduled for their own migration. */
internal suspend fun <T> awaitEasDataState(
    afterObserve: () -> Unit = {},
    source: () -> LiveData<DataState<T>>,
): DataState<T> = withContext(Dispatchers.Main.immediate) {
    val live = source()
    var observer: Observer<DataState<T>>? = null
    try {
        suspendCancellableCoroutine { continuation ->
            val listener = Observer<DataState<T>> { state ->
                if (state.state != DataState.STATE.NOTHING && state.state != DataState.STATE.LOADING && continuation.isActive) {
                    continuation.resume(state)
                }
            }
            observer = listener
            live.observeForever(listener)
            if (continuation.isActive) afterObserve()
        }
    } finally {
        observer?.let(live::removeObserver)
    }
}
