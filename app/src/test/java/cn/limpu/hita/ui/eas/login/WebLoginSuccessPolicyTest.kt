package cn.limpu.hita.ui.eas.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginSuccessPolicyTest {
    private val weihaiTeachingBase =
        "https://webvpn.hitwh.edu.cn/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667"

    @Test
    fun `Weihai mobile portal advances to teaching login`() {
        assertTrue(
            WebLoginSuccessPolicy.shouldOpenWeihaiTeachingEntry(
                "https://webvpn.hitwh.edu.cn/m/portal/?locale=zh",
                weihaiTeachingBase
            )
        )
    }

    @Test
    fun `Weihai teaching root advances but login and foreign hosts do not`() {
        assertTrue(WebLoginSuccessPolicy.shouldOpenWeihaiTeachingEntry(weihaiTeachingBase + "/", weihaiTeachingBase))
        assertFalse(WebLoginSuccessPolicy.shouldOpenWeihaiTeachingEntry(weihaiTeachingBase + "/loginCAS", weihaiTeachingBase))
        assertFalse(
            WebLoginSuccessPolicy.shouldOpenWeihaiTeachingEntry(
                "https://other.example/m/portal",
                weihaiTeachingBase
            )
        )
    }

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
    @Test fun benbuMfaAndIdentityIndexesNeverCompleteLoginEvenWithStaleCookies() {
        val cookies = mapOf("JSESSIONID" to "stale", "HIT" to "stale")
        for (host in listOf("ids.hit.edu.cn", "ids-hit-edu-cn-s.vpn.hit.edu.cn", "ids-hit-edu-cn-s.ivpn.hit.edu.cn")) {
            val mfa = "https://$host/authserver/reAuthCheck/reAuthIndex.do"
            assertTrue(WebLoginSuccessPolicy.isBenbuMfaPage(mfa))
            assertFalse(WebLoginSuccessPolicy.isBenbuAuthenticatedPage(mfa, cookies))
            assertFalse(WebLoginSuccessPolicy.canCollectBenbuSession(mfa))
            assertFalse(WebLoginSuccessPolicy.isBenbuAuthenticatedPage("https://$host/authserver/index", cookies))
        }
    }

    @Test fun benbuSessionRequiresExactTeachingHostAndBothCookies() {
        val cookies = mapOf("JSESSIONID" to "session", "HIT" to "hit")
        for (host in listOf("jwts.hit.edu.cn", "jwts-hit-edu-cn.vpn.hit.edu.cn", "jwts-hit-edu-cn.ivpn.hit.edu.cn")) {
            val teaching = "http://$host:1080/index"
            assertTrue(WebLoginSuccessPolicy.isBenbuAuthenticatedPage(teaching, cookies))
            assertFalse(WebLoginSuccessPolicy.isBenbuAuthenticatedPage(teaching, emptyMap()))
            assertFalse(WebLoginSuccessPolicy.isBenbuAuthenticatedPage(teaching, cookies + ("HIT" to "")))
            assertFalse(WebLoginSuccessPolicy.isBenbuAuthenticatedPage("http://$host:1080/loginCAS", cookies))
            // loginCAS callback may still be checked by polling; the native probe validates it.
            assertTrue(WebLoginSuccessPolicy.canCollectBenbuSession("http://$host:1080/loginCAS"))
        }
        for (url in listOf(
            "https://ivpn.hit.edu.cn/index", "https://i-hit-edu-cn.vpn.hit.edu.cn:1080/portal/index",
            "https://jwts-hit-edu-cn.vpn.hit.edu.cn.evil.example/index",
            "https://ids.hit.edu.cn/cjcx/query", "https://user@jwts.hit.edu.cn/index",
        )) assertFalse(WebLoginSuccessPolicy.isBenbuAuthenticatedPage(url, cookies))
    }

}
