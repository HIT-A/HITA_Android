package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 威海研究生登录两个已报告故障的回归测试。
 *
 * 1. 校园网直连报「浏览器版本过旧」；
 * 2. 非校园网（WebVPN）走完验证码后报「会话无效」。
 *
 * 两个 bug 的成因分别是「UA 判定只认反代宿主」和「把入口根地址当落地页判定」，
 * 都在纯函数层，因此直接用 URL / cookie / base 输入断言，不需要网络或设备。
 */
class WeihaiGraduateSessionRegressionTest {

    // ---------------------------------------------------------------- bug 1

    /**
     * 校园网直连时登录页是 `https://yjsgl.hitwh.edu.cn/`。
     *
     * 历史实现只对 `webvpn.hitwh.edu.cn` 且路径以反代前缀开头才切桌面 UA，
     * 直连因此整条链路都留在移动 UA 上，被 yjsgl 判成「浏览器已过时」。
     */
    @Test
    fun `direct graduate entry requires desktop user agent`() {
        for (url in listOf(
            "https://yjsgl.hitwh.edu.cn/",
            "https://yjsgl.hitwh.edu.cn/xtgl/login_slogin.html",
            "https://yjsgl.hitwh.edu.cn/authentication/main",
            "https://yjsgl.hitwh.edu.cn/student_index",
        )) {
            assertTrue(
                "直连入口 $url 必须切桌面 UA，否则服务端下发「浏览器已过时」拦截页",
                WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor(url),
            )
        }
    }

    /** 直连的 CAS 表单页仍是例外：桌面 UA 会让原生输入法光标与 input 错位。 */
    @Test
    fun `direct cas form keeps mobile user agent`() {
        assertFalse(
            WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor(
                "https://yjsgl.hitwh.edu.cn/authserver/login?service=https%3A%2F%2Fyjsgl.hitwh.edu.cn"
            )
        )
    }

    /** 反代链路原有行为不能被我改坏。 */
    @Test
    fun `vpn path still requires desktop user agent on graduate pages`() {
        val base = WeihaiGraduateSessionPolicy.BASE_URL
        assertTrue(WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor("$base/cas"))
        assertTrue(WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor("$base/authentication/main"))
        assertFalse(
            WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor(
                "$base/authserver/login?service=https%3A%2F%2Fyjsgl.hitwh.edu.cn"
            )
        )
    }

    /** 站点外页面不接管 UA。 */
    @Test
    fun `foreign hosts never get the desktop user agent`() {
        assertFalse(WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor("https://example.invalid/"))
        assertFalse(WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor(""))
    }

    // ---------------------------------------------------------------- bug 2

    /**
     * 凭据判定必须按入口分开。
     *
     * 反代的业务会话由 WebVPN 代管，浏览器只有 wengine_vpn_ticket；
     * 校园网直连没有反代层，业务会话就是 JSESSIONID。
     */
    @Test
    fun `session credential follows the entry`() {
        val vpnBase = WeihaiGraduateSessionPolicy.BASE_URL
        val directBase = "https://yjsgl.hitwh.edu.cn"
        val ticket = mapOf(WeihaiGraduateSessionPolicy.TICKET_COOKIE to "synthetic-ticket")
        val jsession = mapOf("JSESSIONID" to "synthetic-session")

        assertTrue(WeihaiEasEntryPolicy.hasGraduateSessionCredential(vpnBase, ticket))
        assertFalse(
            "反代入口没有票据就必须判未登录",
            WeihaiEasEntryPolicy.hasGraduateSessionCredential(vpnBase, jsession),
        )
        assertTrue(WeihaiEasEntryPolicy.hasGraduateSessionCredential(directBase, jsession))
        assertFalse(
            "直连入口只有反代票据说明登录没落在这个入口上",
            WeihaiEasEntryPolicy.hasGraduateSessionCredential(directBase, ticket),
        )
        // 空 base 视作反代（历史默认），不能因为缺参数就放行。
        assertTrue(WeihaiEasEntryPolicy.hasGraduateSessionCredential(null, ticket))
        assertFalse(WeihaiEasEntryPolicy.hasGraduateSessionCredential(null, jsession))
    }

    /** 大小写不一致的 JSESSIONID 与空值都要正确判定。 */
    @Test
    fun `jsessionid detection is case insensitive and ignores blanks`() {
        assertTrue(WeihaiGraduateSessionPolicy.hasJsessionId(mapOf("jsessionid" to "s")))
        assertTrue(WeihaiGraduateSessionPolicy.hasJsessionId(mapOf("JSESSIONID" to "s")))
        assertFalse(WeihaiGraduateSessionPolicy.hasJsessionId(mapOf("JSESSIONID" to "  ")))
        assertFalse(WeihaiGraduateSessionPolicy.hasJsessionId(emptyMap()))
    }

