package cn.limpu.hita.ui.campus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.campus.SilentPodContact
import cn.limpu.hita.data.model.campus.SilentPodContent
import cn.limpu.hita.data.source.web.campus.SilentPodClient
import cn.limpu.hita.data.source.web.campus.SilentPodException
import cn.limpu.hita.data.source.web.campus.SilentPodFailureKind
import cn.limpu.hita.data.source.web.campus.SilentPodSessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    /** 写操作（创建/取消）的结果，只用于一次性提示，不参与页面主状态。 */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    /** 同行成员：多人仓预约必填，单人仓用不到。 */
    private val _contacts = MutableStateFlow<List<SilentPodContact>>(emptyList())
    val contacts: StateFlow<List<SilentPodContact>> = _contacts.asStateFlow()

    /** 服务端记住的常用手机号，用于预填预约对话框；拿不到就是空串。 */
    private val _defaultMobile = MutableStateFlow("")
    val defaultMobile: StateFlow<String> = _defaultMobile.asStateFlow()

    /**
     * 用当前会话加载；会话缺失直接进入未登录，不发请求。
     */
    fun load(session: SilentPodSession?) {
        loadJob?.cancel()
        actionJob?.cancel()
        _actionMessage.value = null
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
    fun createReservation(
        spaceId: Int,
        date: String,
        startTime: String,
        endTime: String,
        notes: String,
        contactIds: List<Int> = emptyList(),
    ) {
        val current = session ?: run {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            try {
                client.createReservation(
                    cookieHeader = current.cookieHeader,
                    spaceId = spaceId,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    notes = notes,
                    contactIds = contactIds,
                    baseUrl = current.baseUrl,
                )
                _actionMessage.value = getApplication<Application>().getString(R.string.silent_pod_reserved)
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SilentPodException) {
                if (failure.kind == SilentPodFailureKind.AUTHENTICATION) session = null
                _actionMessage.value = describe(failure)
                // 时间类失败基本都是列表过期（窗口起点已滑走 / 时段被抢）：
                // 顺手刷新一次，用户重开对话框拿到的就是新数据。
                if (failure.kind == SilentPodFailureKind.API && failure.code in STALE_SLOT_CODES) fetch()
            } catch (error: Exception) {
                _actionMessage.value =
                    getApplication<Application>().getString(R.string.silent_pod_error_unknown)
            }
        }
    }

    /**
     * 添加同行成员：先按证号核验姓名，确认一致后再保存。
     *
     * 姓名对不上就不保存——否则会存成「名字是 A、证件是 B」的联系人，
     * 之后每次多人预约都会用错人。
     */
    fun addContact(name: String, cardNo: String, onAdded: (SilentPodContact) -> Unit) {
        val current = session ?: run {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        val context = getApplication<Application>()
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            try {
                // 读者核验只是「尽力而为」：bdc 服务在公网直连下稳定返回
                // ACCESS_CHECK（用户不存在），拿它当前置条件会让添加成员永远失败。
                // 拿到结果就比对姓名，拿不到就交给服务端在创建时判断。
                val reader = try {
                    client.verifyReader(current.cookieHeader, cardNo, current.baseUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SilentPodException) {
                    null
                }
                if (reader != null && !reader.name.equals(name.trim(), ignoreCase = true)) {
                    _actionMessage.value = context.getString(
                        R.string.silent_pod_contact_name_mismatch,
                        reader.name,
                    )
                    return@launch
                }
                val contact = client.createContact(
                    cookieHeader = current.cookieHeader,
                    name = reader?.name ?: name.trim(),
                    cardNo = reader?.cardNo?.ifBlank { cardNo.trim() } ?: cardNo.trim(),
                    baseUrl = current.baseUrl,
                )
                if (_contacts.value.none { it.id == contact.id }) {
                    _contacts.value = _contacts.value + contact
                }
                onAdded(contact)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SilentPodException) {
                if (failure.kind == SilentPodFailureKind.AUTHENTICATION) session = null
                _actionMessage.value = describe(failure)
            } catch (error: Exception) {
                _actionMessage.value = context.getString(R.string.silent_pod_error_unknown)
            }
        }
    }

    /** 删除已保存的同行成员。 */
    fun removeContact(contact: SilentPodContact) {
        val current = session ?: run {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        val context = getApplication<Application>()
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            try {
                client.removeContact(current.cookieHeader, contact.id, current.baseUrl)
                _contacts.value = _contacts.value.filterNot { it.id == contact.id }
                _actionMessage.value = context.getString(R.string.silent_pod_contact_removed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SilentPodException) {
                if (failure.kind == SilentPodFailureKind.AUTHENTICATION) session = null
                _actionMessage.value = describe(failure)
            } catch (error: Exception) {
                _actionMessage.value = context.getString(R.string.silent_pod_error_unknown)
            }
        }
    }

    /** 取消预约；理由留空时服务端写默认理由。 */
    fun cancelReservation(reservationId: Int, reason: String = "") {
        val current = session ?: run {
            _state.value = CampusServiceUiState.RequiresSignIn
            return
        }
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
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
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                _state.value = CampusServiceUiState.Ready(
                    client.fetchContent(
                        cookieHeader = current.cookieHeader,
                        baseUrl = current.baseUrl,
                    ),
                )
                // 同行成员是多人仓才用的附加数据：拉失败也不影响空间列表展示。
                runCatching {
                    client.fetchContacts(current.cookieHeader, current.baseUrl)
                }.onSuccess { payload ->
                    _contacts.value = payload.contacts
                    payload.defaultMobile?.takeIf { it.isNotBlank() }?.let {
                        _defaultMobile.value = it
                    }
                }
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
            SilentPodFailureKind.API -> apiMessage(failure)
                ?: context.getString(R.string.silent_pod_error_api, failure.detail.orEmpty())
            SilentPodFailureKind.PARSE -> context.getString(R.string.silent_pod_error_parse)
        }
    }

    /**
     * 服务端业务错误码 → 可操作文案。
     *
     * 服务端 `errorMsg` 常常过于笼统（`INVALID_FIELD` 只写「预约时间异常」），
     * 直接贴给用户既看不懂也不知道下一步做什么，所以按错误码收敛成建议。
     * 未收录的码返回 `null`，由调用方退回原始信息。
     */
    private fun apiMessage(failure: SilentPodException): String? {
        val resId = when (failure.code) {
            "INVALID_FIELD", "DATA_ANOMALIES", "RESERVATION_TIME_PAST",
            "NO_ALLOW_RESERVATION_TIME", "NO_IN_OPEN_DURATION",
            -> R.string.silent_pod_code_time_invalid

            "LESS_RESERVATION_DURATION", "MORE_RESERVATION_DURATION" ->
                R.string.silent_pod_code_duration

            "RESERVATION_TIME_CONFLICT" -> R.string.silent_pod_code_conflict

            "RESERVATION_NO_COMPLETE", "TEAM_RESERVATION_NO_COMPLETE" ->
                R.string.silent_pod_code_no_complete

            "TOTAL_TIME_EXCEEDING_LIMIT" -> R.string.silent_pod_code_quota

            "PEOPLE_NUM_NO_EQUAL", "TEAM_PEOPLE_NUM_NO_EQUAL" -> R.string.silent_pod_code_people

            "SPACE_NO_EXIST", "SPACE_DISABLE", "DISABLE_STATUS", "DISABLE_RESERVATION" ->
                R.string.silent_pod_code_space

            "ACCESS_DENIED", "IDENTIFY_SCOPE" -> R.string.silent_pod_code_access

            "IN_BLACKLIST" -> R.string.silent_pod_code_blacklist

            "NO_EXISTS" -> R.string.silent_pod_contact_not_found

            "ALREADY_EXISTS" -> R.string.silent_pod_contact_exists

            "BUSY" -> R.string.silent_pod_contact_busy

            else -> return null
        }
        return getApplication<Application>().getString(resId)
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
                apiMessage(this) ?: context.getString(R.string.silent_pod_error_api, detail.orEmpty()),
            )

            SilentPodFailureKind.PARSE -> CampusServiceUiState.Failed(
                context.getString(R.string.silent_pod_error_parse),
            )
        }
    }

    private companion object {
        /**
         * 出现即说明「手上的时段已经不准了」的错误码：窗口滑动、时段被抢、
         * 时长规则变化都会落到这里，顺手刷新一次列表比让用户自己点刷新更省事。
         */
        val STALE_SLOT_CODES = setOf(
            "INVALID_FIELD",
            "DATA_ANOMALIES",
            "RESERVATION_TIME_PAST",
            "NO_ALLOW_RESERVATION_TIME",
            "NO_IN_OPEN_DURATION",
            "RESERVATION_TIME_CONFLICT",
            "LESS_RESERVATION_DURATION",
            "MORE_RESERVATION_DURATION",
        )
    }
}
