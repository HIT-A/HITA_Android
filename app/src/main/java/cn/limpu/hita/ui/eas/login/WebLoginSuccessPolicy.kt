package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken
import java.net.URI

/** Pure URL and cookie rules shared by the WebView login flow and its tests. */
internal object WebLoginSuccessPolicy {

    private val benbuTeachingHosts = setOf(
        "jwts.hit.edu.cn", "jwts-hit-edu-cn.vpn.hit.edu.cn", "jwts-hit-edu-cn.ivpn.hit.edu.cn",
    )
    private val benbuIdentityHosts = setOf(
        "ids.hit.edu.cn", "ids-hit-edu-cn-s.vpn.hit.edu.cn", "ids-hit-edu-cn-s.ivpn.hit.edu.cn",
    )

    fun isBenbuMfaPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host?.lowercase() in benbuIdentityHosts &&
            uri.path.orEmpty().lowercase().startsWith("/authserver/reauthcheck/")
    }

    /** Never collect a teaching session from an identity/MFA or VPN portal page. */
    fun canCollectBenbuSession(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.userInfo == null && uri.host?.lowercase() in benbuTeachingHosts &&
            !uri.path.orEmpty().contains("authserver", ignoreCase = true)
    }

    fun isBenbuAuthenticatedPage(url: String, cookies: Map<String, String>): Boolean {
        if (!canCollectBenbuSession(url)) return false
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return !path.contains("login", ignoreCase = true) &&
            !cookies["JSESSIONID"].isNullOrBlank() && !cookies["HIT"].isNullOrBlank()
    }

    private const val WEIHAI_TICKET_COOKIE_PREFIX = "wengine_vpn_ticket"

    fun shouldOpenWeihaiTeachingEntry(url: String, teachingBaseUrl: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val teachingBase = runCatching { URI(teachingBaseUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("webvpn.hitwh.edu.cn", ignoreCase = true) ||
            uri.port != -1 || uri.userInfo != null
        ) return false

        val path = uri.path.orEmpty().trim('/').lowercase()
        val teachingPath = teachingBase.path.orEmpty().trim('/').lowercase()
        return path.isEmpty() || path == "m/portal" || path == "portal/home" || path == teachingPath
    }

    fun isWeihaiAuthenticatedPage(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val isFunctionPage = path.contains("/http/") ||
            path.contains("kbcx") ||
            path.contains("cjcx") ||
            path.contains("kjscx") ||
            path.contains("query") ||
            path.contains("index")
        // CAS 刚验完票回跳时会落在 loginCAS 上（?ticket=ST-...），此时 cookie 往往已有效；
        // 若仍按"登录页"排除，用户会永久卡在空白页。登录前的 loginCAS 从不带 ticket 参数，
        // 所以只对带票据的回跳放行。
        val hasCasTicket = uri.query.orEmpty().contains("ticket=ST-", ignoreCase = true)
        val isLoginPage = !hasCasTicket &&
            (path.contains("logincas") || path.endsWith("/login") || path.contains("/login/"))
        val hasVpnTicket = cookies.keys.any { key ->
            key.startsWith(WEIHAI_TICKET_COOKIE_PREFIX, ignoreCase = true) ||
                key.contains(WEIHAI_TICKET_COOKIE_PREFIX, ignoreCase = true)
        }
        // JSESSIONID 的 key 大小写在不同 WebVPN 节点/路径下并不一致，
        // 严格 equals("JSESSIONID") 会漏判，导致登录成功后仍不 finish。
        val hasJsession = hasJsessionIdCookie(cookies)
        return isFunctionPage && !isLoginPage && hasVpnTicket && hasJsession
    }

    /** 大小写不敏感地判断 cookies 中是否存在非空的 JSESSIONID。 */
    fun hasJsessionIdCookie(cookies: Map<String, String>): Boolean =
        cookies.any { (key, value) ->
            key.equals("JSESSIONID", ignoreCase = true) && value.isNotBlank()
        }

    fun shenzhenCookieProbeUrls(proxyBaseUrl: String, directBaseUrl: String): List<String> {
        val paths = listOf("/authentication/main", "/student_index", "/user/me", "/")
        return (listOf(proxyBaseUrl, directBaseUrl).distinctBy { it.trimEnd('/').lowercase() })
            .flatMap { base -> paths.map { path -> base.trimEnd('/') + path } }
    }

    fun shenzhenWebBaseUrl(host: String?, proxyBaseUrl: String, directBaseUrl: String): String {
        return if (host.equals(URI(directBaseUrl).host, ignoreCase = true)) {
            directBaseUrl.trimEnd('/')
        } else {
            proxyBaseUrl.trimEnd('/')
        }
    }

    fun isShenzhenAuthenticatedPage(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val allowedHosts = setOf("jw.hitsz.edu.cn", "jw-hitsz-edu-cn.hitsz.edu.cn")
        if (host !in allowedHosts) return false

        val path = uri.path.orEmpty().lowercase()
        if (path.contains("login") || path.contains("authserver") ||
            path.contains("authentication/require") || path.contains("session/invalid")
        ) {
            return false
        }
        val isKnownAcademicPage = path.isBlank() || path == "/" ||
            path.contains("authentication/main") ||
            // aTrust/CAS 验完票后 jw 会把代理地址回跳到 `/cas`，页面内容就是教务首页；
            // 不把它算作已登录，用户会停在一个不会再触发成功判定的页面上。
            path.trimEnd('/') == "/cas" ||
            path.contains("student_index") ||
            path.contains("user/me") ||
            path.contains("xszykb") ||
            path.contains("xsxk")
        return isKnownAcademicPage && EASToken.hasShenzhenWebSessionCookies(cookies)
    }

    /** 深圳 aTrust 信任门户的任意 /portal 页面。 */
    fun isShenzhenTrustPortal(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        if (!uri.host.orEmpty().equals("trust.hitsz.edu.cn", ignoreCase = true)) return false
        return uri.path.orEmpty().lowercase().startsWith("/portal")
    }

    /**
     * 门户里的认证/引导页面：`shortcut.html`（会探测本地 aTrust 客户端）、
     * `#/login`（统一身份认证表单）、客户端下载页。
     *
     * 这些页面恰恰说明**还没连上**：教务入口无会话时会被 302 到这里。把这里当成
     * "已连接"再回跳教务入口，就形成 门户→教务→verify→门户 的无限重定向，页面
     * 永远停在 `shortcut.html` 的 Loading。所以它们绝不触发自动续跳。
     */
    fun isShenzhenTrustPortalAuthPage(url: String): Boolean {
        if (!isShenzhenTrustPortal(url)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        if (path.startsWith("/portal/shortcut")) return true
        val route = uri.rawFragment.orEmpty().lowercase().removePrefix("!")
        return route.startsWith("/login") ||
            route.startsWith("/down_client") ||
            route.startsWith("/app_center/auth")
    }

    /** 门户页且已不在认证/下载引导上：aTrust 已放行，可以续跳教务。 */
    fun isShenzhenTrustPortalConnected(url: String): Boolean =
        isShenzhenTrustPortal(url) && !isShenzhenTrustPortalAuthPage(url)

    /**
     * 深圳教务代理的"会话失活"页面：`/session/invalid`、`/authentication/require`。
     * 它们出现在教务 host 上，但不是已登录页，需要转 `/cas` 重新走一次统一身份认证。
     */
    fun isShenzhenReauthenticationPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.host.orEmpty().lowercase() !in setOf(
                "jw.hitsz.edu.cn", "jw-hitsz-edu-cn.hitsz.edu.cn"
            )
        ) return false
        val path = uri.path.orEmpty().lowercase()
        return path.contains("/session/invalid") || path.endsWith("/authentication/require")
    }

    /**
     * 深圳教务登录入口：到达这里说明已离开 aTrust。
     *
     * 注意它不是"已登录"：`/session/invalid`、`/authentication/require` 也在这个 host 上，
     * 需要由 `ShenzhenWebAutoLogin.reauthenticationUrl` 转到 `/cas` 再走一次 CAS。
     */
    fun isShenzhenAcademicEntry(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host.orEmpty().lowercase() in setOf("jw.hitsz.edu.cn", "jw-hitsz-edu-cn.hitsz.edu.cn")
    }
}
