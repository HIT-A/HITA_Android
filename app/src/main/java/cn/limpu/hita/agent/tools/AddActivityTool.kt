package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.timetable.ArrangementInput
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.utils.LogUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddActivityTool : SuspendingReActTool {
    override suspend fun execute(input: ReActToolInput): String? {
        val activity = parseAddActivityInput(input.actionInput)
        val name = activity.name
        val fromMs = activity.fromMs
        val toMs = activity.toMs
        val place = activity.place

        val now = System.currentTimeMillis()
        if (fromMs != null && toMs != null) {
            val oneYear = 365L * 24 * 60 * 60 * 1000
            if (fromMs < now - oneYear || fromMs > now + oneYear ||
                toMs < now - oneYear || toMs > now + oneYear) {
                LogUtils.w("[DEBUG] Timestamps out of reasonable range, rejecting: fromMs=$fromMs toMs=$toMs")
                return "时间戳不合理，请重新描述时间（如'明天下午3点'）"
            }
        }

        if (name != null && fromMs != null && toMs != null) {
            val timetableInput = TimetableAgentInput(
                application = input.application,
                action = TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT,
                timetableId = input.timetableId,
                arrangement = ArrangementInput(name = name, fromMs = fromMs, toMs = toMs, place = place),
            )
            return ToolHelper.formatTimetableResult(ToolHelper.runTimetableTool(timetableInput, input.agentProvider, input.onTrace))
        }
        return "未找到活动信息"
    }

    internal data class ParsedActivity(val name: String?, val fromMs: Long?, val toMs: Long?, val place: String)

    internal fun parseAddActivityInput(actionInput: String): ParsedActivity {

        val cleanedInput = actionInput
            .replace(Regex("""```(?:json)?\s*"""), "")
            .replace("```", "")
            .replace("`", "")
            .trim()

        try {
            val json = org.json.JSONObject(cleanedInput)
            val jsonName = json.optString("name", "").takeIf { it.isNotBlank() }
            val jsonPlace = json.optString("place", "")

            val dayOffset = json.optInt("day_offset", -999)
            val startTime = json.optString("start_time", "")
            val endTime = json.optString("end_time", "")

            if (jsonName != null && dayOffset != -999 && startTime.isNotBlank() && endTime.isNotBlank()) {
                val fromMs = parseRelativeTime(dayOffset, startTime)
                val toMs = parseRelativeTime(dayOffset, endTime)
                if (fromMs != null && toMs != null) {
                    return ParsedActivity(jsonName, fromMs, toMs, jsonPlace)
                }
            }

            val fromVal = json.opt("from")
            val toVal = json.opt("to")
            var jsonFrom: Long? = null
            var jsonTo: Long? = null

            when (fromVal) {
                is Number -> jsonFrom = fromVal.toLong()
                is String -> {
                    val ts = parseTimestampOrIso(fromVal as String)
                    if (ts != null) jsonFrom = ts
                }
            }

            when (toVal) {
                is Number -> jsonTo = toVal.toLong()
                is String -> {
                    val ts = parseTimestampOrIso(toVal as String)
                    if (ts != null) jsonTo = ts
                }
            }


            if (jsonName != null && jsonFrom != null && jsonTo != null) {
                return ParsedActivity(jsonName, jsonFrom, jsonTo, jsonPlace)
            }
        } catch (_: Exception) {
        }


        return ParsedActivity(null, null, null, "")
    }

    private fun parseTimestampOrIso(raw: String): Long? {
        val trimmed = raw.trim().removeSurrounding("\"")
        trimmed.toLongOrNull()?.let { return it }
        val isoFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.CHINA),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA),
        )
        for (fmt in isoFormats) {
            try {
                return fmt.parse(trimmed)?.time
            } catch (_: Exception) { LogUtils.w("Failed to parse date format: $trimmed") }
        }
        return null
    }

    private fun parseRelativeTime(dayOffset: Int, timeStr: String): Long? {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)

        val parts = timeStr.split(":")
        if (parts.size != 2) return null

        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null

        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val result = cal.timeInMillis
        return result
    }

}
