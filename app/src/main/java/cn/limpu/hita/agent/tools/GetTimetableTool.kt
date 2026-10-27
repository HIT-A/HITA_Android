package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.timetable.TimetableAgentInput
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class GetTimetableTool : ReActTool {
    override fun execute(input: ReActToolInput): String {
        val dayOffset = ToolHelper.parseDayOffset(input.userMessage)
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fromMs = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val toMs = cal.timeInMillis

        val agentInput = TimetableAgentInput(
            application = input.application,
            action = TimetableAgentInput.Action.GET_LOCAL_TIMETABLE,
            timetableId = input.timetableId,
            fromMs = fromMs,
            toMs = toMs
        )

        val info = ToolHelper.formatTimetableResult(ToolHelper.runTimetableToolSync(agentInput, input.agentProvider, input.onTrace))

        val dateLabel = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(Date(fromMs))

        return if (info.isBlank()) {
            "[$dateLabel] 没有课程安排"
        } else {
            "[$dateLabel]\n$info"
        }
    }
}