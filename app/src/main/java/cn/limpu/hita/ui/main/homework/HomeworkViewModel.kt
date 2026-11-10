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
import cn.limpu.hita.data.repository.grader.GraderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class HomeworkViewModel @Inject constructor(
    private val repository: HomeworkRepository,
    private val graderRepository: GraderRepository,
    easRepository: EASRepository,
) : ViewModel() {
    val state = repository.state
    private val mutableBusy = MutableLiveData(false)
    val busy: LiveData<Boolean> = mutableBusy
    private val mutableToday = MutableLiveData(LocalDate.now().toString())
    val today: LiveData<String> = mutableToday
    val graderState = graderRepository.state

    /**
     * 评阅系统（grader.hitsz.edu.cn）只对深圳校区已登录学生开放；
     * 未登录或非深圳校区时整块入口（登录卡片 + 待评任务列表）都不渲染。
     * 数据源是 EAS token 的 LiveData，退出登录会 publish 空 token，UI 立即收起。
     * 初始值直接取 getEasToken()，避免首帧闪出登录卡片。
     */
    val graderVisible: LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        value = easRepository.getEasToken().isGraderAvailable()
        addSource(easRepository.observeEasToken()) { value = it.isGraderAvailable() }
    }
    private val mutableNow = MutableLiveData(System.currentTimeMillis())
    val now: LiveData<Long> = mutableNow

    /** 同一可见期内只加载一次 Grader 数据；入口收起（退登/切校区）后重置，重新可见时再触发。 */
    private var graderLoadedForVisiblePeriod = false

    private val graderVisibilityObserver = Observer<Boolean> { visible ->
        if (!visible) {
            graderLoadedForVisiblePeriod = false
        } else if (!graderLoadedForVisiblePeriod) {
            graderLoadedForVisiblePeriod = true
            viewModelScope.launch {
                if (graderRepository.reload() && graderState.value?.accountId != null) {
                    graderRepository.refresh()
                }
            }
        }
    }

    init {
        reload()
        // 入口不可见（未登录/非深圳）时不发起 Grader 请求；转入可见时才 reload/refresh。
        graderVisible.observeForever(graderVisibilityObserver)
    }

    override fun onCleared() {
        super.onCleared()
        graderVisible.removeObserver(graderVisibilityObserver)
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

/** 评阅系统入口条件：EAS 已登录且为深圳校区。 */
private fun EASToken?.isGraderAvailable(): Boolean =
    this?.let { it.isLogin() && it.campus == EASToken.Campus.SHENZHEN } ?: false
