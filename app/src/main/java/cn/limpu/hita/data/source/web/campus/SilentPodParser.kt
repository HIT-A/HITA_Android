package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.SilentPodCategory
import cn.limpu.hita.data.model.campus.SilentPodGroup
import cn.limpu.hita.data.model.campus.SilentPodReservation
import cn.limpu.hita.data.model.campus.SilentPodSpace
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 空间预约接口的响应解析。
 *
 * 全部是纯函数：输入响应原文，输出数据类或 `null`，不做网络与状态管理。
 * 服务端会把同一字段时而写成数字时而写成字符串（`"31"` / `31`），
 * 因此数值字段一律走宽松读取，避免因为类型抖动整页失败。
 */
internal object SilentPodParser {

    /** 时间轴片段的固定格式；不符合的片段直接丢弃，不塞进 UI。 */
    private val TIME_RANGE = Regex("""^\d{2}:\d{2}-\d{2}:\d{2}$""")

    /**
     * 解析空间列表 `{ errorList, warnList, total, data[] }`。
     *
     * 未登录时服务端也可能回 200 加登录跳转，因此 `data` 缺失按空列表处理；
     * 真正的鉴权判定在 [SilentPodSessionPolicy] 与 HTTP 层。
     */
    fun parseSpaces(raw: String): List<SilentPodSpace> {
        val data = listEnvelopeData(raw) ?: return emptyList()
        val spaces = ArrayList<SilentPodSpace>(data.length())
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            spaceOf(item)?.let(spaces::add)
        }
        return spaces
    }

    /** 解析单个空间对象；`spaceTypeId` 不属于三类静音仓时返回 `null`。 */
    fun spaceOf(item: JSONObject): SilentPodSpace? {
        val id = item.intOrNull("id") ?: return null
        val name = item.stringOrNull("name") ?: return null
        val spaceTypeId = item.intOrNull("spaceTypeId") ?: return null
        val category = SilentPodCategory.fromSpaceTypeId(spaceTypeId) ?: return null
        return SilentPodSpace(
            id = id,
            codeName = name,
            areaFullName = item.stringOrNull("areaFullName") ?: "",
            spaceTypeName = item.stringOrNull("spaceTypeName"),
            category = category,
            capacity = item.intOrNull("capacity") ?: if (category == SilentPodCategory.SINGLE) 1 else 4,
            // 缺字段时按分类兜底：只有 spaceTypeId 40 是带门禁的 4 人仓。
            isAccessControl = item.booleanOrNull("isAccessControl")
                ?: (category == SilentPodCategory.QUAD_LOCKED),
            allowDurations = item.timeRanges("allowUseDurationList"),
            disabledDurations = item.timeRanges("disabledDurationList"),
            minDurationMinutes = item.intOrNull("gtDurationMinutes"),
            maxDurationMinutes = item.intOrNull("ltDurationMinutes"),
        )
    }

    /** 解析我的预约 `{ data[] }`；`data` 缺失视为没有预约。 */
    fun parseReservations(raw: String): List<SilentPodReservation> {
        val data = listEnvelopeData(raw) ?: return emptyList()
        val records = ArrayList<SilentPodReservation>(data.length())
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            reservationOf(item)?.let(records::add)
        }
        return records
    }

    /**
     * 创建预约的响应：`{ "model": 123 }` 或 `{ "model": { "id": 123 } }` 都出现过。
     *
     * 拿不到 id 不算失败——预约已经落库，页面刷新后能看到；返回 null 让调用方继续刷新列表。
     */
    fun parseCreatedId(raw: String): Int? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        when (val model = root.opt("model")) {
            is Number -> return model.toInt()
            is String -> return model.trim().toIntOrNull()
            is JSONObject -> return model.intOrNull("id")
            else -> return null
        }
    }

    /** 解析单条预约记录；缺少 `id` 视为无效记录。 */
    fun reservationOf(item: JSONObject): SilentPodReservation? {
        val id = item.intOrNull("id") ?: return null
        val status = item.stringOrNull("status")?.uppercase() ?: ""
        return SilentPodReservation(
            id = id,
            spaceName = item.stringOrNull("spaceName") ?: "",
            areaName = item.stringOrNull("areaName") ?: "",
            spaceTypeName = item.stringOrNull("spaceTypeName"),
            date = item.stringOrNull("reservationDate") ?: "",
            startTime = item.stringOrNull("startTime")?.take(5) ?: "",
            endTime = item.stringOrNull("endTime")?.take(5) ?: "",
            typeText = item.stringOrNull("reservationTypeStr"),
            status = status,
            statusText = item.stringOrNull("statusStr")?.takeIf { it.isNotBlank() }
                ?: statusTextOf(status),
            cancelReason = item.stringOrNull("cancelReason"),
            isMain = item.booleanOrNull("isMain") ?: false,
            isNeedSign = item.booleanOrNull("isNeedSign") ?: false,
            isAllowCancel = item.booleanOrNull("isAllowCancel") ?: false,
        )
    }

    /** 服务端状态码的中文兜底文案；服务端给了 `statusStr` 时以服务端为准。 */
    fun statusTextOf(status: String): String = when (status.uppercase()) {
        "PENDING" -> "未开始"
        "UNDERWAY" -> "进行中"
        "ENDED" -> "已结束"
        "CANCELED" -> "已取消"
        "OFF" -> "已爽约"
        else -> "已预约"
    }

    /**
     * 按固定顺序分组：4 人仓（锁）→ 4 人仓 → 单人仓，组内按仓位编号自然序。
     *
     * 服务端返回顺序不保证稳定，页面分组必须在客户端定死，否则同一份数据两次渲染顺序不同。
     */
    fun group(spaces: List<SilentPodSpace>): List<SilentPodGroup> =
        spaces.groupBy { it.category }
            .toList()
            .sortedBy { (category, _) -> category.ordinal }
            .map { (category, items) ->
                SilentPodGroup(category = category, spaces = items.sortedWith(CODE_NAME_ORDER))
            }

    /** 仓位编号形如 `F01` / `S21`：同前缀按数字比较，否则按字符串比较。 */
    private val CODE_NAME_ORDER = Comparator<SilentPodSpace> { lhs, rhs ->
        val left = splitCode(lhs.codeName)
        val right = splitCode(rhs.codeName)
        if (left.first != right.first) {
            left.first.compareTo(right.first)
        } else {
            left.second.compareTo(right.second)
        }
    }

    private fun splitCode(codeName: String): Pair<String, Int> {
        val prefix = codeName.takeWhile { it.isLetter() }
        val number = codeName.drop(prefix.length).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        return prefix to number
    }

    /**
     * 业务错误信封 `{ success, errorCode, errorMsg, model }` 里的错误描述。
     *
     * 列表接口失败时是 `errorList` 而非 `success`，两者都认。
     */
    fun errorMessageOf(raw: String): String? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optBoolean("success", true)) return null
        val code = json.stringOrNull("errorCode") ?: json.stringOrNull("code")
        val message = json.stringOrNull("errorMsg") ?: json.stringOrNull("message")
        return listOfNotNull(code, message).joinToString("：").ifBlank { null }
    }

    /** 列表信封里的 `data` 数组；缺失或类型不对时返回 `null`。 */
    fun listEnvelopeData(raw: String): JSONArray? = try {
        JSONObject(raw).optJSONArray("data")
    } catch (_: JSONException) {
        null
    }

    /**
     * 抽取 `__INITIAL_STATE__` 里的 `loginStatus.loggedIn`。
     *
     * 空间预约落定页会把登录态内联进 HTML；只有明确 `true` 才算登录成功，
     * 解析失败一律按未登录处理，避免把半加载页面当成功。
     */
    fun initialLoggedIn(html: String): Boolean? {
        val marker = "__INITIAL_STATE__"
        val start = html.indexOf(marker)
        if (start < 0) return null
        val braceIndex = html.indexOf('{', start)
        if (braceIndex < 0) return null
        val end = matchBrace(html, braceIndex) ?: return null
        val state = runCatching { JSONObject(html.substring(braceIndex, end + 1)) }.getOrNull() ?: return null
        val loginStatus = state.optJSONObject("loginStatus") ?: return null
        return loginStatus.booleanOrNull("loggedIn")
    }

    /**
     * 判定 WebView 里 `window.__INITIAL_STATE__.loginStatus` 的序列化结果。
     *
     * 页面脚本把 loginStatus 取出来时得到的是 `null` 或 `{"loggedIn":true,...}`，
     * 与直接解析整页 HTML 等价，避免为了一个布尔值把整页 HTML 拉回来。
     */
    fun loginStatusLoggedIn(json: String?): Boolean? {
        val trimmed = json?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        val status = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return status.booleanOrNull("loggedIn")
    }

    /** 从 `{` 起找配对的 `}`；字符串里的花括号与转义字符不参与计数。 */
    private fun matchBrace(text: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openIndex until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun JSONObject.timeRanges(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.optString(index, "").trim()
            if (TIME_RANGE.matches(value)) out += value
        }
        return out
    }

    /** 数值字段可能是数字也可能是字符串数字。 */
    private fun JSONObject.intOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    /** 布尔字段可能是 `true` / `"true"` / `1`。 */
    private fun JSONObject.booleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }

            else -> null
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) as? String ?: return null
        return value.trim().ifEmpty { null }
    }
}
