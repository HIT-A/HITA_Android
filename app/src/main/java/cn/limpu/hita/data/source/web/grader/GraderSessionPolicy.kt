package cn.limpu.hita.data.source.web.grader

import java.net.URI

/**
 * Grader 会话的两个入口。
 *
 * 事实依据（2026-11-24 核对，公网 DNS + 直连抓取 + 校外蜂窝实测）：
 * - `grader.hitsz.edu.cn` 公网解析到内网 `10.249.12.224`，校外不可达。
 * - 深圳没有可用的传统 WebVPN：`webvpn.hitsz.edu.cn` 根路径、`/login` 与按深信服格式
 *   生成的 `/https/<token>` 全部 404。校外通道是 aTrust 零信任网关。
 * - 网关代理域 `grader-hitsz-edu-cn-s.hitsz.edu.cn` 在校外完成 aTrust 门户 + 统一认证后，
 *   `/home` 正常显示作业，`/api/auth/current_user/` 与 `/api/courses/?all=true` 直接
 *   返回 JSON；地址栏始终停在网关域，说明 OAuth 回跳的 `redirect_uri` 被网关改写了。
 * - 该域同时挂着 aTrust 自己的会话 cookie，只有 grader 的 `sessionid` 不够。
 */
internal object GraderSessionPolicy {
    /** 校园网内直连。 */
    const val ORIGIN = "https://grader.hitsz.edu.cn"
    const val LOGIN_URL = "$ORIGIN/api/login/sso"

    /** 校外经 aTrust 网关代理的同一套服务。 */
    const val GATEWAY_ORIGIN = "https://grader-hitsz-edu-cn-s.hitsz.edu.cn"

    enum class Entry(val origin: String, val host: String) {
        DIRECT(ORIGIN, "grader.hitsz.edu.cn"),
        GATEWAY(GATEWAY_ORIGIN, "grader-hitsz-edu-cn-s.hitsz.edu.cn"),
        ;

        /**
         * 登录起点。
         *
         * 直连直接走 SSO，与历史行为一致；校外必须从 `/home` 进——网关会把它带到
         * aTrust 门户，认证完再回跳到这个地址，而 `/api/login/sso` 起点未经实测。
         */
        val startUrl: String
            get() = when (this) {
                DIRECT -> LOGIN_URL
                GATEWAY -> "$origin/home"
            }
    }

    /**
     * 直连只保留业务必需的 cookie。
     *
     * 写请求（提交作业）需要 Django 的 `csrftoken` 与 `X-CSRFTOKEN` 头，所以直连也不能
     * 只存 `sessionid`；其余浏览器 cookie 一律丢弃。
     */
    fun sessionCookie(header: String): String? {
        if (header.any { it.code < 0x20 || it.code > 0x7e }) return null
        val pairs = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val kept = pairs.filter { it.substringBefore('=', "") in DIRECT_COOKIE_NAMES }
        val names = kept.map { it.substringBefore('=', "") }
        if (names.count { it == SESSION_COOKIE } != 1) return null
        if (names.count { it == CSRF_COOKIE } > 1) return null
        val session = kept.first { it.substringBefore('=', "") == SESSION_COOKIE }.substringAfter('=', "")
        if (!validSessionValue(session)) return null
        val csrf = kept.firstOrNull { it.substringBefore('=', "") == CSRF_COOKIE }?.substringAfter('=', "")
        if (csrf != null && !validSessionValue(csrf)) return null
        return buildList {
            add("$SESSION_COOKIE=$session")
            csrf?.let { add("$CSRF_COOKIE=$it") }
        }.joinToString("; ")
    }

    /**
     * 网关域上的 Cookie 头：除 grader 的 `sessionid` 外还挂着 aTrust 的会话 cookie，
     * 少了后者请求会被网关 302 回信任门户。因此整条头原样保留，只校验字符集、长度、
     * 名字形态与「`sessionid` 恰好一个」。
     *
     * 返回值同时是幂等的规范化形式：落盘时存这个值，[GraderStore] 用同一个函数复验。
     */
    fun gatewayCookie(header: String): String? {
        if (header.isEmpty() || header.length > MAX_COOKIE_HEADER_LENGTH) return null
        if (header.any { it.code < 0x20 || it.code > 0x7e }) return null
        val pairs = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (pairs.isEmpty() || pairs.any { !it.contains('=') }) return null
        val names = pairs.map { it.substringBefore('=', "") }
        if (names.any { name -> name.isEmpty() || !name.all(::isCookieNameChar) }) return null
        if (names.count { it == SESSION_COOKIE } != 1) return null
        val session = pairs.first { it.substringBefore('=', "") == SESSION_COOKIE }.substringAfter('=', "")
        if (!validSessionValue(session)) return null
        return pairs.joinToString("; ")
    }

    /** 按入口校验并规范化 Cookie 头；不合法返回 null，调用方按未登录处理。 */
    fun cookieHeader(entry: Entry, header: String): String? = when (entry) {
        Entry.DIRECT -> sessionCookie(header)
        Entry.GATEWAY -> gatewayCookie(header)
    }

    /** 写请求要带的 Django CSRF 令牌；缺失时写操作无法通过校验。 */
    fun csrfToken(cookieHeader: String): String? {
        if (cookieHeader.any { it.code < 0x20 || it.code > 0x7e }) return null
        val value = cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=', "") == CSRF_COOKIE }
            ?.substringAfter('=', "")
            ?: return null
        return value.takeIf { validSessionValue(it) }
    }

    /**
     * 登录落点。
     *
     * 除 `/home`、`/courses` 外，根路径也是落点：CAS 回调完成后 Grader 常先停在 `/`，
     * 再由前端路由（history API）切到具体页面——那种切换不会触发 `onPageFinished`，
     * 只认子路径就会一直等不到判定（2026-11-25 真机实证）。
     */
    fun isCandidatePage(url: String, entry: Entry = Entry.DIRECT): Boolean = try {
        val uri = URI(url)
        val path = uri.path.orEmpty()
        uri.scheme == "https" && uri.host == entry.host &&
            (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null &&
            (path.isEmpty() || path == "/" || path == "/home" || path.startsWith("/home/") ||
                path == "/courses" || path.startsWith("/courses/"))
    } catch (_: Exception) {
        false
    }

    private fun validSessionValue(value: String): Boolean =
        value.isNotEmpty() && value.none { it == '"' || it == ',' || it == ';' || it == '\\' || it == ' ' }

    private fun isCookieNameChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '-' || char == '_' || char == '.'

    private const val SESSION_COOKIE = "sessionid"
    private const val CSRF_COOKIE = "csrftoken"
    private val DIRECT_COOKIE_NAMES = setOf(SESSION_COOKIE, CSRF_COOKIE)
    private const val MAX_COOKIE_HEADER_LENGTH = 4_096
}
