package cn.limpu.hita.ui.main.homework

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.homework.HomeworkRepository
import cn.limpu.hita.data.repository.geniusta.GeniusTaRepository
import cn.limpu.hita.data.repository.grader.GraderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class HomeworkViewModel @Inject constructor(
    private val repository: HomeworkRepository,
    private val graderRepository: GraderRepository,
    private val geniusTaRepository: GeniusTaRepository,
    easRepository: EASRepository,
) : ViewModel() {
    val state = repository.state
    private val mutableBusy = MutableLiveData(false)
    val busy: LiveData<Boolean> = mutableBusy
    private val mutableToday = MutableLiveData(LocalDate.now().toString())
    val today: LiveData<String> = mutableToday
    val graderState = graderRepository.state
    val geniusTaState = geniusTaRepository.state

    /** Grader 入口沿用深圳教务登录条件；GeniusTA 使用独立账号。 */
    val graderVisible: LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        value = easRepository.getEasToken().isGraderAvailable()
        addSource(easRepository.observeEasToken()) { value = it.isGraderAvailable() }
    }
    private val mutableNow = MutableLiveData(System.currentTimeMillis())
    val now: LiveData<Long> = mutableNow

    // 同一个可见期只加载一次，避免在入口隐藏时访问平台。
    private var loadedForVisiblePeriod = false
    private val graderReadConsumer = "homework:${System.identityHashCode(this)}"

    private val graderVisibilityObserver = Observer<Boolean> { visible ->
        graderRepository.readCoordinator.setVisible(graderReadConsumer, visible)
        if (!visible) {
            loadedForVisiblePeriod = false
        } else if (!loadedForVisiblePeriod) {
            loadedForVisiblePeriod = true
            viewModelScope.launch {
                if (graderRepository.readCoordinator.load(graderReadConsumer) &&
                    graderVisible.value == true && graderState.value?.accountId != null
                ) {
                    graderRepository.refresh()
                }
            }
        }
    }

    init {
        reload()
        graderVisible.observeForever(graderVisibilityObserver)
        viewModelScope.launch {
            if (geniusTaRepository.reload() && geniusTaState.value?.needsLogin == false) {
                geniusTaRepository.refresh()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        graderVisible.removeObserver(graderVisibilityObserver)
        graderRepository.readCoordinator.setVisible(graderReadConsumer, false)
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
    internal fun complete(key: TodoKey, completed: Boolean, onCompleted: (() -> Unit)? = null) {
        when (key) {
            is TodoKey.Personal -> update(onCompleted) { repository.setCompleted(key.id, completed) }
            is TodoKey.Imported -> viewModelScope.launch {
                val success = when (key.platform) {
                    TodoPlatform.GRADER -> graderRepository.setCompleted(key.accountId, key.id, completed)
                    TodoPlatform.GENIUSTA -> geniusTaRepository.setCompleted(key.accountId, key.id, completed)
                }
                if (success) onCompleted?.invoke()
            }
        }
    }
    fun delete(id: String, onDeleted: () -> Unit) = update(onDeleted) { repository.delete(id) }

    fun refreshGrader() {
        viewModelScope.launch { graderRepository.refresh() }
    }

    fun connectGeniusTa(username: String, password: String, onConnected: () -> Unit) {
        viewModelScope.launch {
            if (geniusTaRepository.connect(username, password)) onConnected()
        }
    }

    fun refreshGeniusTa() {
        viewModelScope.launch { geniusTaRepository.refresh() }
    }

    fun disconnectGeniusTa() {
        viewModelScope.launch { geniusTaRepository.disconnect() }
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

private fun EASToken?.isGraderAvailable(): Boolean =
    this?.let { it.isLogin() && it.campus == EASToken.Campus.SHENZHEN } ?: false
