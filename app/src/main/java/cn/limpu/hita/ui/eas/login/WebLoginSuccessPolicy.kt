package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken
import java.net.URI

/** Pure URL and cookie rules shared by the WebView login flow and its tests. */
internal object WebLoginSuccessPolicy {
    private const val WEIHAI_TICKET_COOKIE_PREFIX = "wengine_vpn_ticket"

    fun isWeihaiAuthenticatedPage(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val isFunctionPage = path.contains("/http/") ||
            path.contains("kbcx") ||
            path.contains("cjcx") ||
            path.contains("kjscx") ||
            path.contains("query") ||
            path.contains("index")
        val isLoginPage = path.contains("logincas") ||
            path.endsWith("/login") ||
            path.contains("/login/")
        val hasVpnTicket = cookies.keys.any { key ->
            key.startsWith(WEIHAI_TICKET_COOKIE_PREFIX, ignoreCase = true) ||
                key.contains(WEIHAI_TICKET_COOKIE_PREFIX, ignoreCase = true)
        }
        return isFunctionPage && !isLoginPage && hasVpnTicket &&
            cookies["JSESSIONID"].orEmpty().isNotBlank()
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
