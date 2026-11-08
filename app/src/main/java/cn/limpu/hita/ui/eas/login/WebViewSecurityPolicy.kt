package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken
import java.net.URI

/**
 * 登录 WebView 的安全基线（纯规则，可 JVM 单测）。
 *
 * 设计约束：不能打断三校区现有的认证跳转链路（aTrust 门户、Zero Trust 网关、CAS、
 * WebVPN 回跳）。因此这里只做三件事：
 * 1. 收紧 WebView 的本地文件能力（登录页不需要 file:// / content://）；
 * 2. 判定导航是否允许在主 WebView 内发生（白名单按 host 后缀，覆盖现有全部落点）；
 * 3. 给弹窗/`window.open` 导航提供同一套判定，避免任意页面把主 WebView 拽到任意地址。
 *
 * SSL 错误由 Activity 显式 `cancel()` 处理，这里只提供统一的 URL 判定。
 */
internal object WebViewSecurityPolicy {

    /** 校区认证/教务落点 host 后缀白名单。子域匹配必须以 `.` 为界，防止伪装后缀。 */
    private val commonHostSuffixes = listOf(
        "hit.edu.cn",
        "hitsz.edu.cn",
        "hitwh.edu.cn",
    )

    private val benbuHostSuffixes = listOf("vpn.hit.edu.cn", "ivpn.hit.edu.cn")

    /** 允许在主 WebView 内加载的 host 后缀集合（按校区）。 */
    fun allowedHostSuffixes(campus: EASToken.Campus): List<String> = when (campus) {
        EASToken.Campus.SHENZHEN -> commonHostSuffixes
        EASToken.Campus.BENBU -> commonHostSuffixes + benbuHostSuffixes
        EASToken.Campus.WEIHAI -> commonHostSuffixes
    }

    /**
     * 主框架导航是否放行。
     *
     * 放行条件：http/https + 无 userinfo + host 命中白名单后缀。
     * 其余 scheme（intent://、file://、javascript:、data: 等）一律拦截：登录流程不需要，
     * 放行会把任意 scheme 交给系统组件或执行内联脚本。
     */
    fun isAllowedNavigation(campus: EASToken.Campus, url: String?): Boolean {
        val uri = parse(url) ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return allowedHostSuffixes(campus).any { host == it || host.endsWith(".$it") }
    }

    /**
     * 弹窗（`window.open` / target=_blank）是否允许转发到主 WebView。
     * 与主框架导航同一口径，另外要求 host 非空。
     */
    fun isAllowedPopupForward(campus: EASToken.Campus, url: String?): Boolean =
        isAllowedNavigation(campus, url)

    /** 该 URL 是否属于必须拒绝的明文页面（用于诊断与测试断言）。 */
    fun isCleartextNavigation(url: String?): Boolean =
        parse(url)?.scheme?.lowercase() == "http"

    private fun parse(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        return runCatching { URI(url) }.getOrNull()
    }
}
