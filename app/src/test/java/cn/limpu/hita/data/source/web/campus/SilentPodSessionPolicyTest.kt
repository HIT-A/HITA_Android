package cn.limpu.hita.data.source.web.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间预约会话判定。WebView 只允许 https 并会取 CookieManager 里的
 * `pima.space-reservation-fg.sid`，所以这里断言：只认这一个 Cookie、
 * 重复或含分隔符的值一律判未登录、入口页/认证中心页的归属判定不能放宽。
 */
class SilentPodSessionPolicyTest {

    private val name = SilentPodSessionPolicy.SESSION_COOKIE

    @Test
    fun `login starts from reservation entry instead of a CAS deep link`() {
        val uri = java.net.URI(SilentPodSessionPolicy.LOGIN_URL)
        assertEquals("https", uri.scheme)
        assertEquals("www.utszlib.edu.cn", uri.host)
        assertEquals(SilentPodSessionPolicy.ENTRY_PATH, uri.path)
        // 自主深链 channel-auth 会让 CAS 自己生成带双斜杠的 redirect_uri，
        // 回调时 state 必被拒；登录必须由站点入口发起。
        assertFalse(uri.toString().contains("channel-auth"))
        assertFalse(uri.toString().contains("cas.utszlib.edu.cn"))
    }

    @Test
    fun `callback errors are recognized without accepting arbitrary error pages`() {
        assertTrue(SilentPodSessionPolicy.isAuthenticationErrorPage("https://cas.utszlib.edu.cn//channelAuthError?errorMsg=state"))
        assertTrue(SilentPodSessionPolicy.isAuthenticationErrorPage("https://cas.utszlib.edu.cn/channelAuthError"))
        listOf(
            "https://cas.utszlib.edu.cn/login",
            "https://cas.utszlib.edu.cn/channel-auth/callback?code=test&state=test",
            "http://cas.utszlib.edu.cn/channelAuthError",
            "https://user@cas.utszlib.edu.cn/channelAuthError",
            "https://cas.utszlib.edu.cn.evil.invalid/channelAuthError",
        ).forEach { assertFalse(SilentPodSessionPolicy.isAuthenticationErrorPage(it)) }
    }

    @Test
    fun `session cookie is extracted verbatim`() {
        assertEquals(
            "$name=abc123",
            SilentPodSessionPolicy.sessionCookie("foo=1; $name=abc123; pima.locale=zh_CN"),
        )
    }

    @Test
    fun `missing or empty cookie means signed out`() {
        assertNull(SilentPodSessionPolicy.sessionCookie(""))
        assertNull(SilentPodSessionPolicy.sessionCookie("pima.locale=zh_CN"))
        assertNull(SilentPodSessionPolicy.sessionCookie("$name="))
        assertNull(SilentPodSessionPolicy.sessionCookie("$name=  "))
        assertFalse(SilentPodSessionPolicy.hasSession(""))
    }

    @Test
    fun `duplicate session cookie is rejected instead of picking one`() {
        assertNull(SilentPodSessionPolicy.sessionCookie("$name=a; $name=b"))
    }

    @Test
    fun `control characters and separators are rejected`() {
        assertNull(SilentPodSessionPolicy.sessionCookie("$name=ab\u0000cd"))
        assertNull(SilentPodSessionPolicy.sessionCookie("$name=ab\"cd"))
        assertNull(SilentPodSessionPolicy.sessionCookie("$name=ab,cd"))
        assertNull(SilentPodSessionPolicy.sessionCookie("$name=ab cd"))
        assertNull(SilentPodSessionPolicy.sessionCookie("$name=ab\\cd"))
    }

    @Test
    fun `foreign gateway cookies alone do not count as a session`() {
        // 只有 sdp_user_token（WebVPN 放行票据）不算登录空间预约。
        assertNull(SilentPodSessionPolicy.sessionCookie("sdp_user_token=abc123"))
    }

    @Test
    fun `login is complete only when the session cookie exists`() {
        assertTrue(SilentPodSessionPolicy.isLoginComplete("$name=xyz"))
        assertFalse(SilentPodSessionPolicy.isLoginComplete("pima.locale=zh_CN"))
    }

    @Test
    fun `entry page and landing page are recognized on both hosts`() {
        assertTrue(
            SilentPodSessionPolicy.isReservationPage("https://www.utszlib.edu.cn/space-reservation-fg/?locale=zh_CN")
        )
        assertTrue(
            SilentPodSessionPolicy.isReservationPage(
                "https://www-utszlib-edu-cn-s.hitsz.edu.cn/space-reservation-fg/?ticket=ST-1"
            )
        )
        assertFalse(SilentPodSessionPolicy.isReservationPage("https://www.utszlib.edu.cn/"))
        assertFalse(SilentPodSessionPolicy.isReservationPage("http://www.utszlib.edu.cn/space-reservation-fg/"))
        assertFalse(SilentPodSessionPolicy.isReservationPage("https://evil.example/space-reservation-fg/"))
        assertFalse(SilentPodSessionPolicy.isReservationPage("not a url"))
    }

    @Test
    fun `library cas hosts are recognized`() {
        assertTrue(
            SilentPodSessionPolicy.isCasPage("https://cas.utszlib.edu.cn/cas/login?service=x")
        )
        assertTrue(
            SilentPodSessionPolicy.isCasPage("https://cas-utszlib-edu-cn-s.hitsz.edu.cn/cas/login")
        )
        assertFalse(SilentPodSessionPolicy.isCasPage("https://ids.hit.edu.cn/authserver/login"))
    }

    @Test
    fun `page login state is advisory and defaults to unknown`() {
        assertEquals(
            true,
            SilentPodSessionPolicy.pageLoginState(
                """<script>window.__INITIAL_STATE__={"loginStatus":{"loggedIn":true}}</script>"""
            ),
        )
        assertNull(SilentPodSessionPolicy.pageLoginState("<html></html>"))
    }

    @Test
    fun `non https navigation is rejected`() {
        assertTrue(SilentPodSessionPolicy.isSecureUrl("https://www.utszlib.edu.cn/space-reservation-fg/"))
        assertFalse(SilentPodSessionPolicy.isSecureUrl("http://www.utszlib.edu.cn/space-reservation-fg/"))
        assertFalse(SilentPodSessionPolicy.isSecureUrl("file:///etc/passwd"))
        assertFalse(SilentPodSessionPolicy.isSecureUrl("https://"))
    }
}
