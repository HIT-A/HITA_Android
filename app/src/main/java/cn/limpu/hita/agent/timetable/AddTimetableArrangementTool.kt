package cn.limpu.hita.agent.timetable

import android.app.Application
import cn.limpu.hita.agent.core.AgentTool
import cn.limpu.hita.agent.core.AgentCancellation
import cn.limpu.hita.agent.core.launchAgentIo
import kotlinx.coroutines.ensureActive
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.ScheduleEventCreator
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCase
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCaseFactory

class AddTimetableArrangementTool(
    private val repositoryFactory: (Application) -> TimetableRepository = { TimetableRepository(it) },
    private val writeFactory: (Application, TimetableRepository) -> TimetableWriteUseCase = { app, repository ->
        TimetableWriteUseCaseFactory(app).create(repository)
    },
) : AgentTool<TimetableAgentInput, TimetableAgentOutput> {
    override val name: String = "add_timetable_arrangement"

    override fun execute(
        input: TimetableAgentInput,
        onResult: (AgentToolResult<TimetableAgentOutput>) -> Unit,
    ): AgentCancellation {
        if (input.action != TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT) {
            onResult(AgentToolResult.failure("当前工具不支持此操作"))
            return AgentCancellation.NONE
        }

        val arrangement = input.arrangement
        if (arrangement == null) {
            onResult(AgentToolResult.failure("缺少活动时间安排"))
            return AgentCancellation.NONE
        }
        if (arrangement.name.isBlank()) {
            onResult(AgentToolResult.failure("缺少活动名称"))
            return AgentCancellation.NONE
        }
        if (arrangement.toMs <= arrangement.fromMs) {
            onResult(AgentToolResult.failure("活动结束时间必须晚于开始时间"))
            return AgentCancellation.NONE
        }

        return launchAgentIo(onResult) {
            val repository = repositoryFactory(input.application)

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
                ?: run { ensureActive(); repository.ensureDefaultCustomTimetableSync { ensureActive() } }

            ensureActive()
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

            ensureActive()
            writeFactory(input.application, repository).addSync(result, beforeWrite = { ensureActive() })

            ensureActive()
            AgentToolResult.success(
                    TimetableAgentOutput(
                        action = input.action,
                        timetableId = timetable.id,
                        timetableName = timetable.name,
                        addedEventIds = result.events.map { it.id },
                    )
                )
        }
    }
}
