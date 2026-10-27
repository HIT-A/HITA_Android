package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Compatibility callers retain their eager query; the complete request has one owned coroutine tree. */
internal fun <T> legacyTimetableQuery(
    initialState: DataState.STATE? = null,
    query: suspend () -> DataState<T>,
): LiveData<DataState<T>> {
    val result = if (initialState == null) MutableLiveData<DataState<T>>()
        else MutableLiveData<DataState<T>>(DataState(initialState))
    Thread {
        val state = try { runBlocking { query() } }
        catch (cancelled: CancellationException) { DataState<T>(DataState.STATE.FETCH_FAILED, cancelled.message) }
        result.postValue(state)
    }.start()
    return result
}

internal fun TermItem.timetableQueryCopy() = TermItem(yearCode, yearName, termCode, termName).also {
    it.id = id; it.name = name; it.isCurrent = isCurrent
}
