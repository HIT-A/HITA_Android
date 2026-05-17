package com.limpu.hitax.agent.subject

import com.limpu.hitax.agent.core.AgentEngine
import com.limpu.hitax.agent.core.AgentToolExecutionPolicy
import com.limpu.hitax.agent.core.AgentToolExecutor
import com.limpu.hitax.agent.core.AgentToolResult
import com.limpu.hitax.agent.core.AgentToolRegistry
import com.limpu.hitax.agent.core.AgentTraceEvent
import com.limpu.hitax.agent.core.AgentTraceSanitizer

class SubjectReadmeAgentEngine(
    private val toolRegistry: AgentToolRegistry,
    private val toolExecutor: AgentToolExecutor = AgentToolExecutor(),
) : AgentEngine<SubjectReadmeAgentInput, SubjectReadmeAgentOutput> {

    override fun run(
        input: SubjectReadmeAgentInput,
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (AgentToolResult<SubjectReadmeAgentOutput>) -> Unit,
    ) {
        onTrace(
            AgentTraceEvent(
                stage = "start",
                message = "Resolving candidates for subject",
                payload = AgentTraceSanitizer.sanitizePayload("subjectId=${input.subjectId}"),
            )
        )

        val tool = toolRegistry.get<SubjectReadmeAgentInput, List<com.limpu.hitax.data.model.resource.CourseResourceItem>>("resolve_course_candidates")
        if (tool == null) {
            onResult(AgentToolResult.failure("tool resolve_course_candidates not found"))
            return
        }

        val policy = AgentToolExecutionPolicy(
            timeoutMs = 10000L,
            retryCount = 1,
            retryDelayMs = 180L,
        )
        toolExecutor.execute(
            tool = tool,
            input = input,
            policy = policy,
            onTrace = onTrace,
        ) { result ->
            if (!result.ok) {
                onResult(AgentToolResult.failure(result.error ?: "resolve candidates failed"))
                return@execute
            }

            val candidates = result.data.orEmpty()
            onTrace(
                AgentTraceEvent(
                    stage = "result",
                    message = "Candidates resolved",
                    payload = AgentTraceSanitizer.sanitizePayload("count=${candidates.size}"),
                )
            )
            onResult(AgentToolResult.success(SubjectReadmeAgentOutput(candidates = candidates)))
        }
    }
}
