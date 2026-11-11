package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 威海教务入口选择策略。
 *
 * 背景：威海教务系统（`jwts` / `yjsgl`）只在内网暴露，校外必须经 `webvpn.hitwh.edu.cn`
 * 反代。但校园网内用户本就在内网，直连才是短路径。历史实现一律走 VPN，导致校园网
 * 用户被迫多走一次 VPN 的 CAS 认证，失败面更大。
 */
class WeihaiEasEntryPolicyTest {

    @Test
    fun `direct entry wins when the direct endpoint is reachable`() {
        val entry = WeihaiEasEntryPolicy.choose(directReachable = true, vpnReachable = true)
        assertEquals(WeihaiEasEntryPolicy.Entry.DIRECT, entry)
    }

    @Test
    fun `vpn entry is used when only vpn is reachable`() {
        val entry = WeihaiEasEntryPolicy.choose(directReachable = false, vpnReachable = true)
        assertEquals(WeihaiEasEntryPolicy.Entry.VPN, entry)
    }

    @Test
    fun `vpn is the fallback when neither probe succeeds`() {
        // 探测本身可能被防火墙静默丢包：此时沿用历史默认（VPN），
        // 至少与旧版本行为一致，不会把用户挡在门外。
        val entry = WeihaiEasEntryPolicy.choose(directReachable = false, vpnReachable = false)
        assertEquals(WeihaiEasEntryPolicy.Entry.VPN, entry)
    }

    @Test
    fun `undergraduate login url follows the chosen entry`() {
        assertEquals(
            WeihaiEasEntryPolicy.DIRECT_UNDERGRADUATE_LOGIN,
            WeihaiEasEntryPolicy.loginUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = false),
        )
        assertEquals(
            WeihaiEasEntryPolicy.VPN_LOGIN,
            WeihaiEasEntryPolicy.loginUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = false),
        )
    }

    @Test
    fun `graduate login url follows the chosen entry`() {
        assertEquals(
            WeihaiEasEntryPolicy.DIRECT_GRADUATE_LOGIN,
            WeihaiEasEntryPolicy.loginUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = true),
        )
        assertEquals(
            WeihaiEasEntryPolicy.VPN_GRADUATE_LOGIN,
            WeihaiEasEntryPolicy.loginUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = true),
        )
    }

    @Test
    fun `graduated base url feeds the schedule source`() {
        // 数据层用的 base 必须与登录时选中的入口一致，否则登录成功但拉不到课表。
        assertEquals(
            "https://yjsgl.hitwh.edu.cn",
            WeihaiEasEntryPolicy.timetableBaseUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = true),
        )
        assertEquals(
            WeihaiGraduateSessionPolicy.BASE_URL,
            WeihaiEasEntryPolicy.timetableBaseUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = true),
        )
        assertEquals(
            WeihaiEasEntryPolicy.DIRECT_EAS_PREFIX,
            WeihaiEasEntryPolicy.timetableBaseUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = false),
        )
        assertEquals(
            WeihaiEasEntryPolicy.VPN_EAS_PREFIX,
            WeihaiEasEntryPolicy.timetableBaseUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = false),
        )
    }

    @Test
    fun `direct entry does not require a vpn ticket`() {
        val cookies = mapOf("JSESSIONID" to "synthetic-session")
        // 直连没有 wengine_vpn_ticket，旧判定会永远不认为登录成功。
        assertTrue(
            WeihaiEasEntryPolicy.isAuthenticated(
                entry = WeihaiEasEntryPolicy.Entry.DIRECT,
                url = "http://jwts.hitwh.edu.cn/xtgl/index_initMenu.html",
                cookies = cookies,
            )
        )
        // VPN 入口仍要求票据，否则可能把中间态当成成功。
        assertFalse(
            WeihaiEasEntryPolicy.isAuthenticated(
                entry = WeihaiEasEntryPolicy.Entry.VPN,
                url = "http://jwts.hitwh.edu.cn/xtgl/index_initMenu.html",
                cookies = cookies,
            )
        )
        assertTrue(
            WeihaiEasEntryPolicy.isAuthenticated(
                entry = WeihaiEasEntryPolicy.Entry.VPN,
                url = "https://webvpn.hitwh.edu.cn/http/7772/xsxxx",
                cookies = cookies + ("wengine_vpn_ticketwebvpn_hitwh_edu_cn" to "synthetic-ticket"),
            )
        )
    }

    @Test
    fun `direct entry still rejects pages without a session`() {
        assertFalse(
            WeihaiEasEntryPolicy.isAuthenticated(
                entry = WeihaiEasEntryPolicy.Entry.DIRECT,
                url = "http://jwts.hitwh.edu.cn/xtgl/index_initMenu.html",
                cookies = emptyMap(),
            )
        )
        // 登录页本身不能算成功。
        assertFalse(
            WeihaiEasEntryPolicy.isAuthenticated(
                entry = WeihaiEasEntryPolicy.Entry.DIRECT,
                url = "http://jwts.hitwh.edu.cn/xtgl/login_slogin.html",
                cookies = mapOf("JSESSIONID" to "synthetic-session"),
            )
        )
    }

    @Test
    fun `graduate base url honours only the direct host`() {
        // 直连持久化下来的 base 会被采用。
        assertEquals(
            "https://yjsgl.hitwh.edu.cn",
            WeihaiEasEntryPolicy.graduateBaseUrlFor("https://yjsgl.hitwh.edu.cn/"),
        )
        // 空值、别的域名、非 https 一律回落 VPN 反代根。
        assertEquals(
            WeihaiGraduateSessionPolicy.BASE_URL,
            WeihaiEasEntryPolicy.graduateBaseUrlFor(null),
        )
        assertEquals(
            WeihaiGraduateSessionPolicy.BASE_URL,
            WeihaiEasEntryPolicy.graduateBaseUrlFor(""),
        )
        assertEquals(
            WeihaiGraduateSessionPolicy.BASE_URL,
            WeihaiEasEntryPolicy.graduateBaseUrlFor("https://evil.invalid"),
        )
        assertEquals(
            WeihaiGraduateSessionPolicy.BASE_URL,
            WeihaiEasEntryPolicy.graduateBaseUrlFor("https://jwts.hitwh.edu.cn"),
        )
        assertEquals(
            WeihaiGraduateSessionPolicy.BASE_URL,
            WeihaiEasEntryPolicy.graduateBaseUrlFor("http://yjsgl.hitwh.edu.cn"),
        )
    }

    @Test
    fun `probe targets point at each entry`() {
        assertEquals(
            "http://jwts.hitwh.edu.cn/",
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = false),
        )
        assertEquals(
            "https://yjsgl.hitwh.edu.cn/",
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = true),
        )
        assertEquals(
            "https://webvpn.hitwh.edu.cn/",
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = false),
        )
        assertEquals(
            "https://webvpn.hitwh.edu.cn/",
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = true),
        )
    }
}
