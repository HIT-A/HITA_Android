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
