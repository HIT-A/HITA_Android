package cn.limpu.hita.data.source.web.campus

import java.net.URI

/**
 * 深圳大学城图书馆空间预约的登录规则。
 *
 * 事实依据（接口文档 §2 / §3）：
 * - 业务会话是 `pima.space-reservation-fg.sid`（HttpOnly），缺失时
 *   `x-space-reservation-api` 返回 `401`。
 * - 校园网内可直连 `www.utszlib.edu.cn`；校外必须走 aTrust 代理域
 *   `www-utszlib-edu-cn-s.hitsz.edu.cn`。本应用没有 WebVPN 网关实现，
 *   因此统一让 WebView 打开入口页，由用户在页面内完成图书馆 CAS 登录。
 * - 登录落定页 HTML 内联 `window.__INITIAL_STATE__`，含
 *   `loginStatus.loggedIn`；它只能作为页面判定，真实凭据仍以 Cookie 为准。
 *
 * 与作业平台一样，这里只做「判定」，不做任何网络请求。
 */
internal object SilentPodSessionPolicy {

    /** 直连域：校园网内可用。 */
    const val DIRECT_ORIGIN = "https://www.utszlib.edu.cn"

    /** aTrust 代理域：校外访问用。Cookie 挂在代理域上。 */
    const val GATEWAY_ORIGIN = "https://www-utszlib-edu-cn-s.hitsz.edu.cn"

    /** 空间预约前端入口；登录也在这里完成。 */
    const val ENTRY_URL = "$DIRECT_ORIGIN/space-reservation-fg/?locale=zh_CN"

    const val ENTRY_PATH = "/space-reservation-fg/"

    /**
     * 登录页地址：始终是预约站点自身入口，由站点把用户带到图书馆认证中心。
     *
     * 不直接深链 `cas.utszlib.edu.cn/channel-auth`：那条链路由 CAS 自己生成
     * `redirect_uri`，回调落在 `//channel-auth/callback`（双斜杠），实测返回
     * 「认证失败，state状态码非法」——state 存在 CAS 服务端会话里，客户端无法修正。
     * 交给站点入口后，用户在页面里自行选择读者证登录或「三校师生认证」。
     */
    val LOGIN_URL: String = ENTRY_URL

    fun isAuthenticationErrorPage(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (!isCasPage(url)) return false
        // The live server currently emits a double slash before channelAuthError.
        return uri.path.orEmpty().trimStart('/') == "channelAuthError"
    }

    /** 业务接口基地址（直连，校园网内）。 */
    const val API_BASE_URL = "$DIRECT_ORIGIN/space-reservation-fg/x-space-reservation-api"

    /** 业务接口基地址（aTrust 代理域，校外）。 */
    const val GATEWAY_API_BASE_URL =
        "$GATEWAY_ORIGIN/space-reservation-fg/x-space-reservation-api"

    private const val SPACE_API_SUFFIX = "/x-space-reservation-api"

    /**
     * 读者核验等其他服务的基地址。
     *
     * 它们与空间预约共用同一套签名，只是前缀不同（`x-bdc-service-api`）；
     * 这里按当前会话的 base 推导，避免在直连域 / aTrust 域之间选错。
     */
    fun bdcBaseUrlFor(spaceBaseUrl: String): String {
        if (!spaceBaseUrl.endsWith(SPACE_API_SUFFIX)) return spaceBaseUrl
        return spaceBaseUrl.removeSuffix(SPACE_API_SUFFIX) + "/x-bdc-service-api"
    }

    /** 会话 Cookie 名：存在且非空即视为已登录。 */
    const val SESSION_COOKIE = "pima.space-reservation-fg.sid"

    /**
     * 从 Cookie 头里挑出会话 Cookie。
     *
     * 只接受单个、无控制字节/分隔符的值：浏览器 Cookie 头里可能混着别的站点凭证，
     * 整段转发会把无关凭据带出去；重复出现说明头已损坏，直接判未登录。
     */
    fun sessionCookie(header: String): String? {
        if (header.isEmpty()) return null
        if (header.any { it.code < 0x20 || it.code > 0x7e }) return null
        val matches = header.split(';')
            .map { it.trim() }
            .filter { it.substringBefore('=').trim() == SESSION_COOKIE }
        val value = (matches.singleOrNull()?.substringAfter('=', "") ?: return null).trim()
        if (value.isEmpty()) return null
        if (value.any { it == '"' || it == ',' || it == ';' || it == '\\' || it == ' ' }) return null
        return "$SESSION_COOKIE=$value"
    }

    /** 会话 Cookie 是否存在且可用。 */
    fun hasSession(header: String): Boolean = sessionCookie(header) != null

    /**
     * 选择业务接口的 base URL。
     *
     * 会话 Cookie 挂在哪个域上，请求就必须发到哪个域：校园网内直连，
     * 校外经 aTrust 代理域。传空的 Cookie 头时按直连域返回，供调用方拼登录页地址。
     */
    fun apiBaseUrlFor(host: String): String {
        val lower = host.lowercase()
        return if (lower == "www-utszlib-edu-cn-s.hitsz.edu.cn") GATEWAY_API_BASE_URL else API_BASE_URL
    }

    /**
     * 页面是否属于空间预约前端（入口页、落定页或 CAS 回跳页）。
     *
     * 只有落在这两类 host 且路径在 `/space-reservation-fg` 下，
     * 才值得去读 `__INITIAL_STATE__` 或取 Cookie。
     */
    fun isReservationPage(url: String): Boolean {
        val uri = parse(url) ?: return false
        // WebView 只放行 https，明文页面不可能是落定页，也不该被当成候选页。
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!isLibraryHost(uri.host.orEmpty())) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (uri.rawUserInfo != null) return false
        val path = uri.path.orEmpty()
        return path == ENTRY_PATH || path.startsWith(ENTRY_PATH)
    }

    /** 图书馆认证中心页面；到达这里说明还没登录完成。 */
    fun isCasPage(url: String): Boolean {
        val host = parse(url)?.host?.lowercase() ?: return false
        return host == "cas.utszlib.edu.cn" || host == "cas-utszlib-edu-cn-s.hitsz.edu.cn"
    }

    /**
     * 登录是否可判定为完成。
     *
     * 只要会话 Cookie 存在就算完成：落定页的 `loggedIn` 只是提示，
     * 而业务接口真正校验的是 Cookie，二者冲突时以 Cookie 为准。
     */
    fun isLoginComplete(cookieHeader: String): Boolean = hasSession(cookieHeader)

    /**
     * 读取页面 HTML 的登录态提示，用于在 Cookie 还没落盘时提前结束等待。
     * 返回 `null` 表示页面里没有可判定的状态。
     */
    fun pageLoginState(html: String): Boolean? = SilentPodParser.initialLoggedIn(html)

    /** 拦截跳转用：只允许 https 且 host 非空，避免明文提交账号密码。 */
    fun isSecureUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    private fun isLibraryHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "www.utszlib.edu.cn" || lower == "www-utszlib-edu-cn-s.hitsz.edu.cn"
    }

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: Exception) {
        null
    }
}
