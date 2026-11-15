package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.LabSessionItem
import org.json.JSONArray

/**
 * `myScheduleInfo` 课表页（HTML）的解析器。
 *
 * 页面把「当前学期」和「当前教学周」渲染成两个 `<select>`，选中项带 `selected`。
 * 实测当前学期排在学期下拉框的**最后一个**，因此 `selected` 缺失时回退到最后一个选项——
 * 这一段是 iOS `CampusLabCenterClient.selectedTerm` 的等价实现。
 */
internal object LabCenterHtmlParser {

    data class SelectedTerm(val id: String, val name: String?)

    /** 学期下拉框的选中项；页面不是课表页时返回 null。 */
    fun selectedTerm(html: String): SelectedTerm? {
        val select = selectBlock(html, "termId") ?: return null
        val options = options(select)
        if (options.isEmpty()) return null
        val chosen = options.lastOrNull { it.attributes.contains("selected") } ?: options.last()
        val name = visibleText(chosen.label)
        return SelectedTerm(chosen.value, name.ifEmpty { null })
    }

    /** 教学周下拉框的选中项；没有选中或页面缺失时返回 null。 */
    fun selectedWeek(html: String): Int? =
        selectedOptionValue(html, "week")?.toIntOrNull()

    /** 下拉框里所有选项的值，按页面顺序。 */
    fun optionValues(html: String, selectId: String): List<String> {
        val select = selectBlock(html, selectId) ?: return emptyList()
        return options(select).map { it.value }
    }

    /** 下拉框所有选项的文案，按页面顺序（已去掉标签与常见实体）。 */
    fun optionLabels(html: String, selectId: String): List<String> {
        val select = selectBlock(html, selectId) ?: return emptyList()
        return options(select).map { visibleText(it.label) }
    }

    /** 页面是否是课表页：只有课表页才同时含 `termId` 下拉框与课表 Ajax 调用。 */
    fun looksLikeSchedulePage(html: String): Boolean =
        html.contains("myScheduleInfoAjax") || html.contains("id=\"termId\"") ||
            html.contains("id='termId'")

    /** 登录页 / 会话失效页。 */
    fun looksLikeLoginPage(html: String): Boolean =
        html.contains("hitszlims/webapp/login") ||
            html.contains("/webapp/login") ||
            html.contains("name=\"j_username\"")

    private fun selectedOptionValue(html: String, selectId: String): String? {
        val select = selectBlock(html, selectId) ?: return null
        val values = options(select)
        val chosen = values.lastOrNull { it.attributes.contains("selected") } ?: return null
        return chosen.value
    }

    private data class Option(val value: String, val attributes: String, val label: String)

