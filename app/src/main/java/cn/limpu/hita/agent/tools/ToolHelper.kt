package cn.limpu.hita.agent.tools

import android.app.Application
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.agent.core.AgentToolResult
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.utils.AppConstants
import cn.limpu.hita.utils.LogUtils
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared infrastructure for ReAct tool implementations. */
internal object ToolHelper {

    private val prServerBaseUrl = BuildConfig.HOA_BASE_URL.removeSuffix("/")
    private val prServerApiKey = BuildConfig.HOA_API_KEY

    fun prServerRequest(url: String): Connection {
        val req = Jsoup.connect(url)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(AppConstants.Network.READ_TIMEOUT.toInt())
            .header("Accept", "application/json")
            .header("User-Agent", "HITA_Agent/${BuildConfig.VERSION_NAME}")
        if (prServerApiKey.isNotBlank()) req.header("X-Api-Key", prServerApiKey)
        return req
    }

    fun parseDayOffset(text: String): Int {
        val cal = Calendar.getInstance()
        val todayDow = cal.get(Calendar.DAY_OF_WEEK)
        val dowMap = mapOf(
            "周一" to Calendar.MONDAY, "星期一" to Calendar.MONDAY,
            "周二" to Calendar.TUESDAY, "星期二" to Calendar.TUESDAY,
            "周三" to Calendar.WEDNESDAY, "星期三" to Calendar.WEDNESDAY,
            "周四" to Calendar.THURSDAY, "星期四" to Calendar.THURSDAY,
            "周五" to Calendar.FRIDAY, "星期五" to Calendar.FRIDAY,
            "周六" to Calendar.SATURDAY, "星期六" to Calendar.SATURDAY,
            "周日" to Calendar.SUNDAY, "星期日" to Calendar.SUNDAY, "周天" to Calendar.SUNDAY,
        )
        if (text.contains("今天")) return 0
        if (text.contains("大前天")) return -3
        if (text.contains("前天")) return -2
        if (text.contains("昨天")) return -1
        if (text.contains("明天")) return 1
        if (text.contains("大后天")) return 3
        if (text.contains("后天")) return 2
        for ((label, dow) in dowMap) {
            if (text.contains(label)) {
                var diff = dow - todayDow
                diff = when {
                    text.contains("上个") || text.contains("上周") -> {
                        if (diff >= 0) diff - 7 else diff
                    }
                    text.contains("下个") || text.contains("下周") -> {
                        if (diff <= 0) diff + 7 else diff
                    }
                    else -> diff
                }
                return diff
            }
        }
        val dateRegex = Regex("(\\d{1,2})月(\\d{1,2})")
        val match = dateRegex.find(text) ?: return 0
        val month = match.groupValues[1].toIntOrNull() ?: return 0
        val day = match.groupValues[2].toIntOrNull() ?: return 0
        val target = Calendar.getInstance().apply { set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, day) }
        val diffMs = target.timeInMillis - cal.timeInMillis
        val diffDays = (diffMs / (24 * 3600 * 1000)).toInt()
        return if (diffDays in -365..365) diffDays else 0
    }

    suspend fun runTimetableTool(
        input: TimetableAgentInput,
        agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        onTrace: (AgentTraceEvent) -> Unit,
    ): AgentToolResult<TimetableAgentOutput> =
        TypedAgentSuspendingAdapter.run(input, agentProvider, onTrace)

    /** ReAct still consumes text; this is the sole explicit typed-to-text boundary. */
    fun formatTimetableResult(result: AgentToolResult<TimetableAgentOutput>): String = when (result) {
        is AgentToolResult.Failure -> "工具执行失败 [${result.kind}]: ${result.message}"
        is AgentToolResult.Success -> {
            val output = result.value
            when (output.action) {
                TimetableAgentInput.Action.GET_LOCAL_TIMETABLE -> {
                    if (output.events.isEmpty()) ""
                    else {
                        val sdf = SimpleDateFormat("HH:mm", Locale.CHINA)
                        buildString {
                            output.events.take(15).forEach { ev ->
                                val from = sdf.format(Date(ev.fromMs))
                                val to = sdf.format(Date(ev.toMs))
                                append("- ${ev.name}  $from~$to")
                                if (ev.place.isNotBlank()) append("  @ ${ev.place}")
                                append("\n")
                            }
                        }
                    }
                }
                TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT -> {
                    if (output.addedEventIds.isNotEmpty()) "已成功添加 ${output.addedEventIds.size} 个活动。"
                    else "活动添加成功。"
                }
                TimetableAgentInput.Action.SEARCH_TIMETABLE -> {
                    if (output.events.isEmpty()) "未找到匹配的事件。"
                    else {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                        buildString {
                            append("找到 ${output.events.size} 个匹配的事件:\n")
                            output.events.take(20).forEach { ev ->
                                val from = sdf.format(Date(ev.fromMs))
                                val to = sdf.format(Date(ev.toMs))
                                val typeLabel = when (ev.type.name) {
                                    "EXAM" -> "[考试]"
                                    "CLASS" -> "[课程]"
                                    "OTHER" -> "[其他]"
                                    else -> "[${ev.type.name}]"
                                }
                                append("\n$typeLabel ${ev.name}")
                                append("\n  时间: $from ~ $to")
                                if (ev.place.isNotBlank()) append("\n  地点: ${ev.place}")
                                if (ev.teacher.isNotBlank()) append("\n  教师: ${ev.teacher}")
                            }
                        }
                    }
                }
            }
        }
    }
}
