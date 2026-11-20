package cn.limpu.hita.data.source.web.campus

import java.net.URI

/**
 * 深圳信息门户校园卡的登录态判定。
 *
 * 事实依据（iOS `CampusServiceSessionTargets.swift` 的
 * `unifiedAuthenticationDirectTarget` 与 `CampusCardPortalClient.portalTicket`）：
 * - 登录入口是统一身份认证 `https://ids.hit.edu.cn/authserver/login?service=...login_cas`；
 * - CAS 回跳 `dxcdp.hitsz.edu.cn/portal-api/login_cas` 后，门户前端（`/portal/`）落地；
 * - 只要浏览器停在门户前端页面，就说明 CAS 已放行；停在 `ids.hit.edu.cn`
 *   或 `login_cas` 回跳点上，说明还没登录。
 *
 * 本对象只做纯字符串判定，不碰 WebView / CookieManager，便于 JVM 单测。
 */
internal object CampusCardSessionPolicy {

    const val PORTAL_ORIGIN = "https://dxcdp.hitsz.edu.cn"
    const val PORTAL_WEB_URL = "$PORTAL_ORIGIN/portal/"
    const val CAS_ORIGIN = "https://ids.hit.edu.cn"

    /** 统一身份认证入口，`service` 为门户的 CAS 回调。 */
    const val CAS_LOGIN_URL =
        "$CAS_ORIGIN/authserver/login?service=https%3A%2F%2Fdxcdp.hitsz.edu.cn%3A443%2Fportal-api%2Flogin_cas"

    private const val PORTAL_HOST = "dxcdp.hitsz.edu.cn"
    private const val CAS_HOST = "ids.hit.edu.cn"

    /** 深圳校区经 aTrust/WebVPN 放行时的统一认证镜像。 */
    private const val CAS_GATEWAY_HOST = "ids-hit-edu-cn-s.hitsz.edu.cn"

    /** 门户与统一认证中间的信任页，停留在这里等于还没登录。 */
    private const val TRUST_HOST = "trust.hitsz.edu.cn"

    private val HTML_TICKET_PATTERNS = listOf(
        Regex("""dxcdp\.hitsz\.edu\.cn[^"'\s]*[?&]t=([A-Za-z0-9\-_.%]+)"""),
        Regex("""dxcdp-hitsz-edu-cn-s\.hitsz\.edu\.cn[^"'\s]*[?&]t=([A-Za-z0-9\-_.%]+)"""),
        Regex("""/portal/[^"'\s]*[?&]t=([A-Za-z0-9\-_.%]+)"""),
    )

    enum class State {
        /** 已登录：浏览器停在门户前端页面，且该 origin 已建立 Cookie。 */
        SignedIn,

        /** 明确未登录：停在统一身份认证表单、CAS 回跳点或信任页。 */
        SignedOut,

        /** 判定不了：还在跳转中、页面属其他站点，或缺门户 Cookie。 */
        Unknown,
    }

    /**
     * @param url WebView 当前主文档地址
     * @param cookieNames `CampusCookieJar.header(PORTAL_ORIGIN)` 解析出的 Cookie 名集合
     */
    fun state(url: String, cookieNames: Set<String>): State {
        val uri = runCatching { URI(url) }.getOrNull() ?: return State.Unknown
        if (!uri.scheme.equals("https", ignoreCase = true)) return State.Unknown
        if (uri.rawUserInfo != null) return State.Unknown
        val host = uri.host.orEmpty().lowercase()
        if (host.isEmpty()) return State.Unknown
        if (host == CAS_HOST || host == CAS_GATEWAY_HOST || host == TRUST_HOST) return State.SignedOut
        if (host != PORTAL_HOST) return State.Unknown
        val path = uri.path.orEmpty().lowercase()
        // CAS 回调点：说明还没完成换票，等价于未登录。
        if (path.contains("login_cas")) return State.SignedOut
        // 接口端点不是用户页面，WebView 正常不会停在这里。
        if (path.startsWith("/portal-api/")) return State.Unknown
        // 门户前端页面就代表 CAS 已放行；但该 origin 一条 Cookie 都没有时，
        // 说明 SPA 还在用 `t` 换票，等下一次 onPageFinished 再判。
        return if (cookieNames.isEmpty()) State.Unknown else State.SignedIn
    }

    /**
     * 从 URL 的 query 或 fragment 里取 `t`（门户是 hash 路由，两者都可能带）。
     * 只认 `dxcdp.hitsz.edu.cn` 的地址。
     */
    fun portalTicket(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (!uri.host.orEmpty().equals(PORTAL_HOST, ignoreCase = true)) return null
        if (!uri.path.orEmpty().startsWith("/portal/")) return null
        return listOfNotNull(uri.rawQuery, uri.rawFragment).firstNotNullOfOrNull { raw ->
            ticketFromQuery(raw)
        }
    }

    /** 解析 Cookie 头里的名字集合；控制字符出现在头里说明已损坏，按空处理。 */
    fun cookieNames(header: String): Set<String> {
        if (header.isEmpty()) return emptySet()
        if (header.any { it.code < 0x20 || it.code > 0x7e }) return emptySet()
        return header.split(';')
            .map { it.trim() }
            .filter { it.contains('=') && it.substringAfter('=', "").isNotEmpty() }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** 统一身份认证是否还有持久票据（`CASTGC`）。它是换 `t` 的前提。 */
    fun hasTicket(casCookieHeader: String): Boolean =
        cookieNames(casCookieHeader).any { it.equals("CASTGC", ignoreCase = true) }

    /** 从响应 HTML 里找 `/portal/...?t=` 形式的登录票据。 */
    fun portalTicketInHtml(html: String): String? {
        if (html.isEmpty()) return null
        for (pattern in HTML_TICKET_PATTERNS) {
            val value = pattern.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            if (value != null) return percentDecode(value)
        }
        return null
    }

    /** URL 的 origin（scheme://host[:port]）；解析失败返回空串。 */
    fun originOf(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase() ?: return ""
        val host = uri.host?.lowercase() ?: return ""
        if (scheme.isEmpty() || host.isEmpty()) return ""
        return if (uri.port == -1 || (scheme == "https" && uri.port == 443)) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }

    private fun ticketFromQuery(raw: String): String? {
        // hash 路由里可能再嵌一层 `?`，取最后一段查询串。
        val query = raw.substringAfterLast('?')
        for (pair in query.split('&')) {
            val name = pair.substringBefore('=', "")
            if (name != "t") continue
            val value = pair.substringAfter('=', "")
            if (value.isEmpty()) continue
            return percentDecode(value)
        }
        return null
    }

    private fun percentDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