    private fun selectBlock(html: String, id: String): String? {
        val pattern = Regex(
            "<select[^>]*id\\s*=\\s*[\"']" + Regex.escape(id) + "[\"'][^>]*>([\\s\\S]*?)</select>",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(html)?.groupValues?.get(1)
    }

    private fun options(selectBody: String): List<Option> {
        val pattern = Regex(
            "<option\\s+value\\s*=\\s*[\"']([^\"']*)[\"']([^>]*)>([\\s\\S]*?)</option>",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(selectBody).map { match ->
            Option(
                value = match.groupValues[1].trim(),
                attributes = match.groupValues[2],
                label = match.groupValues[3],
            )
        }.toList()
    }

    /**
     * 去掉标签与实体，得到人类可读的选项文案。
     *
     * 只看已知的四类实体：课表页里的学期名不会出现别的转义，硬解析全套 HTML 实体
     * 需要引入一个只在测试里用一次的库，不值得。
     */
    fun visibleText(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
}

/**
 * `myScheduleInfoAjax` 课表数组的解析器。
 *
 * 每行：
 * `[0]教学班编号 [1]课程名 [2]开课单位 [3]- [4]星期 [5]起始节 [6]结束节 [7]周次 [8]末周
 *  [9]教师 [10]- [11]实验室 [12]班级 [13]人数 [14]学号列表`
 *
 * 剔除规则与 iOS `CampusLabCenterClient.parseSessions` 一致：
 * - `[0]` 以 `-paikao` 结尾的是排考记录，不是实验课；
 * - `[1]` 以 `[理]` 开头的是理论课，不是实验课；
 * - 同一 `教学班|周次|星期|起始节` 只保留一次。
 *
 * 学号列表（第 14 位）**永不读取**：解析结果里没有它的位置，避免敏感数据外流。
 */
internal object LabCenterScheduleParser {

    private const val MAX_WEEK = 33

    /**
     * 把 `myScheduleInfoAjax` 的响应文本解析成行。
     *
     * 平台其它接口有多种 JSON 外壳，但课表接口只返回**裸二维数组**；不是数组的响应
     * 说明已被登录页/网关替换，交给调用方按会话失效处理。
     *
     * @throws IllegalArgumentException 响应不是合法 JSON 数组。
     */
    fun parseRows(body: String): List<List<Any?>> {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) throw IllegalArgumentException("课表接口没有返回数组")
        val array = JSONArray(trimmed)
        val rows = ArrayList<List<Any?>>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONArray(index) ?: continue
            rows.add(List(row.length()) { i -> row.opt(i) })
        }
        return rows
    }

    fun parseSessions(rows: List<List<Any?>>): List<LabSessionItem> {
        val seen = HashSet<String>()
        val sessions = ArrayList<LabSessionItem>(rows.size)
        for (row in rows) {
            if (row.size <= 7) continue
            val code = stringValue(row.getOrNull(0)) ?: continue
            val courseName = stringValue(row.getOrNull(1)) ?: continue
            val weekday = intValue(row.getOrNull(4)) ?: continue
            val startSection = intValue(row.getOrNull(5)) ?: continue
            val endSection = intValue(row.getOrNull(6)) ?: continue
            val weeksText = stringValue(row.getOrNull(7)) ?: continue
            if (weekday !in 1..7) continue
            if (code.lowercase().endsWith("-paikao")) continue
            if (courseName.startsWith("[理]")) continue
            val room = row.getOrNull(11)?.let(::stringValue)
            for (week in parseWeeks(weeksText)) {
                val id = "$code|$week|$weekday|$startSection"
                if (!seen.add(id)) continue
                sessions.add(
                    LabSessionItem(
                        id = id,
                        courseName = courseName,
                        week = week,
                        weekday = weekday,
                        startSection = startSection,
                        endSection = maxOf(startSection, endSection),
                        room = room,
                    )
                )
            }
        }
        return sessions.sortedWith(
            compareBy<LabSessionItem>({ it.week }, { it.weekday }, { it.startSection })
        )
    }

    /**
     * 周次写法：`"11"`、`"3,6,8,10,14"`、`"10-17"`、混合 `"1-4,6"`。
     * 全角逗号、全角连字符与「周」字都会被规范化；超出 1..33 的周次丢弃。
     */
    fun parseWeeks(text: String): List<Int> {
        val normalized = text
            .replace('，', ',')
            .replace('－', '-')
            .replace("周", "")
        val weeks = sortedSetOf<Int>()
        for (part in normalized.split(',')) {
            val bounds = part.split('-')
                .mapNotNull { it.trim().toIntOrNull() }
            if (bounds.size == 2 && bounds[0] <= bounds[1] && bounds[1] - bounds[0] < 40) {
                for (week in bounds[0]..bounds[1]) weeks.add(week)
            } else if (bounds.size == 1) {
                weeks.add(bounds[0])
            }
        }
        return weeks.filter { it in 1..MAX_WEEK }
    }

    private fun stringValue(value: Any?): String? = when (value) {
        null, JSON_NULL -> null
        is String -> value.trim().ifEmpty { null }
        is Number -> value.toString()
        else -> null
    }

    private fun intValue(value: Any?): Int? = when (value) {
        null, JSON_NULL -> null
        is Int -> value
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    /** `org.json` 用 `JSONObject.NULL` 表示 null，与 JVM null 不等价。 */
    private val JSON_NULL: Any = org.json.JSONObject.NULL
}
