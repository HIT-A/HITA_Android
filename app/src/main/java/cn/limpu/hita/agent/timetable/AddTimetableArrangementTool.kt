package cn.limpu.hita.agent.timetable

import cn.limpu.hita.agent.core.AgentTool
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.ScheduleEventCreator
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCaseFactory
import cn.limpu.hita.utils.LogUtils
import kotlin.concurrent.thread

class AddTimetableArrangementTool : AgentTool<TimetableAgentInput, TimetableAgentOutput> {
    override val name: String = "add_timetable_arrangement"

    override fun execute(
        input: TimetableAgentInput,
        onResult: (AgentToolResult<TimetableAgentOutput>) -> Unit,
    ) {
        if (input.action != TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT) {
            onResult(AgentToolResult.failure("当前工具不支持此操作"))
            return
        }

        val arrangement = input.arrangement
        if (arrangement == null) {
            onResult(AgentToolResult.failure("缺少活动时间安排"))
            return
        }
        if (arrangement.name.isBlank()) {
            onResult(AgentToolResult.failure("缺少活动名称"))
            return
        }
        if (arrangement.toMs <= arrangement.fromMs) {
            onResult(AgentToolResult.failure("活动结束时间必须晚于开始时间"))
            return
        }

        thread(start = true) {
            try {
                LogUtils.d("[DEBUG] Starting tool execution")
                val repository = TimetableRepository(input.application)
                LogUtils.d("[DEBUG] Got repository instance")

                val timetable = input.timetableId
                    ?.let { repository.getTimetableByIdSync(it) }
                    ?: DisplayScopeResolver(input.application).currentSync()
                        ?.let { scope ->
                            // 显示目标内当前时间命中的课表;无命中取目标内最早一份。
                            val now = System.currentTimeMillis()
                            scope.timetables.filter { now in it.startTime.time..it.endTime.time }
                                .minByOrNull { it.startTime.time }
                                ?: scope.timetables.firstOrNull()
                        }
                    ?: repository.ensureDefaultCustomTimetableSync()
                LogUtils.d("[DEBUG] Got timetable: id=${timetable.id}, name=${timetable.name}")

                val result = ScheduleEventCreator.buildEvents(
                    timetable = timetable,
                    content = ScheduleEventCreator.Content(
                        name = arrangement.name,
                        place = arrangement.place.orEmpty(),
                        teacher = arrangement.teacher.orEmpty(),
                        subject = null,
                        type = arrangement.type,
                    ),
                    ranges = listOf(
                        ScheduleEventCreator.FixedRange(
                            fromMs = arrangement.fromMs,
                            toMs = arrangement.toMs,
                        )
                    ),
                    source = cn.limpu.hita.data.model.timetable.EventItem.SOURCE_AGENT,
                )
                LogUtils.d("[DEBUG] Created events: count=${result.events.size}, timetableId=${timetable.id}")

                TimetableWriteUseCaseFactory(input.application).create(repository).addSync(result)

                LogUtils.d("[DEBUG] Tool execution successful, returning result")
                onResult(
                    AgentToolResult.success(
                        TimetableAgentOutput(
                            action = input.action,
                            timetableId = timetable.id,
                            timetableName = timetable.name,
                            addedEventIds = result.events.map { it.id },
                        )
                    )
                )
            } catch (e: Exception) {
                LogUtils.e("[DEBUG] Tool execution failed", e)
                onResult(AgentToolResult.failure(e.message ?: "添加活动失败"))
            }
        }
    }
}
