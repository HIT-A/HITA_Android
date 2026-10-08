package cn.limpu.hita.ui.eas.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginSuccessPolicyTest {
    @Test
    fun `Weihai loginCAS without authenticated cookies is not success`() {
        assertFalse(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/loginCAS",
                emptyMap()
            )
        )
    }

    @Test
    fun `Weihai loginCAS with cookies but no CAS ticket is still not success`() {
        // 登录前的 loginCAS 页也可能带 JSESSIONID，必须靠路径排除防提前收工
        assertFalse(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/loginCAS",
                mapOf(
                    "wengine_vpn_ticket" to "ticket",
                    "JSESSIONID" to "session"
                )
            )
        )
    }

    @Test
    fun `Weihai loginCAS with CAS ticket and authenticated cookies is success`() {
        // CAS 验票回跳落在 loginCAS 上（?ticket=ST-...）：放行，不再卡空白页
        assertTrue(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/loginCAS?ticket=ST-12345-abcdefg",
                mapOf(
                    "wengine_vpn_ticket" to "ticket",
                    "JSESSIONID" to "session"
                )
            )
        )
    }

    @Test
    fun `Weihai loginCAS with CAS ticket but no cookies is not success`() {
        assertFalse(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/loginCAS?ticket=ST-12345-abcdefg",
                emptyMap()
            )
        )
    }

    @Test
    fun `Weihai function page with VPN ticket and JSESSIONID is success`() {
        assertTrue(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/kbcx/queryGrkb",
                mapOf(
                    "wengine_vpn_ticket" to "ticket",
                    "JSESSIONID" to "session"
                )
            )
        )
    }

    @Test
    fun `Weihai new EAS root page with authenticated cookies is success`() {
        assertTrue(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/",
                mapOf(
                    "wengine_vpn_ticket" to "ticket",
                    "JSESSIONID" to "session"
                )
            )
        )
    }

    @Test
    fun `Weihai function page with lowercase jsessionid key is success`() {
        // 某些 WebVPN 节点下 cookie 名大小写不一致，不能因 key 大小写漏判
        assertTrue(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/kbcx/queryGrkb",
                mapOf(
                    "wengine_vpn_ticket" to "ticket",
                    "jsessionid" to "session"
                )
            )
        )
    }

    @Test
    fun `Weihai function page with blank jsessionid is not success`() {
        assertFalse(
            WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(
                "https://webvpn.hitwh.edu.cn/http/eas/kbcx/queryGrkb",
                mapOf(
                    "wengine_vpn_ticket" to "ticket",
                    "JSESSIONID" to ""
                )
            )
        )
    }

    @Test
    fun `Shenzhen probes both proxy and direct hosts`() {
        val urls = WebLoginSuccessPolicy.shenzhenCookieProbeUrls(
            proxyBaseUrl = "https://jw-hitsz-edu-cn.hitsz.edu.cn",
            directBaseUrl = "https://jw.hitsz.edu.cn"
        )

        assertTrue(urls.any { it.startsWith("https://jw-hitsz-edu-cn.hitsz.edu.cn/") })
        assertTrue(urls.any { it.startsWith("https://jw.hitsz.edu.cn/") })
    }

    @Test
    fun `Shenzhen direct host keeps direct web base`() {
        assertEquals(
            "https://jw.hitsz.edu.cn",
            WebLoginSuccessPolicy.shenzhenWebBaseUrl(
                host = "jw.hitsz.edu.cn",
                proxyBaseUrl = "https://jw-hitsz-edu-cn.hitsz.edu.cn",
                directBaseUrl = "https://jw.hitsz.edu.cn"
            )
        )
    }

    @Test
    fun `Shenzhen new academic root with proxy session is success`() {
        assertTrue(
            WebLoginSuccessPolicy.isShenzhenAuthenticatedPage(
                "https://jw-hitsz-edu-cn.hitsz.edu.cn/",
                mapOf("SESSION" to "session")
            )
        )
    }

    @Test
    fun `Shenzhen login page is not success even with session cookie`() {
        assertFalse(
            WebLoginSuccessPolicy.isShenzhenAuthenticatedPage(
                "https://jw.hitsz.edu.cn/authentication/main/login",
                mapOf("JSESSIONID" to "session", "route" to "route")
            )
        )
    }
}
