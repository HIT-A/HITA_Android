package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.utils.LogUtils
import java.sql.Timestamp
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ExamEventMapper {
    fun toEvent(exam: ExamItem, timetableId: String, logTag: String = "ExamEventMapper"): EventItem? {
        return try {
            val date = exam.examDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val timeRange = exam.examTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val times = timeRange.split("-")
            if (times.size != 2) return null

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val parsedDate = parseStrict(dateFormat, date) ?: return null
            val parsedStart = parseStrict(timeFormat, times[0].trim()) ?: return null
            val parsedEnd = parseStrict(timeFormat, times[1].trim()) ?: return null
            if (!parsedEnd.after(parsedStart)) return null
            val startClock = Calendar.getInstance().apply { time = parsedStart }
            val endClock = Calendar.getInstance().apply { time = parsedEnd }
            val calendarStart = Calendar.getInstance().apply {
                time = parsedDate
                set(Calendar.HOUR_OF_DAY, startClock.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, startClock.get(Calendar.MINUTE))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val calendarEnd = Calendar.getInstance().apply {
                time = parsedDate
                set(Calendar.HOUR_OF_DAY, endClock.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, endClock.get(Calendar.MINUTE))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            EventItem().apply {
                type = EventItem.TYPE.EXAM
                source = EventItem.SOURCE_EAS_IMPORT
                name = formatExamName(exam.courseName)
                place = exam.examLocation.orEmpty()
                teacher = ""
                subjectId = ""
                this.timetableId = timetableId
                from = Timestamp(calendarStart.timeInMillis)
                to = Timestamp(calendarEnd.timeInMillis)
                fromNumber = 0
                lastNumber = 0
            }
        } catch (e: Exception) {
            LogUtils.e("parse exam failed: ${exam.courseName}", e, logTag)
            null
        }
    }

    private fun parseStrict(format: SimpleDateFormat, value: String): java.util.Date? {
        format.isLenient = false
        val position = ParsePosition(0)
        val parsed = format.parse(value, position)
        return parsed?.takeIf { position.index == value.length }
    }

    fun identityKey(event: EventItem): String {
        return listOf(
            normalizeExamName(event.name),
            normalizeText(event.place),
            event.from.time.toString(),
            event.to.time.toString()
        ).joinToString("|")
    }

    private fun formatExamName(courseName: String?): String {
        val normalized = normalizeExamName(courseName.orEmpty()).ifBlank { "考试" }
        return "[考试] $normalized"
    }

    private fun normalizeExamName(name: String): String {
        return normalizeText(name)
            .removePrefix("[考试]")
            .trim()
    }

    private fun normalizeText(value: String?): String {
        return value.orEmpty().replace(Regex("\\s+"), " ").trim()
    }
}
