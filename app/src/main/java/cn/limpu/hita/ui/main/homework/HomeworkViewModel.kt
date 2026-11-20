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
import cn.limpu.hita.data.repository.geniusta.GeniusTaError
import cn.limpu.hita.data.repository.geniusta.GeniusTaRepository
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.source.preference.TodoLoginCredential
import cn.limpu.hita.data.source.preference.TodoLoginProvider
import cn.limpu.hita.data.source.preference.TodoPlatformCredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel
class HomeworkViewModel @Inject constructor(
    private val repository: HomeworkRepository,
    private val graderRepository: GraderRepository,
    private val geniusTaRepository: GeniusTaRepository,
    private val credentialStore: TodoPlatformCredentialStore,
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
    private var geniusSync: Job? = null

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
        geniusSync = viewModelScope.launch {
            if (geniusTaRepository.reload()) restoreGeniusTa()
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

    internal suspend fun savedGeniusTaLogin(): TodoLoginCredential? {
        val saved = credentialStore.read(TodoLoginProvider.GENIUSTA) ?: return null
        val currentAccount = geniusTaState.value?.accountId
        return saved.takeIf { currentAccount == null || currentAccount == it.accountId }
    }

    internal suspend fun forgetGeniusTaLogin(): Boolean =
        credentialStore.forget(TodoLoginProvider.GENIUSTA)

    internal suspend fun connectGeniusTa(
        username: String,
        password: String,
        rememberDevice: Boolean,
        expectedAccountId: String?,
    ): TodoLoginResult {
        geniusSync?.cancel()
        if (!geniusTaRepository.connect(username, password, expectedAccountId)) return TodoLoginResult.Failed
        val accountId = geniusTaState.value?.accountId ?: return TodoLoginResult.Failed
        if (!rememberDevice) return TodoLoginResult.Connected
        val saved = credentialStore.save(TodoLoginProvider.GENIUSTA, TodoLoginCredential(username, password, accountId))
        return if (saved) TodoLoginResult.Connected else TodoLoginResult.ConnectedNotSaved
    }

    fun refreshGeniusTa() {
        if (geniusSync?.isActive == true) return
        geniusSync = viewModelScope.launch { restoreGeniusTa() }
    }

    private suspend fun restoreGeniusTa() {
        // Explicit logout has no active account, even if the user's encrypted login is retained.
        val accountId = geniusTaState.value?.accountId ?: return
        if (geniusTaState.value?.needsLogin != true && geniusTaRepository.refresh()) return
        val state = geniusTaState.value ?: return
        if (state.accountId != accountId || !state.needsLogin ||
            (state.error != null && state.error != GeniusTaError.AUTHENTICATION)
        ) return
        val saved = credentialStore.read(TodoLoginProvider.GENIUSTA)?.takeIf { it.accountId == accountId } ?: return
        geniusTaRepository.reconnect(saved.username, saved.password, accountId)
    }

    fun disconnectGeniusTa() {
        geniusSync?.cancel()
        viewModelScope.launch { geniusTaRepository.disconnect() }
    }

    fun disconnectGrader() {
        viewModelScope.launch { graderRepository.disconnect() }
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

internal enum class TodoLoginResult { Failed, Connected, ConnectedNotSaved }

private fun EASToken?.isGraderAvailable(): Boolean =
    this?.let { it.isLogin() && it.campus == EASToken.Campus.SHENZHEN } ?: false
