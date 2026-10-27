package cn.limpu.hita.agent.subject

import cn.limpu.hita.agent.core.AgentTool
import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.AgentRetrySafety
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.utils.CourseResourceLinker

class ResolveCourseCandidatesTool : AgentTool<SubjectReadmeAgentInput, List<CourseResourceItem>> {
    override val name: String = "resolve_course_candidates"

    override val retrySafety = AgentRetrySafety.IDEMPOTENT

    override fun execute(
        input: SubjectReadmeAgentInput,
        onResult: (AgentToolResult<List<CourseResourceItem>>) -> Unit,
    ): AgentCancellation {
        return CourseResourceLinker.resolveCandidates(
            owner = input.owner,
            courseCodeRaw = input.courseCode,
            courseNameRaw = input.courseName,
            campus = input.campus,
        ) { candidates ->
            onResult(AgentToolResult.success(candidates))
        }
    }
}
