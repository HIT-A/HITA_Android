package cn.limpu.hita.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.notice.AppNoticeCenter
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.DisplayScopeSelector
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.EasSettingsRepository
import com.limpu.hitauser.data.model.CheckUpdateResult
import com.limpu.hitauser.data.repository.LocalUserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume

internal sealed interface MainEffect {
    data object SessionExpired : MainEffect
    data class Update(val result: CheckUpdateResult) : MainEffect
    data class Notice(val selection: MainNoticeSelection) : MainEffect
    data class Message(val stringId: Int) : MainEffect
}

internal data class MainHostEffect(val host: Long, val session: MainSession?, val value: MainEffect)

@HiltViewModel
internal class MainViewModel @Inject constructor(
    private val application: Application,
    private val localUsers: LocalUserRepository,
    private val eas: EASRepository,
    private val requests: MainRequests,
    private val settings: EasSettingsRepository,
    private val wallpapers: WallpaperStore,
    private val scopeSelector: DisplayScopeSelector,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state = mutableState.asStateFlow()
    private val effectQueue = Channel<MainHostEffect>(Channel.UNLIMITED)
    val effects = effectQueue.receiveAsFlow()
    private var hostSequence = 0L
    private var activeHost: Long? = null
    private var initialized = false
    private var updateJob: Job? = null
    private var noticeJob: Job? = null
    private var wallpaperJob: Job? = null
    private var pendingUpdate: CheckUpdateResult? = null
    private var pendingSessionExpired: MainSession? = null
    private var noticeFetched = false
    private var pendingNotice: MainNoticeSelection = MainNoticeSelection.None
    private val startup = MainStartupCoordinator(object : MainStartupSettings {
        override fun autoImportEnabled() = settings.isAutoReimportEnabled()
        override fun lastSuccessfulImport() = settings.getLastAutoReimportTs()
        override fun saveSuccessfulImport(time: Long) = settings.setLastAutoReimportTs(time)
    }, System::currentTimeMillis)

    fun initialize(name: String?, index: Int) {
        if (initialized) return
        initialized = true
        mutableState.value = state.value.copy(
            navigation = MainNavigationState.restore(MainTab.visibleFor(eas.getEasToken()), name, index),
            todayTitle = application.getString(R.string.maintab_today),
        )
    }

    fun attachHost(): Long {
        val host = ++hostSequence
        activeHost = host
        if (pendingNotice != MainNoticeSelection.None) emit(host, MainEffect.Notice(pendingNotice))
        pendingUpdate?.let { emit(host, MainEffect.Update(it)) }
        pendingSessionExpired?.takeIf { it == MainSession.from(eas.getEasToken()) }?.let {
            emit(host, MainEffect.SessionExpired, it)
        }
        return host
    }

    fun detachHost(host: Long) {
        if (activeHost != host) return
        activeHost = null
        if (updateJob?.isActive == true) {
            updateJob?.cancel()
            startup.retryInterruptedUpdate()
        }
        noticeJob?.cancel()
        noticeJob = null
    }

    fun accepts(effect: MainHostEffect, host: Long): Boolean =
        activeHost == host && effect.host == host &&
            (effect.session == null || effect.session == MainSession.from(eas.getEasToken()))

    fun start(host: Long, versionCode: Long) {
        if (activeHost != host) return
        accountChanged(eas.getEasToken())
        execute(startup.start(localUsers.getLoggedInUser().isValid(), versionCode))
        requestNotices(host)
    }

    fun accountChanged(token: EASToken) {
        mutableState.value = state.value.copy(navigation = state.value.navigation.withVisibleTabs(MainTab.visibleFor(token)))
        refreshUser()
        execute(startup.accountChanged(MainSession.from(token), MainTab.BLOG in state.value.navigation.visibleTabs))
    }

    fun refreshUser() {
        mutableState.value = state.value.copy(drawer = MainDrawerMapper.map(
            MainLocalUserSnapshot.from(localUsers.getLoggedInUser()),
            MainEasUserSnapshot.from(eas.getEasToken()), eas.isSessionExpired(),
            MainDrawerText(
                application.getString(R.string.eas_account_not_logged_in_title),
                application.getString(R.string.eas_account_not_logged_in_subtitle),
                application.getString(R.string.eas_session_expired_short),
            ),
        ))
    }

    private fun execute(actions: List<MainStartupAction>) {
        actions.forEach { action ->
            when (action) {
                is MainStartupAction.Import -> {
                    viewModelScope.launch {
                        val result = requests.importTimetable(action.session.undergraduate)
                        // Re-read account identity even while the Activity is stopped and observes no token.
                        if (action.session != MainSession.from(eas.getEasToken())) return@launch
                        if (startup.importFinished(action, result)) {
                            eas.markSessionExpired()
                            refreshUser()
                            pendingSessionExpired = action.session
                            activeHost?.let { emit(it, MainEffect.SessionExpired, action.session) }
                        }
                    }
                }
                MainStartupAction.SyncBlog -> requests.syncBlog()
                is MainStartupAction.CheckUpdate -> checkUpdate(action.versionCode)
            }
        }
    }

    private fun checkUpdate(versionCode: Long) {
        updateJob?.cancel()
        val host = activeHost ?: return
        updateJob = viewModelScope.launch {
            val result = requests.checkUpdate(versionCode)
            if (result.data?.shouldUpdate == true) {
                pendingUpdate = requireNotNull(result.data)
                emit(host, MainEffect.Update(requireNotNull(result.data)))
            }
        }
    }

    private fun requestNotices(host: Long) {
        if (noticeFetched || noticeJob?.isActive == true) return
        noticeJob = viewModelScope.launch {
            val notices = requests.notices()
            pendingNotice = MainNoticePolicy.select(notices, AppNoticeCenter.seenNoticeIds(application), BuildConfig.VERSION_CODE.toLong())
            noticeFetched = true
            if (pendingNotice != MainNoticeSelection.None) emit(host, MainEffect.Notice(pendingNotice))
        }
    }

    fun noticeFinished(selection: MainNoticeSelection) {
        if (pendingNotice == selection) pendingNotice = MainNoticeSelection.None
    }

    fun updateFinished(result: CheckUpdateResult) { if (pendingUpdate === result) pendingUpdate = null }
    fun sessionExpiredPresented() { pendingSessionExpired = null }

    private fun emit(host: Long, effect: MainEffect, session: MainSession? = null) {
        if (activeHost == host) effectQueue.trySend(MainHostEffect(host, session, effect))
    }

    fun saveAvatar(uri: Uri) {
        val host = activeHost ?: return
        viewModelScope.launch {
            val success = requests.saveAvatar(uri)
            refreshUser()
            emit(host, MainEffect.Message(if (success) R.string.main_avatar_changed else R.string.main_avatar_failed))
        }
    }

    fun saveWallpaper(uri: Uri) = changeWallpaper(uri)
    fun removeWallpaper() = changeWallpaper(null)
    fun validateWallpaperPath(path: String?) {
        if (path.isNullOrBlank()) return
        viewModelScope.launch {
            try {
                wallpapers.clearMissingSelection(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                cn.limpu.hita.utils.LogUtils.e("Cannot clear missing wallpaper selection", error)
            }
        }
    }

    private fun changeWallpaper(uri: Uri?) {
        val request = wallpapers.beginChange()
        wallpaperJob?.cancel()
        val host = activeHost
        wallpaperJob = viewModelScope.launch {
            try {
                if (uri == null) wallpapers.remove(request) else wallpapers.replace(request, uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (host != null) emit(host, MainEffect.Message(R.string.wallpaper_save_failed))
            }
        }
    }

    fun logout() {
        if (localUsers.getLoggedInUser().isValid()) localUsers.logout(application)
        eas.logout()
        accountChanged(eas.getEasToken())
    }

    fun reloginSucceeded() { startup.reloginSucceeded(); accountChanged(eas.getEasToken()) }
    fun selectTab(index: Int) { mutableState.value = state.value.copy(navigation = state.value.navigation.selectIndex(index)) }
    fun setDrawer(open: Boolean) { if (open) refreshUser(); mutableState.value = state.value.copy(drawerOpen = open) }
    fun setTimelineTitle(title: String) { mutableState.value = state.value.copy(todayTitle = title) }
    fun setTimetableTitle(title: String, showName: Boolean) { mutableState.value = state.value.copy(timetableTitle = title, showTimetableName = showName) }
    fun setTimetableName(name: String) { mutableState.value = state.value.copy(timetableName = name, showTimetableName = true) }
    fun setScopes(options: List<DisplayScopeResolver.ScopeOption>) { mutableState.value = state.value.copy(scopeOptions = options.toList()) }
    fun setScope(scope: DisplayScope?) { mutableState.value = state.value.copy(currentScope = scope) }
    fun setBlogDot(show: Boolean) { mutableState.value = state.value.copy(blogDotVisible = show) }
    fun selectScope(index: Int): Long? {
        val option = state.value.scopeOptions.getOrNull(index) ?: return null
        scopeSelector.select(option.scope)
        return mainScopeTargetMonday(option.startMillis, option.endMillis, System.currentTimeMillis())
    }

    override fun onCleared() {
        activeHost = null
        startup.close()
        effectQueue.close()
        super.onCleared()
    }
}

/** Main-confined LiveData observation; cancellation removes the observer on Main as well. */
internal suspend fun <T> LiveData<T>.awaitMainValue(): T = suspendCancellableCoroutine { continuation ->
    val completed = AtomicBoolean(false)
    lateinit var observer: Observer<T>
    observer = Observer { value ->
        if (value != null && completed.compareAndSet(false, true)) {
            removeObserver(observer)
            if (continuation.isActive) continuation.resume(value)
        }
    }
    continuation.invokeOnCancellation {
        completed.set(true)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) removeObserver(observer)
        else android.os.Handler(android.os.Looper.getMainLooper()).post { removeObserver(observer) }
    }
    if (continuation.isActive) observeForever(observer)
}
