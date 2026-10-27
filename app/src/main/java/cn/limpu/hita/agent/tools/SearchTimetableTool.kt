package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.timetable.TimetableAgentInput

class SearchTimetableTool : ReActTool {
    override fun execute(input: ReActToolInput): String {
        val keyword = extractKeyword(input.actionInput)
        if (keyword.isBlank()) {
            return "搜索失败: 无法解析关键词"
        }

        val agentInput = TimetableAgentInput(
            application = input.application,
            action = TimetableAgentInput.Action.SEARCH_TIMETABLE,
            timetableId = input.timetableId,
            keyword = keyword,
        )

        return ToolHelper.formatTimetableResult(ToolHelper.runTimetableToolSync(agentInput, input.agentProvider, input.onTrace))
    }

    private fun extractKeyword(actionInput: String): String {
        val keys = listOf("query", "keyword", "param", "name")
        keys.forEach { key ->
            val regex = Regex(""""$key"\s*:\s*"([^"]+)""")
            val value = regex.find(actionInput)?.groupValues?.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return actionInput.trim().removeSurrounding("{", "}")
    }
}
