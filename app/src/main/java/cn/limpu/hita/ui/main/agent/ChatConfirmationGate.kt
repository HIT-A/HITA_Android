package cn.limpu.hita.ui.main.agent

import cn.limpu.hita.agent.tools.ConfirmationGate
import cn.limpu.hita.agent.tools.ConfirmationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 聊天页的写操作确认门。
 *
 * 语义：
 * - 一次只允许一个待确认请求（[pending] 有值时新请求直接判 `UNAVAILABLE`），
 *   避免多个写工具同时弹窗把用户淹没。
 * - [request] 挂起等待用户选择；[approve] / [reject] 只对当前请求生效。
 * - [cancelAll] 在会话切换、页面销毁、请求被取消时调用，让挂起的工具立刻拿到
 *   `REJECTED` 而不是永久挂住。
 *
 * 线程：`request` 由 ReAct 循环在 IO 线程调用；`approve`/`reject` 从主线程来。
 * [MutableStateFlow] 与 [CompletableDeferred] 都是线程安全的，这里不额外加锁。
 */
class ChatConfirmationGate : ConfirmationGate {

    data class Pending(
        val request: ConfirmationRequest,
        val sessionId: String?,
    )

    private val _state = MutableStateFlow<Pending?>(null)
    val state: StateFlow<Pending?> = _state.asStateFlow()

    private var outstanding: CompletableDeferred<ConfirmationGate.Decision>? = null

    /** 由调用方在每次 ReAct 往返前绑定当前会话，保证确认只作用于发起它的会话。 */
    var sessionIdProvider: () -> String? = { null }

    override suspend fun request(request: ConfirmationRequest): ConfirmationGate.Decision {
        val sessionId = sessionIdProvider()
        // 已有待确认请求：不排队、不覆盖，直接告诉工具现在不能执行。
        if (_state.value != null) return ConfirmationGate.Decision.UNAVAILABLE
        val deferred = CompletableDeferred<ConfirmationGate.Decision>()
        outstanding = deferred
        _state.value = Pending(request = request, sessionId = sessionId)
        return try {
            deferred.await()
        } finally {
            // 无论是用户选择还是协程被取消，都要清掉待确认状态，否则 UI 会卡在对话框上。
            if (_state.value?.sessionId == sessionId) _state.value = null
            if (outstanding === deferred) outstanding = null
        }
    }

    /** 用户点了「执行」。 */
    fun approve() {
        outstanding?.complete(ConfirmationGate.Decision.APPROVED)
    }

    /** 用户点了「取消」。 */
    fun reject() {
        outstanding?.complete(ConfirmationGate.Decision.REJECTED)
    }

    /**
     * 让所有挂起的请求以拒绝收场。
     *
     * @param sessionId 非空时只清理该会话的请求；为空时清理全部（页面销毁/切会话）。
     */
    fun cancelAll(sessionId: String? = null) {
        val pending = _state.value ?: return
        if (sessionId != null && pending.sessionId != sessionId) return
        outstanding?.complete(ConfirmationGate.Decision.REJECTED)
        _state.value = null
        outstanding = null
    }
}
