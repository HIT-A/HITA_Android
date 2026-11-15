package cn.limpu.hita.agent.tools

import android.app.Application
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.model.resource.AgentResourceCard

data class ReActToolInput(
    val actionInput: String,
    val userMessage: String,
    val application: Application,
    val easRepository: EASRepository,
    val timetableId: String?,
    val agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    val onTrace: (AgentTraceEvent) -> Unit,
    val onResourceCards: (List<AgentResourceCard>) -> Unit = {},
    /**
     * 写操作的确认通道。默认 [ConfirmationGate.unavailable]：没有 UI 订阅时
     * 工具不得落盘，只能返回「待确认」。
     */
    val confirmationGate: ConfirmationGate = ConfirmationGate.unavailable,
)

fun interface ReActTool {
    fun execute(input: ReActToolInput): String?
}

/** A finite tool operation that can suspend and follows the caller's cancellation. */
fun interface SuspendingReActTool {
    suspend fun execute(input: ReActToolInput): String?
}

/**
 * 声明结构化状态的工具。
 *
 * 只有实现本接口的工具才会被 [ReActToolRegistry] 记为 `succeeded`/`failure` 等确定状态；
 * 只实现 [SuspendingReActTool] 的工具仍按 `unknown` 统计，因为返回非空文本不等于业务成功。
 */
fun interface OutcomeReActTool {
    suspend fun executeWithOutcome(input: ReActToolInput): ReActToolOutcome
}
