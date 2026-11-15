package cn.limpu.hita.ui.campus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.campus.SilentPodContent
import cn.limpu.hita.data.source.web.campus.SilentPodClient
import cn.limpu.hita.data.source.web.campus.SilentPodException
import cn.limpu.hita.data.source.web.campus.SilentPodFailureKind
import cn.limpu.hita.data.source.web.campus.SilentPodSessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 静音仓页面的状态持有者。
 *
 * 它只做一次「读空间 + 读我的预约」，并把每种失败翻译成可读文案；
 * 登录本身交给 WebView，Cookie 由 Activity 每次传入，避免把会话缓存到内存里。
 */
internal class SilentPodViewModel(application: Application) : AndroidViewModel(application) {

    private val client = SilentPodClient()

    private val _state = MutableStateFlow<CampusServiceUiState<SilentPodContent>>(
        CampusServiceUiState.Loading,
    )
    val state: StateFlow<CampusServiceUiState<SilentPodContent>> = _state.asStateFlow()

    /** 上一次成功的会话；刷新时复用，避免每刷新一次都要回 WebView 取。 */
    private var session: SilentPodSession? = null

    /** 写操作（创建/取消）的结果，只用于一次性提示，不参与页面主状态。 */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    /**
     * 用当前会话加载；会话缺失直接进入未登录，不发请求。
     */
    fun load(session: SilentPodSession?) {
        this.session = session?.takeIf { SilentPodSessionPolicy.hasSession(it.cookieHeader) }
        if (this.session == null) {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        fetch()
    }

    /** 重新加载当前会话；未登录时保持未登录状态。 */
    fun refresh() {
        if (session == null) {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        fetch()
    }

    /**
     * 创建预约。
     *
     * 成功后立刻重新拉取，让列表反映服务端真实状态；失败只提示，不把整页打成错误态——
     * 空间列表本身仍然可用。
     */
    fun createReservation(spaceId: Int, date: String, startTime: String, endTime: String, notes: String) {
        val current = session ?: run {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        viewModelScope.launch {
            try {
                client.createReservation(
                    cookieHeader = current.cookieHeader,
                    spaceId = spaceId,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    notes = notes,
                    baseUrl = current.baseUrl,
                )
                _actionMessage.value = getApplication<Application>().getString(R.string.silent_pod_reserved)
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SilentPodException) {
                if (failure.kind == SilentPodFailureKind.AUTHENTICATION) session = null
                _actionMessage.value = describe(failure)
            } catch (error: Exception) {
                _actionMessage.value =
                    getApplication<Application>().getString(R.string.silent_pod_error_unknown)
            }
        }
    }

    /** 取消预约；理由留空时服务端写默认理由。 */
    fun cancelReservation(reservationId: Int, reason: String = "") {
        val current = session ?: run {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        viewModelScope.launch {
            try {
                client.cancelReservation(
                    cookieHeader = current.cookieHeader,
                    reservationId = reservationId,
                    reason = reason,
                    baseUrl = current.baseUrl,
                )
                _actionMessage.value = getApplication<Application>().getString(R.string.silent_pod_cancelled)
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SilentPodException) {
                if (failure.kind == SilentPodFailureKind.AUTHENTICATION) session = null
                _actionMessage.value = describe(failure)
            } catch (error: Exception) {
                _actionMessage.value =
                    getApplication<Application>().getString(R.string.silent_pod_error_unknown)
            }
        }
    }

    /** 提示被消费后清空，避免旋转屏幕后又弹一次。 */
    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    private fun fetch() {
        val current = session ?: return
        _state.value = CampusServiceUiState.Loading
        viewModelScope.launch {
            try {
                _state.value = CampusServiceUiState.Ready(
                    client.fetchContent(
                        cookieHeader = current.cookieHeader,
                        baseUrl = current.baseUrl,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SilentPodException) {
                if (failure.kind == SilentPodFailureKind.AUTHENTICATION) {
                    // 会话被服务端拒绝：清掉本地记录，让页面回到「去登录」而不是反复重试。
                    session = null
                }
                _state.value = failure.toState()
            } catch (error: Exception) {
                _state.value = CampusServiceUiState.Failed(
                    getApplication<Application>().getString(R.string.silent_pod_error_unknown),
                )
            }
        }
    }

    /** 把失败翻译成一句可读文案，供整页状态与一次性提示共用。 */
    private fun describe(failure: SilentPodException): String {
        val context = getApplication<Application>()
        return when (failure.kind) {
            SilentPodFailureKind.AUTHENTICATION -> context.getString(R.string.silent_pod_error_auth)
            SilentPodFailureKind.GATEWAY -> context.getString(R.string.silent_pod_error_gateway)
            SilentPodFailureKind.TIMEOUT -> context.getString(R.string.silent_pod_error_timeout)
            SilentPodFailureKind.NETWORK -> context.getString(R.string.silent_pod_error_network)
            SilentPodFailureKind.API -> context.getString(R.string.silent_pod_error_api, failure.detail.orEmpty())
            SilentPodFailureKind.PARSE -> context.getString(R.string.silent_pod_error_parse)
        }
    }

    private fun SilentPodException.toState(): CampusServiceUiState<SilentPodContent> {
        val context = getApplication<Application>()
        return when (kind) {
            SilentPodFailureKind.AUTHENTICATION -> CampusServiceUiState.RequiresSignIn

            SilentPodFailureKind.GATEWAY -> CampusServiceUiState.Failed(
                context.getString(R.string.silent_pod_error_gateway),
            )

            SilentPodFailureKind.TIMEOUT -> CampusServiceUiState.Failed(
                context.getString(R.string.silent_pod_error_timeout),
            )

            SilentPodFailureKind.NETWORK -> CampusServiceUiState.Failed(
                context.getString(R.string.silent_pod_error_network),
            )

            SilentPodFailureKind.API -> CampusServiceUiState.Failed(
                context.getString(R.string.silent_pod_error_api, detail.orEmpty()),
            )

            SilentPodFailureKind.PARSE -> CampusServiceUiState.Failed(
                context.getString(R.string.silent_pod_error_parse),
            )
        }
    }
}
