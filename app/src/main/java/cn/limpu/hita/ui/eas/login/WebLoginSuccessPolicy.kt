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
        if (path.contains("login") || path.contains("authserver") || path.contains("authentication/require")) {
            return false
        }
        val isKnownAcademicPage = path.isBlank() || path == "/" ||
            path.contains("authentication/main") ||
            path.contains("student_index") ||
            path.contains("user/me") ||
            path.contains("xszykb") ||
            path.contains("xsxk")
        return isKnownAcademicPage && EASToken.hasShenzhenWebSessionCookies(cookies)
    }
}
