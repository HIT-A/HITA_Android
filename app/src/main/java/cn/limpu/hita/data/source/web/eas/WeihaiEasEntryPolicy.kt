package cn.limpu.hita.data.source.web.eas

import java.net.URI

/**
 * 威海教务入口选择：校园网内直连、校外回落 WebVPN。
 *
 * 事实依据（2026-11-10 实测，详见 `docs/weihai-eas-network-entry.md`）：
 * - `jwts.hitwh.edu.cn` 解析到内网私有地址 `172.26.64.16`，`yjsgl.hitwh.edu.cn` 公网 NXDOMAIN；
 *   校外无法直达，必须经 `webvpn.hitwh.edu.cn` 反代。
 * - `webvpn.hitwh.edu.cn` 自身需要一次独立 CAS 认证，未认证一律 302 到 `/login`。
 * - 因此校园网内多走这一层认证是纯损失：既慢，又多一处可能失败。
 *
 * 设计：选择逻辑全部是纯函数，探测器只回答「这条路通不通」，两者分离便于离线测试。
 */
object WeihaiEasEntryPolicy {

    /** 直连：校园网内直接访问教务系统。 */
    const val DIRECT_UNDERGRADUATE_LOGIN = "http://jwts.hitwh.edu.cn/"
    const val DIRECT_GRADUATE_LOGIN = "https://yjsgl.hitwh.edu.cn/"
    const val DIRECT_EAS_PREFIX = "http://jwts.hitwh.edu.cn"

    /** WebVPN 反代：校外访问。 */
    const val VPN_LOGIN = "https://webvpn.hitwh.edu.cn/"
    const val VPN_EAS_PREFIX =
        "https://webvpn.hitwh.edu.cn/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667"
    val VPN_GRADUATE_LOGIN: String get() = WeihaiGraduateSessionPolicy.BASE_URL + "/cas"

    enum class Entry { DIRECT, VPN }

    /**
     * 选入口。
     *
     * 直连可达就用直连（更短、少一层认证）；否则回落 VPN。
     * 两个探测都失败时也选 VPN：探测可能被防火墙静默丢包，沿用历史默认行为
     * 至少不会比旧版本更差，也不会把用户彻底挡在门外。
     */
    fun choose(directReachable: Boolean, vpnReachable: Boolean): Entry = when {
        directReachable -> Entry.DIRECT
        else -> Entry.VPN
    }

    /** 登录页 URL。 */
    fun loginUrl(entry: Entry, graduate: Boolean): String = when (entry) {
        Entry.DIRECT -> if (graduate) DIRECT_GRADUATE_LOGIN else DIRECT_UNDERGRADUATE_LOGIN
        Entry.VPN -> if (graduate) VPN_GRADUATE_LOGIN else VPN_LOGIN
    }

    /**
     * 数据层使用的 base。必须与登录时选中的入口一致——否则会出现
     * 「登录成功但拉不到课表」这种最难排查的组合故障。
     */
    fun timetableBaseUrl(entry: Entry, graduate: Boolean): String = when (entry) {
        Entry.DIRECT -> if (graduate) DIRECT_GRADUATE_LOGIN.trimEnd('/') else DIRECT_EAS_PREFIX
        Entry.VPN -> if (graduate) WeihaiGraduateSessionPolicy.BASE_URL else VPN_EAS_PREFIX
    }

    /** 连通性探测目标。 */
    fun probeUrl(entry: Entry, graduate: Boolean): String = when (entry) {
        Entry.DIRECT -> if (graduate) DIRECT_GRADUATE_LOGIN else DIRECT_UNDERGRADUATE_LOGIN
        Entry.VPN -> VPN_LOGIN
    }

