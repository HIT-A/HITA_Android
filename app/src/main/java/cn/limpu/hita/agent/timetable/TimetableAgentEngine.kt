package cn.limpu.hita.agent.timetable

import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.notifyAgentObserver
import cn.limpu.hita.agent.core.AgentEngine
import cn.limpu.hita.agent.core.AgentToolExecutionPolicy
import cn.limpu.hita.agent.core.AgentToolExecutor
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.agent.core.AgentToolRegistry
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.core.AgentTraceSanitizer

class TimetableAgentEngine(
    private val toolRegistry: AgentToolRegistry,
    private val toolExecutor: AgentToolExecutor = AgentToolExecutor(),
) : AgentEngine<TimetableAgentInput, TimetableAgentOutput> {

    override fun run(
        input: TimetableAgentInput,
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (AgentToolResult<TimetableAgentOutput>) -> Unit,
    ): AgentCancellation {
        notifyAgentObserver { onTrace(
            AgentTraceEvent(
                stage = "start",
                message = "正在运行课表智能体",
                payload = AgentTraceSanitizer.sanitizePayload("action=${input.action},timetableId=${input.timetableId.orEmpty()}"),
            )
        ) }

        val toolName = when (input.action) {
            TimetableAgentInput.Action.GET_LOCAL_TIMETABLE -> "get_local_timetable"
            TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT -> "add_timetable_arrangement"
            TimetableAgentInput.Action.SEARCH_TIMETABLE -> "search_local_timetable"
        }

        val tool = toolRegistry.get<TimetableAgentInput, TimetableAgentOutput>(toolName)
        if (tool == null) {
            notifyAgentObserver { onResult(AgentToolResult.failure("未找到所需的课表工具")) }
            return AgentCancellation.NONE
        }

        val policy = if (input.action == TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT) {
            // A timed-out insert may still commit. Retrying it creates a second event.
            AgentToolExecutionPolicy(timeoutMs = 5000L, retryCount = 0)
        } else {
            AgentToolExecutionPolicy(timeoutMs = 5000L, retryCount = 1, retryDelayMs = 180L)
        }

        return toolExecutor.execute(
            tool = tool,
            input = input,
            policy = policy,
            onTrace = onTrace,
        ) { result ->
            if (result is AgentToolResult.Failure) {
                onResult(result)
                return@execute
            }
            val output = (result as AgentToolResult.Success).value
            notifyAgentObserver { onTrace(
                AgentTraceEvent(
                    stage = "result",
                    message = "课表智能体执行完成",
                    payload = AgentTraceSanitizer.sanitizePayload(
                        "action=${output.action},eventCount=${output.events.size},addedCount=${output.addedEventIds.size}"
                    ),
                )
            ) }
            onResult(result)
        }
    }
}
