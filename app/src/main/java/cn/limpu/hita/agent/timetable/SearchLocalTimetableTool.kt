package cn.limpu.hita.agent.timetable

import cn.limpu.hita.agent.core.AgentTool
import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.AgentRetrySafety
import cn.limpu.hita.agent.core.launchAgentIo
import kotlinx.coroutines.ensureActive
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository

class SearchLocalTimetableTool : AgentTool<TimetableAgentInput, TimetableAgentOutput> {
    override val name: String = "search_local_timetable"

    override val retrySafety = AgentRetrySafety.IDEMPOTENT

    override fun execute(
        input: TimetableAgentInput,
        onResult: (AgentToolResult<TimetableAgentOutput>) -> Unit,
    ): AgentCancellation {
        if (input.action != TimetableAgentInput.Action.SEARCH_TIMETABLE) {
            onResult(AgentToolResult.failure("当前工具不支持此操作"))
            return AgentCancellation.NONE
        }

        val keyword = input.keyword?.trim().orEmpty()
        if (keyword.isEmpty()) {
            onResult(AgentToolResult.failure("搜索关键词不能为空"))
            return AgentCancellation.NONE
        }

        return launchAgentIo(onResult) {
            val repository = TimetableRepository(input.application)

            // 跟随主课表显示目标过滤。
            val scope = DisplayScopeResolver(input.application).currentSync()
            ensureActive()
            val events = repository.searchEventsByKeywordSync(keyword, scope?.timetableIds.orEmpty())
                .sortedBy { it.from.time }

            ensureActive()
            AgentToolResult.success(
                    TimetableAgentOutput(
                        action = input.action,
                        timetableId = events.firstOrNull()?.timetableId ?: "",
                        timetableName = scope?.name ?: "全部课表",
                        events = events.map {
                            TimetableEventSnapshot(
                                id = it.id,
                                timetableId = it.timetableId,
                                name = it.name,
                                fromMs = it.from.time,
                                toMs = it.to.time,
                                place = it.place.orEmpty(),
                                teacher = it.teacher.orEmpty(),
                                type = it.type,
                                source = it.source,
                            )
                        },
                    )
                )
        }
    }
}
