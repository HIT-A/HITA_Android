package cn.limpu.hita.agent.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [ConfirmationRequest] 编成 trace 的 payload 文本，再从文本解回来。
 *
 * 为什么需要它：ReAct 循环跑在 IO 线程，而确认对话框必须在主线程由用户操作。
 * 两者之间只有 `AgentTraceEvent` 这条现成通道，因此请求要能被序列化过去。
 * 这里只做编解码，不含任何业务判断，便于用 JVM 单测锁住往返一致。
 */
object ConfirmationRequestCodec {

    /** trace 的 stage 名；UI 只按这个常量识别待确认事件。 */
    const val STAGE = "confirmation_required"

    private const val KEY_TITLE = "title"
    private const val KEY_DETAILS = "detailLines"
    private const val KEY_CONFIRM = "confirmLabel"
    private const val KEY_CANCEL = "cancelLabel"

    fun encode(request: ConfirmationRequest): String = JSONObject().apply {
        put(KEY_TITLE, request.title)
        put(KEY_DETAILS, JSONArray(request.detailLines))
        put(KEY_CONFIRM, request.confirmLabel)
        put(KEY_CANCEL, request.cancelLabel)
    }.toString()

    /**
     * @return 解码成功返回请求；payload 不是本格式（例如普通文本 trace）时返回 null
     */
    fun decode(payload: String?): ConfirmationRequest? {
        if (payload.isNullOrBlank()) return null
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val title = json.optString(KEY_TITLE).trim()
        if (title.isEmpty()) return null
        val details = json.optJSONArray(KEY_DETAILS)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        return ConfirmationRequest(
            title = title,
            detailLines = details,
            confirmLabel = json.optString(KEY_CONFIRM).ifBlank { "执行" },
            cancelLabel = json.optString(KEY_CANCEL).ifBlank { "取消" },
        )
    }
}
