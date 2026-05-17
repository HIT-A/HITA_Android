package com.limpu.hitax.agent.tools

import com.limpu.hitax.data.AppDatabase
import com.limpu.hitax.data.source.dao.ClassroomCacheDao
import com.limpu.hitax.utils.LogUtils
import org.json.JSONArray

class SearchEmptyClassroomTool : ReActTool {

    override fun execute(input: ReActToolInput): String {
        val dao = AppDatabase.getDatabase(input.application).classroomCacheDao()
        val params = parseParams(input.actionInput)

        val allCaches = dao.getAllSync()
        if (allCaches.isEmpty()) {
            return "暂无空教室缓存数据。请先完成教务登录，系统会自动在后台缓存教室数据。"
        }

        val weeks = allCaches.map { it.week }.distinct().sorted()
        val targetWeek = params.week ?: weeks.minOrNull() ?: 1

        val records = allCaches.filter { it.week == targetWeek }
        if (records.isEmpty()) {
            return "未找到第 ${targetWeek} 周的空教室缓存数据。已缓存的周次：${weeks.joinToString(", ")}"
        }

        var filtered = records
        if (!params.building.isNullOrBlank()) {
            val query = params.building.trim()
            filtered = filtered.filter {
                it.buildingName.contains(query, ignoreCase = true) ||
                it.buildingId.contains(query, ignoreCase = true)
            }
        }

        if (filtered.isEmpty()) {
            val buildings = records.map { it.buildingName }.distinct()
            return "未找到符合条件的教室。第 ${targetWeek} 周已有缓存的教学楼：${buildings.joinToString(", ")}"
        }

        val dayOfWeek = params.dayOfWeek
        val period = params.period

        val results = if (dayOfWeek != null || period != null) {
            filtered.filter { entity ->
                val occupied = parseSchedule(entity.scheduleJson)
                if (dayOfWeek != null && period != null) {
                    !occupied.any { it.dayOfWeek == dayOfWeek && it.period == period }
                } else if (dayOfWeek != null) {
                    val dayPeriods = occupied.filter { it.dayOfWeek == dayOfWeek }.map { it.period }.toSet()
                    dayPeriods.size < 12
                } else {
                    val periodDays = occupied.filter { it.period == period }.map { it.dayOfWeek }.toSet()
                    periodDays.size < 7
                }
            }
        } else {
            filtered
        }

        if (results.isEmpty()) {
            return "第 ${targetWeek} 周${params.building?.let { " $it" } ?: ""} 暂无符合条件的空闲教室。"
        }

        val grouped = results.groupBy { it.buildingName }
        return buildString {
            appendLine("第 ${targetWeek} 周 空闲教室查询结果${params.building?.let { "（$it）" } ?: ""}：")
            if (dayOfWeek != null) appendLine("星期${dayOfWeek}")
            if (period != null) appendLine("第 ${period} 节")
            appendLine()
            grouped.toSortedMap().forEach { (buildingName, classrooms) ->
                appendLine("【$buildingName】")
                val sorted = classrooms.sortedBy { it.name }
                if (dayOfWeek != null && period != null) {
                    sorted.forEach {
                        val cap = if (it.capacity > 0) " 容量:${it.capacity}" else ""
                        val special = it.specialClassroom?.takeIf { s -> s.isNotBlank() }?.let { s -> " [$s]" } ?: ""
                        appendLine("  ${it.name}$cap$special")
                    }
                } else if (dayOfWeek != null) {
                    sorted.forEach { room ->
                        val occupied = parseSchedule(room.scheduleJson).filter { it.dayOfWeek == dayOfWeek }.map { it.period }.toSortedSet()
                        val free = (1..12).filter { it !in occupied }
                        val freeStr = if (free.isEmpty()) "无空闲" else "空闲: ${free.joinToString(", ")}节"
                        appendLine("  ${room.name} ($freeStr)")
                    }
                } else if (period != null) {
                    sorted.forEach { room ->
                        val occupied = parseSchedule(room.scheduleJson).filter { it.period == period }.map { it.dayOfWeek }.toSortedSet()
                        val free = (1..7).filter { it !in occupied }
                        val freeStr = if (free.isEmpty()) "无空闲" else "空闲: 周${free.joinToString(", ")}"
                        appendLine("  ${room.name} ($freeStr)")
                    }
                } else {
                    sorted.forEach {
                        val occupiedCount = parseSchedule(it.scheduleJson).size
                        val cap = if (it.capacity > 0) " 容量:${it.capacity}" else ""
                        val special = it.specialClassroom?.takeIf { s -> s.isNotBlank() }?.let { s -> " [$s]" } ?: ""
                        appendLine("  ${it.name} (占用${occupiedCount}/84节)$cap$special")
                    }
                }
                appendLine()
            }
            appendLine("共 ${results.size} 间教室")
        }.trim()
    }

    private data class SearchParams(
        val building: String?,
        val week: Int?,
        val dayOfWeek: Int?,
        val period: Int?,
    )

    private data class ScheduleEntry(val dayOfWeek: Int, val period: Int)

    private fun parseParams(actionInput: String): SearchParams {
        val cleaned = actionInput
            .replace(Regex("""```(?:json)?\s*"""), "")
            .replace("```", "")
            .replace("`", "")
            .trim()
        return try {
            val json = org.json.JSONObject(cleaned)
            SearchParams(
                building = json.optString("building", "").takeIf { it.isNotBlank() },
                week = json.optInt("week", -1).takeIf { it > 0 },
                dayOfWeek = json.optInt("day_of_week", -1).takeIf { it in 1..7 },
                period = json.optInt("period", -1).takeIf { it in 1..12 }
                        ?: json.optInt("jie", -1).takeIf { it in 1..12 }
                        ?: json.optInt("节次", -1).takeIf { it in 1..12 },
            )
        } catch (_: Exception) {
            SearchParams(null, null, null, null)
        }
    }

    private fun parseSchedule(scheduleJson: String): List<ScheduleEntry> {
        return try {
            val arr = JSONArray(scheduleJson)
            val list = mutableListOf<ScheduleEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val dow = obj.optInt("XQJ", 0)
                val period = obj.optInt("XJ", 0)
                val occupied = obj.optString("PKBJ").isNotBlank() || obj.optString("JYBJ").isNotBlank()
                if (dow in 1..7 && period in 1..12 && occupied) {
                    list.add(ScheduleEntry(dow, period))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
