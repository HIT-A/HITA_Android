package cn.limpu.hita.agent.tools

/**
 * 写操作确认的唯一决策点。
 *
 * 把「确认通道返回什么 → 工具该做什么」收敛到一处，避免每个写工具各自解释
 * [ConfirmationGate.Decision]，也避免漏掉「无 UI 时不执行」这条安全默认。
 */
object WriteConfirmation {

    sealed interface Decision {
        /** 已获用户批准，可以执行写操作。 */
        data object Proceed : Decision

        /** 不得执行，直接把 [outcome] 回给模型。 */
        data class Halt(val outcome: ReActToolOutcome) : Decision
    }

    /**
     * @param pendingText 无 UI 订阅时回给模型的说明，必须点明「尚未执行」。
     * @param cancelledText 用户明确拒绝时的说明。
     */
    suspend fun decide(
        gate: ConfirmationGate,
        request: ConfirmationRequest,
        pendingText: String,
        cancelledText: String,
    ): Decision = when (gate.request(request)) {
        ConfirmationGate.Decision.APPROVED -> Decision.Proceed
        ConfirmationGate.Decision.REJECTED -> Decision.Halt(ReActToolOutcome.Cancelled(cancelledText))
        ConfirmationGate.Decision.UNAVAILABLE ->
            Decision.Halt(ReActToolOutcome.NeedsConfirmation(pendingText, request))
    }

    /**
     * 网络类失败无法区分「服务端已处理」与「未处理」，按结果未知处理，
     * 禁止自动重试。
     */
    fun isUncertainFailure(message: String?): Boolean {
        val lowered = message?.lowercase().orEmpty()
        if (lowered.isEmpty()) return false
        return UNCERTAIN_MARKERS.any { lowered.contains(it) }
    }

    private val UNCERTAIN_MARKERS = listOf(
        "timeout", "timed out", "connection", "reset", "unavailable", "502", "503", "504",
    )
}
