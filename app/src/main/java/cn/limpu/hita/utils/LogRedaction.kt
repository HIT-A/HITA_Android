package cn.limpu.hita.utils

import java.net.URI

/**
 * 集中式日志脱敏规则（纯 Kotlin，可 JVM 单测）。
 *
 * 背景：登录链路里 URL query 可能带 ticket/service、cookie 值就是会话凭据、
 * token 前缀足以冒用、学号/姓名属于个人敏感信息。过去各 Activity/WebSource 各写一套
 * `safeUrl` 或直接打印原值，规则不一致；这里提供唯一实现。
 *
 * 原则：**只保留排障必需的结构信息**（scheme/host/port/path、cookie 名、token 长度与
 * 末 4 位），永不输出可复用的凭据原值。
 */
object LogRedaction {

    /**
     * URL 脱敏：保留 scheme://host[:port]/path，丢弃 userinfo、query、fragment。
     * 解析失败时退回长度占位，避免把畸形字符串原样打出来。
     */
    fun url(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return "<unparsable url len=${raw.length}>"
        return buildString {
            append(uri.scheme.orEmpty())
            if (!uri.scheme.isNullOrEmpty()) append("://")
            append(uri.host.orEmpty())
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.path.orEmpty())
        }
    }

    /** cookie 头脱敏：只保留 cookie 名与值长度。 */
    fun cookieHeader(raw: String?): String {
        if (raw.isNullOrBlank()) return "<empty>"
        return raw.split(';')
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val name = trimmed.substringBefore('=')
                if (name.isEmpty()) return@mapNotNull null
                "$name=<${trimmed.length - name.length - 1} chars>"
            }
            .joinToString("; ")
    }

    /** 单个 cookie / token 值：只保留末 4 位，其余用长度替代。 */
    fun secret(value: String?): String {
        if (value.isNullOrEmpty()) return "<empty>"
        if (value.length <= 4) return "<${value.length} chars>"
        return "<${value.length} chars, …${value.takeLast(4)}>"
    }

    /** 账号（学号/工号）：保留前 2 位与后 2 位。 */
    fun account(value: String?): String {
        if (value.isNullOrBlank()) return "<empty>"
        val trimmed = value.trim()
        if (trimmed.length <= 4) return "<${trimmed.length} chars>"
        return "${trimmed.take(2)}***${trimmed.takeLast(2)}"
    }

    /** 姓名等短个人信息：只保留长度。 */
    fun personalName(value: String?): String {
        if (value.isNullOrBlank()) return "<empty>"
        return "<${value.trim().length} chars>"
    }

    /** cookie 名列表：名字本身不是秘密，但统一按固定格式输出便于检索。 */
    fun cookieNames(names: Collection<String>?): String {
        if (names.isNullOrEmpty()) return "[]"
        return names.sorted().joinToString(", ", "[", "]")
    }
}
