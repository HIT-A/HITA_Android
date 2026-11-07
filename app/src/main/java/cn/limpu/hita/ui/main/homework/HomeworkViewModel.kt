package cn.limpu.hita.ui.main.homework

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.repository.homework.HomeworkRepository
import cn.limpu.hita.data.repository.grader.GraderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class HomeworkViewModel @Inject constructor(
    private val repository: HomeworkRepository,
    private val graderRepository: GraderRepository,
) : ViewModel() {
    val state = repository.state
    private val mutableBusy = MutableLiveData(false)
    val busy: LiveData<Boolean> = mutableBusy
    private val mutableToday = MutableLiveData(LocalDate.now().toString())
    val today: LiveData<String> = mutableToday
    val graderState = graderRepository.state
    private val mutableNow = MutableLiveData(System.currentTimeMillis())
    val now: LiveData<Long> = mutableNow

    init {
        reload()
        viewModelScope.launch {
            if (graderRepository.reload() && graderState.value?.accountId != null) {
                graderRepository.refresh()
            }
        }
    }

    fun refreshToday() {
        mutableNow.value = System.currentTimeMillis()
        val date = LocalDate.now().toString()
        if (mutableToday.value != date) mutableToday.value = date
    }

    fun reload() = update { repository.reload() }
    fun save(id: String?, title: String, date: String, time: String = "23:59", onSaved: () -> Unit) = update(onSaved) {
        if (id == null) repository.add(title, date, time) else repository.edit(id, title, date, time)
    }
    fun complete(id: String, completed: Boolean, onCompleted: (() -> Unit)? = null) =
        update(onCompleted) { repository.setCompleted(id, completed) }
    fun delete(id: String, onDeleted: () -> Unit) = update(onDeleted) { repository.delete(id) }

    fun refreshGrader() {
        viewModelScope.launch { graderRepository.refresh() }
    }

    fun completeGrader(id: String, completed: Boolean) {
        viewModelScope.launch { graderRepository.setCompleted(id, completed) }
    }

    fun disconnectGrader(onDisconnected: () -> Unit) {
        viewModelScope.launch {
            if (graderRepository.disconnect()) onDisconnected()
        }
    }

    private fun update(onSuccess: (() -> Unit)? = null, operation: suspend () -> Boolean) {
        if (mutableBusy.value == true) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                if (operation()) onSuccess?.invoke()
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
