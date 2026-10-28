package cn.limpu.hita.agent.tools

import android.app.Application
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EasQuerySession
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.model.classroom.ClassroomItem
import com.limpu.component.data.DataState
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SearchEmptyClassroomTool internal constructor(
    private val weekSource: suspend (Application, TermItem) -> Int?,
    private val queryTimeoutMs: Long = QUERY_TIMEOUT_MS,
) : SuspendingReActTool {
    constructor() : this({ app, term ->
        withContext(Dispatchers.IO) {
            TimetableRepository(app).getCurrentWeekOfTimetableOnce(term)
        }
    })

    override suspend fun execute(input: ReActToolInput): String {
        val session = input.easRepository.captureQuerySession()
        return session.whileCurrent { execute(input, session) }
    }

    private suspend fun execute(input: ReActToolInput, session: EasQuerySession): String {
        val params = parseParams(input.actionInput)
        val termState = queryState { input.easRepository.queryTermsRemoteOnce(session) }
        if (termState.state != DataState.STATE.SUCCESS) {
            return stateError("获取当前学期", termState)
        }

        val terms = termState.data.orEmpty()
        if (terms.isEmpty()) return "教务系统未返回可查询的学期。"

        val currentYear = session.calendarYear.toString()
        val currentYearTerms = terms.filter { term ->
            term.yearCode.contains(currentYear) ||
                term.yearName.contains(currentYear) ||
                term.name.contains(currentYear)
        }
        val availableTerms = currentYearTerms.ifEmpty { terms }
        val term = availableTerms.firstOrNull { it.isCurrent } ?: availableTerms.first()

        val targetWeek = params.week ?: awaitWeek(input.application, term)
            ?: return "无法确定当前教学周，请检查该学期课表后重试。"

        session.ensureCurrent()
        val buildingsState = queryState { input.easRepository.createTeachingBuildingsRequest(session).execute() }
        if (buildingsState.state != DataState.STATE.SUCCESS) {
            return stateError("获取教学楼列表", buildingsState)
        }

        val allBuildings = buildingsState.data.orEmpty()
        val buildings = if (params.building.isNullOrBlank()) {
            allBuildings
        } else {
            val query = params.building.trim()
            allBuildings.filter { building ->
                building.name.orEmpty().contains(query, ignoreCase = true) ||
                    building.id.contains(query, ignoreCase = true)
            }
        }
        if (buildings.isEmpty()) {
            val available = allBuildings.mapNotNull { it.name?.takeIf(String::isNotBlank) }.distinct()
            return if (params.building.isNullOrBlank()) {
                "教务系统未返回可查询的教学楼。"
            } else {
                "未找到教学楼“${params.building}”。当前教学楼：${available.joinToString("、")}"
            }
        }

        val queryResults = mutableListOf<Pair<String, List<ClassroomItem>>>()
        val failures = mutableListOf<Pair<String, String>>()
        for (building in buildings) {
            val buildingName = building.name?.takeIf(String::isNotBlank) ?: building.id
            session.ensureCurrent()
            val state = queryState {
                input.easRepository.createEmptyClassroomQueryRequest(
                    session = session,
                    term = term,
                    buildingItem = building,
                    week = targetWeek,
                    useCache = false,
                ).states().filter {
                    it.state != DataState.STATE.NOTHING && it.state != DataState.STATE.LOADING
                }.last()
            }
            if (state.state == DataState.STATE.SUCCESS) {
                queryResults += buildingName to state.data.orEmpty()
            } else {
                failures += buildingName to (state.message?.takeIf(String::isNotBlank) ?: state.state.name)
            }
        }

        if (queryResults.isEmpty()) {
            val details = failures.joinToString("；") { (building, message) -> "$building：$message" }
            return "空教室实时查询失败${if (details.isNotBlank()) "：$details" else "。"}"
        }

        val records = queryResults.flatMap { (buildingName, classrooms) ->
            classrooms.map { buildingName to it }
        }
        val filtered = records.filter { (_, classroom) ->
            val occupied = parseSchedule(classroom)
            val dayOfWeek = params.dayOfWeek
            val period = params.period
            when {
                dayOfWeek != null && period != null ->
                    !occupied.any { it.dayOfWeek == dayOfWeek && it.period == period }
                dayOfWeek != null ->
                    occupied.filter { it.dayOfWeek == dayOfWeek }.map { it.period }.toSet().size < 12
                period != null ->
                    occupied.filter { it.period == period }.map { it.dayOfWeek }.toSet().size < 7
                else -> true
            }
        }

        if (filtered.isEmpty()) {
            val failureNote = partialFailureNote(failures)
            val queryLabel = buildString {
                append("第 ${targetWeek} 周")
                params.building?.takeIf(String::isNotBlank)?.let { append(" $it") }
                if (params.dayOfWeek != null) append(" 星期${params.dayOfWeek}")
                if (params.period != null) append(" 第 ${params.period} 节")
            }
            return if (failures.isEmpty()) {
                "$queryLabel 暂无符合条件的空闲教室。"
            } else {
                "$queryLabel 已成功查询的教学楼中没有符合条件的空闲教室。$failureNote"
            }
        }

        val grouped = filtered.groupBy({ it.first }, { it.second })
        return buildString {
            append("第 ${targetWeek} 周 空闲教室查询结果")
            params.building?.takeIf(String::isNotBlank)?.let { append("（$it）") }
            appendLine("：")
            if (params.dayOfWeek != null) appendLine("星期${params.dayOfWeek}")
            if (params.period != null) appendLine("第 ${params.period} 节")
            appendLine()
            grouped.toSortedMap().forEach { (buildingName, classrooms) ->
                appendLine("【$buildingName】")
                classrooms.sortedBy { it.name }.forEach { classroom ->
                    when {
                        params.dayOfWeek != null && params.period != null -> {
                            val capacity = if (classroom.capacity > 0) " 容量:${classroom.capacity}" else ""
                            val special = classroom.specialClassroom
                                ?.takeIf(String::isNotBlank)
                                ?.let { " [$it]" }
                                .orEmpty()
                            appendLine("  ${classroom.name}$capacity$special")
                        }
                        params.dayOfWeek != null -> {
                            val occupied = parseSchedule(classroom)
                                .filter { it.dayOfWeek == params.dayOfWeek }
                                .map { it.period }
                                .toSortedSet()
                            val free = (1..12).filter { it !in occupied }
                            val freeText = if (free.isEmpty()) "无空闲" else "空闲: ${free.joinToString(", ")}节"
                            appendLine("  ${classroom.name} ($freeText)")
                        }
                        params.period != null -> {
                            val occupied = parseSchedule(classroom)
                                .filter { it.period == params.period }
                                .map { it.dayOfWeek }
                                .toSortedSet()
                            val free = (1..7).filter { it !in occupied }
                            val freeText = if (free.isEmpty()) "无空闲" else "空闲: 周${free.joinToString(", ")}"
                            appendLine("  ${classroom.name} ($freeText)")
                        }
                        else -> {
                            val occupiedCount = parseSchedule(classroom).size
                            val capacity = if (classroom.capacity > 0) " 容量:${classroom.capacity}" else ""
                            val special = classroom.specialClassroom
                                ?.takeIf(String::isNotBlank)
                                ?.let { " [$it]" }
                                .orEmpty()
                            appendLine("  ${classroom.name} (占用${occupiedCount}/84节)$capacity$special")
                        }
                    }
                }
                appendLine()
            }
            appendLine("共 ${filtered.size} 间教室")
            if (failures.isNotEmpty()) append(partialFailureNote(failures))
        }.trim()
    }

    private fun stateError(label: String, state: DataState<*>): String {
        val detail = state.message?.takeIf(String::isNotBlank)
        return when (state.state) {
            DataState.STATE.NOT_LOGGED_IN -> "${label}失败：请先登录教务系统。"
            DataState.STATE.TOKEN_INVALID -> "${label}失败：教务登录已过期，请重新登录。"
            else -> "${label}失败：${detail ?: state.state.name}"
        }
    }

    private fun partialFailureNote(failures: List<Pair<String, String>>): String =
        "部分教学楼实时查询失败：" + failures.joinToString("；") { (building, message) -> "$building：$message" }

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

    private fun parseSchedule(classroom: ClassroomItem): List<ScheduleEntry> =
        classroom.scheduleList.mapNotNull { entry: JSONObject ->
            val dayOfWeek = entry.optInt("XQJ", 0)
            val period = entry.optInt("XJ", 0)
            val occupied = entry.optString("PKBJ").isNotBlank() || entry.optString("JYBJ").isNotBlank()
            if (dayOfWeek in 1..7 && period in 1..12 && occupied) {
                ScheduleEntry(dayOfWeek, period)
            } else {
                null
            }
        }

    private suspend fun <T> queryState(query: suspend () -> DataState<T>): DataState<T> =
        withTimeoutOrNull(queryTimeoutMs) { query() }
            ?: DataState(DataState.STATE.FETCH_FAILED, "等待教务系统响应超时")

    // Pages keep their Room observation; this finite consumer performs one
    // bounded DAO read so a tool never owns an observeForever callback.
    private suspend fun awaitWeek(application: Application, term: TermItem): Int? =
        withTimeoutOrNull(queryTimeoutMs) { weekSource(application, term) }

    private companion object {
        const val QUERY_TIMEOUT_MS = 30_000L
    }
}
