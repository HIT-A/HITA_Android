package cn.limpu.hita.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.limpu.component.data.DataState
import com.limpu.component.data.Trigger
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.UpdateRepository
import cn.limpu.hita.utils.LiveDataUtils
import com.limpu.hitauser.data.model.CheckUpdateResult
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.hitauser.data.repository.ManagerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/** 挂起等待 LiveData 的第一个非空值（用于 LiveData → Flow 迁移桥接）。 */
private suspend fun <T> LiveData<T>.awaitValue(): T = suspendCancellableCoroutine { cont ->
    val observerRef = arrayOfNulls<Observer<T>>(1)
    val observer = Observer<T> { value ->
        if (value != null) {
            observerRef[0]?.let { removeObserver(it) }
            cont.resume(value)
        }
    }
    observerRef[0] = observer
    observeForever(observer)
    cont.invokeOnCancellation { observerRef[0]?.let { removeObserver(it) } }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val localUserRepository: LocalUserRepository,
    private val managerRepository: ManagerRepository,
    private val updateRepository: UpdateRepository,
    private val easRepository: EASRepository
) : ViewModel() {

    /**
     * LiveData
     */
    private val localUserController = MutableLiveData<Trigger>()
    val loggedInUserLiveData: LiveData<UserLocal> = localUserController.switchMap{
        val res = MutableLiveData<UserLocal>()
        res.value = localUserRepository.getLoggedInUser()
        return@switchMap res
    }

    // StateFlow 迁移示例：checkUpdateResult 从 LiveData+switchMap 迁移到 Flow 管线。
    // UI 侧用 lifecycleScope.launch { viewModel.checkUpdateResult.collect { ... } } 订阅。
    private var updateRequestSequence = 0L
    private val checkUpdateTrigger = MutableStateFlow<Pair<Long, Long>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val checkUpdateResult: StateFlow<DataState<CheckUpdateResult>> = checkUpdateTrigger
        .filterNotNull()
        .flatMapLatest { (currentCode, _) ->
            flow {
                emit(
                    updateRepository.checkUpdateWithFallback(
                        currentVersionName = BuildConfig.VERSION_NAME,
                        currentVersionCode = currentCode,
                        builtInVersionName = BuildConfig.UPDATE_VERSION_NAME,
                        builtInVersionCode = BuildConfig.UPDATE_VERSION_CODE,
                        updateUrl = BuildConfig.UPDATE_URL,
                        updateLog = BuildConfig.UPDATE_LOG,
                        allowPrerelease = BuildConfig.UPDATE_ALLOW_PRERELEASE
                    ).awaitValue()
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DataState(DataState.STATE.NOTHING)
        )


    fun startRefreshUser() {
        localUserController.value = Trigger.actioning
    }


    fun checkForUpdate(versionCode: Long) {
        checkUpdateTrigger.value = versionCode to ++updateRequestSequence
    }
}
