package cn.limpu.hita.data.source.web.campus

import java.net.URI

/**
 * 实验中心（GvSun LIMS）的会话规则。
 *
 * 事实来源：`docs/reference/深圳校区实验中心网页接口.md` §2.1 与 §5。
 * - 入口 `https://lab.hitsz.edu.cn/hitszlims/test?PROJECT_NAME=hitszlims`；
 * - 会话失效时门户 302 到 `/hitszlims/login`，课表接口则回落到 `/hitszlims/webapp/login`；
 * - 已登录的课表页一定含学期下拉框 `id="termId"` 与 `myScheduleInfoAjax` 调用。
 *
 * 与 `GraderSessionPolicy` / `InfoPortalLoginPolicy` 同构：这里只做纯判定，
 * WebView 与网络都由上层负责。
 */
internal object LabCenterSessionPolicy {
    const val ORIGIN = "https://lab.hitsz.edu.cn"
    const val ENTRY_URL = "$ORIGIN/hitszlims/test?PROJECT_NAME=hitszlims"
    const val SCHEDULE_URL = "$ORIGIN/hitszlims/schedule/myScheduleInfo"
    const val SCHEDULE_AJAX_URL = "$ORIGIN/hitszlims/schedule/myScheduleInfoAjax"

    /** 只允许在实验中心这一个 host 上加载与判定，避免把会话带到别的站点。 */
    fun isLabUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.orEmpty().equals("lab.hitsz.edu.cn", ignoreCase = true)) return false
        return uri.port == -1 || uri.port == 443
    }

    /** 登录中转页 / 失效页：到达这里说明**还没有**可用会话。 */
    fun isLoginTransitPage(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return path.contains("/webapp/login") || path.endsWith("/hitszlims/login") ||
            path.contains("/uaa/oauth/authorize")
    }

    /**
     * 登录成功的判定：停在实验中心域内的业务页，且页面里出现课表页标志。
     *
     * 只看 URL 不够：失效时服务端会给一个 200 的登录壳页面，URL 可能仍停在
     * `myScheduleInfo`，因此必须同时确认页面内容里没有登录标志。
     */
    fun isLoginComplete(url: String, html: String): Boolean {
        if (!isLabUrl(url)) return false
        if (isLoginTransitPage(url)) return false
        return isSchedulePage(html) && !isLoginPage(html)
    }

    /** 页面是课表页：含 `id="termId"` 或 `myScheduleInfoAjax`。 */
    fun isSchedulePage(html: String): Boolean = LabCenterHtmlParser.looksLikeSchedulePage(html)

    /** 页面是登录壳 / 失效页。 */
    fun isLoginPage(html: String): Boolean = LabCenterHtmlParser.looksLikeLoginPage(html)

    /**
     * 可用的 Cookie 头。
     *
     * 实验中心的 JSP 会话由站点自己的一整组 Cookie 承载，不做白名单裁剪；
     * 这里只拒掉空值和控制字符（后者说明 Cookie 已经损坏，转发出去只会得到一个误导性的登录页）。
     */
    fun cookieHeader(header: String): String? {
        val trimmed = header.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.code < 0x20 || it.code > 0x7e }) return null
        val hasValue = trimmed.split(';').any { part ->
            val name = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            name.isNotEmpty() && value.isNotEmpty()
        }
        return if (hasValue) trimmed else null
    }
}
