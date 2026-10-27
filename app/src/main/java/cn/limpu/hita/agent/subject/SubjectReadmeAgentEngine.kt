package cn.limpu.hita.agent.subject

import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.notifyAgentObserver
import cn.limpu.hita.agent.core.AgentEngine
import cn.limpu.hita.agent.core.AgentToolExecutionPolicy
import cn.limpu.hita.agent.core.AgentToolExecutor
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.agent.core.AgentToolRegistry
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.core.AgentTraceSanitizer

class SubjectReadmeAgentEngine(
    private val toolRegistry: AgentToolRegistry,
    private val toolExecutor: AgentToolExecutor = AgentToolExecutor(),
) : AgentEngine<SubjectReadmeAgentInput, SubjectReadmeAgentOutput> {

    override fun run(
        input: SubjectReadmeAgentInput,
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (AgentToolResult<SubjectReadmeAgentOutput>) -> Unit,
    ): AgentCancellation {
        notifyAgentObserver { onTrace(
            AgentTraceEvent(
                stage = "start",
                message = "正在匹配课程资料候选项",
                payload = AgentTraceSanitizer.sanitizePayload("subjectId=${input.subjectId}"),
            )
        ) }

        val tool = toolRegistry.get<SubjectReadmeAgentInput, List<cn.limpu.hita.data.model.resource.CourseResourceItem>>("resolve_course_candidates")
        if (tool == null) {
            notifyAgentObserver { onResult(AgentToolResult.failure("未找到课程资料候选匹配工具")) }
            return AgentCancellation.NONE
        }

        val policy = AgentToolExecutionPolicy(
            timeoutMs = 10000L,
            retryCount = 1,
            retryDelayMs = 180L,
        )
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
            val candidates = (result as AgentToolResult.Success).value
            notifyAgentObserver { onTrace(
                AgentTraceEvent(
                    stage = "result",
                    message = "课程资料候选项匹配完成",
                    payload = AgentTraceSanitizer.sanitizePayload("count=${candidates.size}"),
                )
            ) }
            onResult(result.map { SubjectReadmeAgentOutput(candidates = it) })
        }
    }
}
