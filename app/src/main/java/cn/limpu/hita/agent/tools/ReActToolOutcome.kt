package cn.limpu.hita.agent.tools

/**
 * 结构化工具结果。
 *
 * 契约：
 * - [observation] 是回给模型的文本，任何状态下都必须可读。
 * - 只有显式声明状态的工具才可信；未声明者一律按 [OutcomeUnknown] 处理，
 *   避免把「没有抛异常」误当成业务成功。
 */
sealed interface ReActToolOutcome {
    val observation: String

    data class Succeeded(override val observation: String) : ReActToolOutcome

    /** [retryable] 为 true 时模型可以换参数重试；业务失败通常不可重试。 */
    data class Failed(
        override val observation: String,
        val retryable: Boolean = false,
    ) : ReActToolOutcome

    /** 请求可能已被远端处理但结果未知；禁止自动重试，交由用户决定。 */
    data class OutcomeUnknown(override val observation: String) : ReActToolOutcome

    data class Cancelled(override val observation: String) : ReActToolOutcome

    /** 尚未执行，等待用户确认。 */
    data class NeedsConfirmation(
        override val observation: String,
        val request: ConfirmationRequest,
    ) : ReActToolOutcome

    companion object {
        /** 兼容旧工具：非空字符串视为成功文本，但状态仍为未知。 */
        fun fromLegacy(observation: String?): ReActToolOutcome =
            OutcomeUnknown(observation.orEmpty())

        fun statusName(outcome: ReActToolOutcome): String = when (outcome) {
            is Succeeded -> "succeeded"
            is Failed -> "failure"
            is OutcomeUnknown -> "unknown"
            is Cancelled -> "cancelled"
            is NeedsConfirmation -> "pending_confirmation"
        }
    }
}

/**
 * 待确认操作的描述。文案由工具提供，UI 只负责展示与回传选择，
 * 不解释业务语义。
 */
data class ConfirmationRequest(
    val title: String,
    val detailLines: List<String>,
    val confirmLabel: String = "执行",
    val cancelLabel: String = "取消",
)

/**
 * 确认结果的传递通道。
 *
 * 工具通过它询问用户；没有订阅方时（headless、测试、后台）返回 [Decision.Unavailable]，
 * 工具必须据此拒绝执行写操作。
 */
fun interface ConfirmationGate {
    suspend fun request(request: ConfirmationRequest): Decision

    enum class Decision { APPROVED, REJECTED, UNAVAILABLE }

    companion object {
        /** 无 UI 订阅时的安全默认：不执行。 */
        val unavailable: ConfirmationGate = ConfirmationGate { Decision.UNAVAILABLE }
    }
}
