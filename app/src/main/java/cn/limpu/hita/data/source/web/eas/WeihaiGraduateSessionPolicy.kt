package cn.limpu.hita.data.source.web.eas

import java.net.URI
import com.google.gson.JsonParser

/** iOS connector-backed protocol; pure entry/profile rules are usable by offline fixtures. */
internal object WeihaiGraduateSessionPolicy {
    const val BASE_URL = "https://webvpn.hitwh.edu.cn/https/77726476706e69737468656265737421e9fd529b2b7e60596a1f81e29d51367b6311"
    const val TICKET_COOKIE = "wengine_vpn_ticketwebvpn_hitwh_edu_cn"
    private val base = URI(BASE_URL)
    private val landingPaths = setOf(base.path + "/authentication/main", base.path + "/student_index")

    /** 校园网直连 `yjsgl` 与 WebVPN 反代根并列的第二个合法宿主。 */
    const val DIRECT_HOST = "yjsgl.hitwh.edu.cn"

    fun isAuthenticatedLanding(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == base.host && uri.port == -1 &&
            uri.userInfo == null && uri.path in landingPaths && !cookies[TICKET_COOKIE].isNullOrBlank()
    }

    /**
     * 是否存在非空 `JSESSIONID`（大小写不敏感）。
     *
     * 反代入口的业务会话由 WebVPN 服务端代管，浏览器只拿得到 `wengine_vpn_ticket`；
     * 校园网直连时没有这层网关，业务会话就是 `JSESSIONID` 本身。两条入口的凭据不同，
     * 判定必须分开，否则直连永远被判成「未登录」。
     */
    fun hasJsessionId(cookies: Map<String, String>): Boolean =
        cookies.any { (key, value) ->
            key.equals("JSESSIONID", ignoreCase = true) && value.isNotBlank()
        }

    data class Profile(val account: String, val name: String?)

    fun parseProfile(body: String): Profile? {
        val json = runCatching { JsonParser().parse(body).asJsonObject }.getOrNull() ?: return null
        fun field(key: String): String? {
            val value = json.get(key) ?: return null
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
            return value.asString.trim().takeIf { it.isNotEmpty() }
        }
        val account = field("yhdm") ?: field("username") ?: return null
        val roles = json.get("rolecode")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (field("pylx") != "2" || field("sfxs") != "1" ||
            roles.none { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "02" }) return null
        return Profile(account, field("xm"))
    }

    fun isAuthenticationFailure(status: Int, url: String, body: String): Boolean {
        if (status in 300..399 || status == 401 || status == 403) return true
        if (status >= 500) return false
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return path.contains("/login") || path.contains("/cas") ||
            path.contains("authserver") || path.contains("authentication/require") ||
            body.contains("<form", true) &&
            (body.contains("password", true) || body.contains("统一身份认证"))
    }

    /**
     * 该 URL 的请求是否需要伪装成桌面浏览器。
     *
     * 研究生教务（`yjsgl`）经 WebVPN 反代后，服务端**按 UA 判定浏览器版本**，移动 UA 会
     * 拿到「你使用的浏览器已经过时」拦截页（实测 `Chrome Mobile 144` 即被拒）。因此除
     * 统一身份认证表单页之外，整条研究生链路都必须用桌面 UA。
     *
     * CAS 表单页（路径含 `/authserver/`）是唯一的例外：它由 WebView 渲染供用户输入，
     * 桌面 UA 会让 Chromium 的原生输入法光标与 HTML input 错位。登录入口 `/cas` 本身
     * 不属于例外——它只是一个跳转点，重定向到 `/authserver/login` 后才切回移动 UA。
     */
    fun needsDesktopUserAgentFor(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        // 只在 CAS 表单页本页保留移动 UA；`/cas` 登录入口与登录后的教务页都要桌面 UA。
        if (path.contains("/authserver/", ignoreCase = true)) return false
        val host = uri.host.orEmpty()
        // 校园网直连（`https://yjsgl.hitwh.edu.cn`）：同一个应用、同一套服务端 UA 判定，
        // 漏掉这条宿主会让直连整条链路退回移动 UA 并立刻拿到「浏览器已过时」拦截页。
        if (host.equals(DIRECT_HOST, ignoreCase = true)) return true
        if (!host.equals(base.host, ignoreCase = true)) return false
        return path.startsWith(base.path, ignoreCase = true)
    }
}
