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

    @Test fun `Shenzhen trust portal pages continue to academic login`() {
        for (url in listOf(
            "https://trust.hitsz.edu.cn/portal",
            "https://trust.hitsz.edu.cn/portal/",
            "https://trust.hitsz.edu.cn/portal/shortcut.html",
            "https://trust.hitsz.edu.cn/portal/#/page_app_handler",
            "https://TRUST.hitsz.edu.cn/portal/index",
        )) assertTrue(url, WebLoginSuccessPolicy.isShenzhenTrustPortal(url))

        for (url in listOf(
            "http://trust.hitsz.edu.cn/portal",
            "https://trust.hitsz.edu.cn/other",
            "https://trust.hitsz.edu.cn.evil.example/portal",
            "https://jw-hitsz-edu-cn.hitsz.edu.cn/authentication/main",
            "not-a-url",
            "",
        )) assertFalse(url, WebLoginSuccessPolicy.isShenzhenTrustPortal(url))
    }

    @Test fun `Shenzhen academic entry is recognised so the flow does not loop`() {
        assertTrue(WebLoginSuccessPolicy.isShenzhenAcademicEntry("https://jw-hitsz-edu-cn.hitsz.edu.cn/authentication/main"))
        assertTrue(WebLoginSuccessPolicy.isShenzhenAcademicEntry("https://jw.hitsz.edu.cn/"))
        assertFalse(WebLoginSuccessPolicy.isShenzhenAcademicEntry("https://trust.hitsz.edu.cn/portal"))
        assertFalse(WebLoginSuccessPolicy.isShenzhenAcademicEntry("https://jw.hitsz.edu.cn.evil.example/"))
    }

    /**
     * 回归防线：教务入口无会话时会被 302 到 aTrust 的 shortcut.html / #/login。
     * 这两类页面一旦被当成"已连接"就回跳教务入口，会形成
     * 门户 → 教务 → verify → 门户 的无限重定向，页面永远停在 Loading。
     */
    @Test fun `aTrust auth pages are never treated as connected`() {
        for (url in listOf(
            "https://trust.hitsz.edu.cn/portal/shortcut.html?dest=%23%21%2Flogin&appUrl=x",
            "https://trust.hitsz.edu.cn/portal/?redirectid=0.1#/login",
            "https://trust.hitsz.edu.cn/portal/#!/login",
            "https://trust.hitsz.edu.cn/portal/#/down_client_mobile",
            "https://trust.hitsz.edu.cn/portal/#/app_center/auth",
        )) {
            assertTrue("portal: $url", WebLoginSuccessPolicy.isShenzhenTrustPortal(url))
            assertTrue("auth page: $url", WebLoginSuccessPolicy.isShenzhenTrustPortalAuthPage(url))
            assertFalse("connected: $url", WebLoginSuccessPolicy.isShenzhenTrustPortalConnected(url))
        }
    }

    @Test fun `aTrust portal home after login is connected`() {
        for (url in listOf(
            "https://trust.hitsz.edu.cn/portal/#/service",
            "https://trust.hitsz.edu.cn/portal/#/user_info",
            "https://trust.hitsz.edu.cn/portal/index",
        )) {
            assertFalse("auth page: $url", WebLoginSuccessPolicy.isShenzhenTrustPortalAuthPage(url))
            assertTrue("connected: $url", WebLoginSuccessPolicy.isShenzhenTrustPortalConnected(url))
        }
    }

    @Test fun `aTrust portal auto login only fires on the login route`() {
        assertTrue(
            ShenzhenTrustPortalAutoLogin.shouldAutoLogin(
                "https://trust.hitsz.edu.cn/portal/?redirectid=0.5#/login"
            )
        )
        for (url in listOf(
            "https://trust.hitsz.edu.cn/portal/shortcut.html?dest=%23%21%2Flogin",
            "https://trust.hitsz.edu.cn/portal/#/service",
            "https://jw-hitsz-edu-cn.hitsz.edu.cn/authentication/main",
        )) {
            assertFalse(url, ShenzhenTrustPortalAutoLogin.shouldAutoLogin(url))
        }
    }

    @Test fun `aTrust portal login script targets the agreement checkbox`() {
        val script = ShenzhenTrustPortalAutoLogin.buildLoginScript()
        // 门户可能有「记住我」等多个勾选框：必须按协议/同意文案定位，不能盲选第一个，
        // 否则勾错框后点登录会被服务端拒绝。
        assertTrue(script.contains("isAgreementLabel"))
        assertTrue(script.contains("协议"))
        assertTrue(script.contains("同意"))
        assertTrue(script.contains("agree"))
        assertTrue(script.contains("JSON.stringify({clicked:true"))
    }

    @Test fun `Shenzhen CAS callback landing completes login`() {
        // aTrust 放行后 jw 代理回跳到 /cas，页面已是教务首页，必须能被判定为已登录。
        assertTrue(
            WebLoginSuccessPolicy.isShenzhenAuthenticatedPage(
                "https://jw-hitsz-edu-cn.hitsz.edu.cn/cas",
                mapOf("SESSION" to "session")
            )
        )
        assertFalse(
            WebLoginSuccessPolicy.isShenzhenAuthenticatedPage(
                "https://jw-hitsz-edu-cn.hitsz.edu.cn/cas",
                emptyMap()
            )
        )
    }
}