    /**
     * 研究生会话（以及本科直连）从持久化的 `webBaseUrl` 还原请求根地址。
     *
     * 只接受白名单 host，其余（空、畸形、别的域名）一律回落到 VPN 反代根，
     * 避免被异常数据带到任意地址。本科直连的 base 指向 `jwts`，研究生指向 `yjsgl`。
     */
    fun graduateBaseUrlFor(webBaseUrl: String?): String {
        val base = webBaseUrl.orEmpty().trimEnd('/')
        if (base.isEmpty()) return WeihaiGraduateSessionPolicy.BASE_URL
        val uri = runCatching { java.net.URI(base) }.getOrNull()
            ?: return WeihaiGraduateSessionPolicy.BASE_URL
        if (!uri.host.orEmpty().equals("yjsgl.hitwh.edu.cn", ignoreCase = true)) {
            return WeihaiGraduateSessionPolicy.BASE_URL
        }
        if (!uri.scheme.orEmpty().equals("https", ignoreCase = true)) {
            return WeihaiGraduateSessionPolicy.BASE_URL
        }
        return base
    }

    /**
     * 该入口下，登录是否已经完成。
     *
     * 两者的判据不同，不能共用一套：
     * - VPN 入口必须同时有 `wengine_vpn_ticket*`（反代票据）与非空 `JSESSIONID`；
     * - 直连入口**不会有** VPN 票据，只看业务会话与页面身份。
     */
    fun isAuthenticated(entry: Entry, url: String, cookies: Map<String, String>): Boolean =
        when (entry) {
            Entry.DIRECT -> isDirectAuthenticated(url, cookies)
            Entry.VPN -> isVpnAuthenticated(url, cookies)
        }

    /**
     * WebVPN 入口的判定：功能页 + 反代票据 + 业务会话，三者缺一不可。
     *
     * 与 `WebLoginSuccessPolicy.isWeihaiAuthenticatedPage` 保持同一口径，但放在本模块内，
     * 避免 `data` 层反向依赖 `ui` 层。
     */
    private fun isVpnAuthenticated(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.host.orEmpty().equals("webvpn.hitwh.edu.cn", ignoreCase = true)) return false
        val path = uri.path.orEmpty().lowercase()
        val isFunctionPage = path.contains("/http/") ||
            path.contains("kbcx") ||
            path.contains("cjcx") ||
            path.contains("kjscx") ||
            path.contains("query") ||
            path.contains("index")
        val hasCasTicket = uri.query.orEmpty().contains("ticket=ST-", ignoreCase = true)
        val isLoginPage = !hasCasTicket &&
            (path.contains("logincas") || path.endsWith("/login") || path.contains("/login/"))
        val hasVpnTicket = cookies.keys.any {
            it.contains(WEIHAI_TICKET_COOKIE_PREFIX, ignoreCase = true)
        }
        return isFunctionPage && !isLoginPage && hasVpnTicket && hasJsessionId(cookies)
    }

    private fun isDirectAuthenticated(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host.orEmpty().lowercase()
        if (!isDirectHost(host)) return false
        val path = uri.path.orEmpty().lowercase()
        // 登录表单页本身不算成功。
        if (path.contains("login")) return false
        // 直连没有 VPN 票据，只能靠业务会话判定。
        return hasJsessionId(cookies)
    }

    /** 大小写不敏感地判断是否存在非空 `JSESSIONID`（不同 WebVPN 节点大小写不一致）。 */
    private fun hasJsessionId(cookies: Map<String, String>): Boolean =
        cookies.any { (key, value) ->
            key.equals("JSESSIONID", ignoreCase = true) && value.isNotBlank()
        }

    private const val WEIHAI_TICKET_COOKIE_PREFIX = "wengine_vpn_ticket"

    /**
     * 研究生直连的落点判定。
     *
     * 走 WebVPN 时落点是反代后的 `authentication/main` 或 `student_index`，且必然带
     * `wengine_vpn_ticket`；直连时 host 是 `yjsgl.hitwh.edu.cn`、**没有** VPN 票据，
     * 只能看落点路径与业务会话。
     */
    fun isDirectGraduateLanding(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.orEmpty().equals("https", ignoreCase = true)) return false
        if (!uri.host.orEmpty().equals("yjsgl.hitwh.edu.cn", ignoreCase = true)) return false
        val path = uri.path.orEmpty().trimEnd('/').lowercase()
        if (!path.endsWith("/authentication/main") && !path.endsWith("/student_index")) return false
        return hasJsessionId(cookies)
    }

    private fun isDirectHost(host: String): Boolean =
        host == "jwts.hitwh.edu.cn" || host == "yjsgl.hitwh.edu.cn"
}
