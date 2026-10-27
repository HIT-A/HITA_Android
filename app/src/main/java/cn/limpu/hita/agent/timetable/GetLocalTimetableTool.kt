package cn.limpu.hita.agent.timetable

import cn.limpu.hita.agent.core.AgentTool
import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.AgentRetrySafety
import cn.limpu.hita.agent.core.launchAgentIo
import kotlinx.coroutines.ensureActive
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository

class GetLocalTimetableTool : AgentTool<TimetableAgentInput, TimetableAgentOutput> {
    override val name: String = "get_local_timetable"

    override val retrySafety = AgentRetrySafety.IDEMPOTENT

    override fun execute(
        input: TimetableAgentInput,
        onResult: (AgentToolResult<TimetableAgentOutput>) -> Unit,
    ): AgentCancellation {
        if (input.action != TimetableAgentInput.Action.GET_LOCAL_TIMETABLE) {
            onResult(AgentToolResult.failure("当前工具不支持此操作"))
            return AgentCancellation.NONE
        }

        return launchAgentIo(onResult) {
            val repository = TimetableRepository(input.application)
            var scopeName: String? = null

            val events = if (input.timetableId != null) {
                val timetable = repository.getTimetableByIdSync(input.timetableId)
                    ?: run {
                        return@launchAgentIo AgentToolResult.failure("未找到课表 ${input.timetableId}")
                    }
                ensureActive()
                repository.getEventsOfTimetableSync(timetable.id, input.fromMs, input.toMs)
            } else {
                // 跟随主课表显示目标(独立课表或组合课表),不再查全部课表。
                val scope = DisplayScopeResolver(input.application).currentSync()
                ensureActive()
                scopeName = scope?.name
                repository.getEventsOfTimetablesSync(scope?.timetableIds.orEmpty(), input.fromMs, input.toMs)
            }.sortedBy { it.from.time }

            ensureActive()
            AgentToolResult.success(
                    TimetableAgentOutput(
                        action = input.action,
                        timetableId = events.firstOrNull()?.timetableId ?: "",
                        timetableName = scopeName ?: "全部课表",
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
