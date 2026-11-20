package cn.limpu.hita.data.repository.today

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** A failed local read must not be mistaken for a successfully read empty collection. */
sealed interface TodayReadState<out T> {
    data object Loading : TodayReadState<Nothing>
    data class Ready<T>(val value: T) : TodayReadState<T>
    data class Failed<T>(val retained: T?, val failure: Throwable) : TodayReadState<T>
}

/** Retention is local to a subscription; changing target/window never inherits old content. */
internal fun <T> Flow<T>.asTodayReadState(): Flow<TodayReadState<T>> = flow {
    var retained: T? = null
    emit(TodayReadState.Loading)
    emitAll(this@asTodayReadState.map<T, TodayReadState<T>> { value ->
        retained = value
        TodayReadState.Ready(value)
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(TodayReadState.Failed(retained, failure))
    })
}
