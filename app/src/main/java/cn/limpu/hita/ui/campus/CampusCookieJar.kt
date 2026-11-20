package cn.limpu.hita.ui.campus

import android.webkit.CookieManager

/**
 * 从 WebView 的 CookieManager 读取指定 origin 的会话 Cookie。
 *
 * 校园扩展的各个门户只共享「统一身份认证」这一步，业务会话各自独立，
 * 因此每个功能只取自己 origin 下的那段 Cookie，不做跨域拼接。
 */
object CampusCookieJar {

    /** @return 该 origin 的 `name=value; ...` 原文；没有 Cookie 时返回空串 */
    fun header(origin: String): String =
        runCatching { CookieManager.getInstance().getCookie(origin).orEmpty() }.getOrDefault("")

    /**
     * 按名字挑出单个 Cookie。
     *
     * 门户侧只接受白名单里的会话 Cookie；把整段 Cookie 头转发给业务接口会把
     * 与本次调用无关的凭证一起带出去，因此默认只取需要的那个。
     */
    fun session(header: String, name: String): String? {
        if (header.isEmpty()) return null
        // 控制字符出现在 Cookie 头里说明已经损坏，直接判无效而不是继续拼。
        if (header.any { it.code < 0x20 || it.code > 0x7e }) return null
        val match = header.split(';').map { it.trim() }
            .firstOrNull { it.substringBefore('=', "").trim() == name }
            ?: return null
        val value = match.substringAfter('=', "").trim()
        if (value.isEmpty()) return null
        if (value.any { it == '"' || it == ',' || it == ';' || it == '\\' || it == ' ' }) return null
        return "$name=$value"
    }

    /** 是否存在指定名字且非空的 Cookie。 */
    fun has(header: String, name: String): Boolean = session(header, name) != null
}
