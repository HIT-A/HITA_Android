package cn.limpu.hita.ui.main.homework

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.repository.homework.HomeworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class HomeworkViewModel @Inject constructor(private val repository: HomeworkRepository) : ViewModel() {
    val state = repository.state
    private val mutableBusy = MutableLiveData(false)
    val busy: LiveData<Boolean> = mutableBusy
    private val mutableToday = MutableLiveData(LocalDate.now().toString())
    val today: LiveData<String> = mutableToday

    init { reload() }

    fun refreshToday() {
        val date = LocalDate.now().toString()
        if (mutableToday.value != date) mutableToday.value = date
    }

    fun reload() = update { repository.reload() }
    fun save(id: String?, title: String, date: String, onSaved: () -> Unit) = update(onSaved) {
        if (id == null) repository.add(title, date) else repository.edit(id, title, date)
    }
    fun complete(id: String, completed: Boolean, onCompleted: (() -> Unit)? = null) =
        update(onCompleted) { repository.setCompleted(id, completed) }
    fun delete(id: String, onDeleted: () -> Unit) = update(onDeleted) { repository.delete(id) }

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