    /**
     * 直连落点：非登录页 + 已有业务会话即完成登录。
     *
     * 反代固定暴露 `/authentication/main`、`/student_index`，直连时教务自己的落点
     * 路径不同（例如 `index_initMenu`），沿用精确路径白名单会让登录成功后永远判不出来，
     * 最终在 `finishWithCookies` 被拦住、Activity 不返回，用户就看到失败。
     */
    @Test
    fun `direct landing accepts real graduate pages but not login pages`() {
        val direct = "https://yjsgl.hitwh.edu.cn"
        val session = mapOf("JSESSIONID" to "synthetic-session")
        for (path in listOf("/authentication/main", "/student_index", "/index_initMenu", "/main")) {
            assertTrue(path, WeihaiEasEntryPolicy.isDirectGraduateLanding(direct + path, session))
        }
        for (path in listOf(
            "/",
            "/xtgl/login_slogin.html",
            "/cas",
            "/authserver/login",
            "/authentication/require",
        )) {
            assertFalse(
                "登录/认证页不能算登录成功：$path",
                WeihaiEasEntryPolicy.isDirectGraduateLanding(direct + path, session),
            )
        }
        // 没有业务会话时，任何页面都不算完成。
        assertFalse(
            WeihaiEasEntryPolicy.isDirectGraduateLanding(
                "$direct/authentication/main",
                mapOf(WeihaiGraduateSessionPolicy.TICKET_COOKIE to "synthetic-ticket"),
            )
        )
        // 反代宿主不是直连入口。
        assertFalse(
            WeihaiEasEntryPolicy.isDirectGraduateLanding(
                WeihaiGraduateSessionPolicy.BASE_URL + "/authentication/main",
                session,
            )
        )
    }

    /**
     * 「会话无效」的直接成因：入口根地址被当成落地页。
     *
     * `WebViewLoginActivity` 写入 `web_base_url` 的是入口根地址
     * （`WeihaiEasEntryPolicy.timetableBaseUrl`），历史实现把它喂给
     * `isAuthenticatedLanding()`，该函数要求 path 精确等于 `/authentication/main`
     * 或 `/student_index`，根地址必然不匹配——于是无论登录是否成功都恒定报「会话无效」。
     */
    @Test
    fun `entry root url is not a landing page`() {
        val vpnRoot = WeihaiGraduateSessionPolicy.BASE_URL
        val ticket = mapOf(WeihaiGraduateSessionPolicy.TICKET_COOKIE to "synthetic-ticket")
        assertFalse(
            "入口根地址没有 landing path，不能当成功落地页",
            WeihaiGraduateSessionPolicy.isAuthenticatedLanding(vpnRoot, ticket),
        )
        assertFalse(
            WeihaiGraduateSessionPolicy.isAuthenticatedLanding("$vpnRoot/", ticket),
        )
        // 真正的落地页仍然必须是成功的。
        assertTrue(
            WeihaiGraduateSessionPolicy.isAuthenticatedLanding("$vpnRoot/authentication/main", ticket),
        )
        // 凭据判定（新口径）不受路径影响，只看入口与 cookie 种类。
        assertTrue(WeihaiEasEntryPolicy.hasGraduateSessionCredential(vpnRoot, ticket))
        assertEquals(
            vpnRoot,
            WeihaiEasEntryPolicy.graduateBaseUrlFor(WeihaiEasEntryPolicy.timetableBaseUrl(
                WeihaiEasEntryPolicy.Entry.VPN, graduate = true,
            )),
        )
        assertEquals(
            "https://yjsgl.hitwh.edu.cn",
            WeihaiEasEntryPolicy.graduateBaseUrlFor(WeihaiEasEntryPolicy.timetableBaseUrl(
                WeihaiEasEntryPolicy.Entry.DIRECT, graduate = true,
            )),
        )
    }

    /** 登录入口 base 与数据层请求根必须一致，否则会出现「登录成功但拉不到课表」。 */
    @Test
    fun `login entry base matches data layer request root`() {
        for (entry in WeihaiEasEntryPolicy.Entry.values()) {
            val base = WeihaiEasEntryPolicy.timetableBaseUrl(entry, graduate = true)
            assertEquals(base, WeihaiEasEntryPolicy.graduateBaseUrlFor(base))
        }
    }
}
