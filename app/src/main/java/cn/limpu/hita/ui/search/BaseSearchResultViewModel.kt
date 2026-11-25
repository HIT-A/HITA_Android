package cn.limpu.hita.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import com.limpu.component.data.DataState

abstract class BaseSearchResultViewModel<T> : ViewModel() {


    private val searchTriggerLiveData: MutableLiveData<SearchTrigger> = MutableLiveData()
    val searchResultLiveData: LiveData<DataState<List<T>>> = searchTriggerLiveData.switchMap {
        // Never leave the old result rows actionable while a different query is in flight.
        MediatorLiveData<DataState<List<T>>>(DataState(DataState.STATE.LOADING)).apply {
            addSource(doSearch(it)) { result -> value = result }
        }
    }


    protected abstract fun doSearch(trigger: SearchTrigger): LiveData<DataState<List<T>>>

    fun startSearch(text: String, pageSize: Int, pageNum: Int, append: Boolean) {
        searchTriggerLiveData.value = SearchTrigger.getActioning(text, pageSize, pageNum, append)
    }

    fun changeSearchText(text: String): Boolean {
        val old = searchTriggerLiveData.value
        if (text != old?.text && text.isNotBlank()) {
            searchTriggerLiveData.value =
                SearchTrigger.getActioning(text, old?.pageSize?:0, 0, false)
            return true
        }
        return false

    }

    internal fun retrySearch() {
        val current = searchTriggerLiveData.value ?: return
        searchTriggerLiveData.value = current
    }
}