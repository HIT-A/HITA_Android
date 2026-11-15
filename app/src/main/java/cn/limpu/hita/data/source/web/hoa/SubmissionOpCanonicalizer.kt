package cn.limpu.hita.data.source.web.hoa

/**
 * 把一条投稿操作序列化成稳定文本，用于派生幂等键。
 *
 * 约束：同一份语义内容必须得到同一字符串，因此键按字典序输出、集合元素排序、
 * 数值统一小写无科学计数。不做任何业务校验——校验属于投稿流程。
 */
object SubmissionOpCanonicalizer {

    fun canonicalize(op: Map<String, Any?>): String = buildString {
        append('{')
        op.entries
            .sortedBy { it.key }
            .forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append('"').append(escape(entry.key)).append("\":")
                append(value(entry.value))
            }
        append('}')
    }

    /**
     * [org.json.JSONArray] 版本：逐项按稳定字段序重排后排序拼接。
     *
     * 直接 `toString()` 会保留调用方的键序，同一组操作若键序不同会派生出不同的幂等键。
     */
    fun canonicalOpsFromJsonArray(ops: org.json.JSONArray): String {
        val canonical = ArrayList<String>(ops.length())
        for (index in 0 until ops.length()) {
            val element = ops.opt(index)
            canonical += when (element) {
                is org.json.JSONObject -> canonicalize(
                    element.keys().asSequence().associateWith { element.opt(it) }
                )
                else -> element?.toString().orEmpty()
            }
        }
        return canonical.map { it.trim() }.sorted().joinToString(separator = "\u0001")
    }

    private fun value(raw: Any?): String = when (raw) {
        null -> "null"
        is String -> "\"" + escape(raw) + "\""
        is Boolean -> raw.toString()
        is Number -> number(raw)
        is Map<*, *> -> canonicalize(raw.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> raw.joinToString(separator = ",", prefix = "[", postfix = "]") { value(it) }
        is Array<*> -> raw.joinToString(separator = ",", prefix = "[", postfix = "]") { value(it) }
        else -> "\"" + escape(raw.toString()) + "\""
    }

    private fun number(raw: Number): String {
        val asDouble = raw.toDouble()
        // 整数值统一成整数形式，避免 1 与 1.0 派生不同键。
        return if (asDouble == asDouble.toLong().toDouble()) asDouble.toLong().toString()
        else asDouble.toString()
    }

    private fun escape(text: String): String = buildString(text.length + 8) {
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
    }
}
