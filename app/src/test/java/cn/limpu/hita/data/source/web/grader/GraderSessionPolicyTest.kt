package cn.limpu.hita.data.source.web.grader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作业平台会话的准入规则。登录 WebView 现在会在 SSL 错误时取消加载、只放行 https，
 * 这些前提依赖 `sessionCookie` 只接受单个、无控制字节的 `sessionid`。
 */
class GraderSessionPolicyTest {

    @Test
    fun `single sessionid cookie is extracted verbatim`() {
        assertEquals(
            "sessionid=abc123",
            GraderSessionPolicy.sessionCookie("foo=1; sessionid=abc123; bar=2")
        )
    }

    @Test
    fun `duplicate sessionid is rejected instead of picking one`() {
        assertNull(GraderSessionPolicy.sessionCookie("sessionid=a; sessionid=b"))
    }

    @Test
    fun `control characters and separators are rejected`() {
        assertNull(GraderSessionPolicy.sessionCookie("sessionid=ab\u0000cd"))
        assertNull(GraderSessionPolicy.sessionCookie("sessionid=ab\"cd"))
        assertNull(GraderSessionPolicy.sessionCookie("sessionid=ab,cd"))
        assertNull(GraderSessionPolicy.sessionCookie("sessionid=ab cd"))
        assertNull(GraderSessionPolicy.sessionCookie("sessionid="))
    }

    @Test
    fun `only sessionid is accepted from a browser cookie header`() {
        assertNull(GraderSessionPolicy.sessionCookie("CASTGC=abc; JSESSIONID=def"))
    }

    @Test
    fun `candidate pages are limited to https home and courses on the exact host`() {
        assertTrue(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn/home"))
        assertTrue(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn/courses/42"))
        assertFalse(GraderSessionPolicy.isCandidatePage("http://grader.hitsz.edu.cn/home"))
        assertFalse(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn.evil.invalid/home"))
        assertFalse(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn:8443/home"))
        assertFalse(GraderSessionPolicy.isCandidatePage("https://user@grader.hitsz.edu.cn/home"))
        assertFalse(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn/api/auth/current_user/"))
    }

    @Test
    fun `login url is https sso entry`() {
        assertTrue(GraderSessionPolicy.LOGIN_URL.startsWith("https://"))
        assertTrue(GraderSessionPolicy.ORIGIN.startsWith("https://"))
    }

    @Test
    fun `gateway cookie keeps the whole header but still requires exactly one sessionid`() {
        val header = "SESSION=abc; sessionid=s1; sdp_user_token=xyz"
        assertEquals(header, GraderSessionPolicy.gatewayCookie(header))
        // 落盘的是这个规范化形式，再校验一次必须幂等。
        assertEquals(header, GraderSessionPolicy.gatewayCookie(requireNotNull(GraderSessionPolicy.gatewayCookie(header))))
    }

    @Test
    fun `gateway cookie without grader session or with malformed pairs is rejected`() {
        for (header in listOf(
            "SESSION=abc; sdp_user_token=xyz",
            "sessionid=a; sessionid=b",
            "sessionid=; SESSION=abc",
            "sessionid=a; broken",
            "sessionid=ab cd; SESSION=abc",
            "sessionid=ab\"cd; SESSION=abc",
            "sessionid=a; " + "x".repeat(4_096),
        )) assertNull(header.take(40), GraderSessionPolicy.gatewayCookie(header))
    }

    @Test
    fun `cookie header validation is scoped to the entry`() {
        assertEquals(
            "sessionid=b",
            GraderSessionPolicy.cookieHeader(GraderSessionPolicy.Entry.DIRECT, "sessionid=b"),
        )
        // 直连只保留 sessionid，网关 cookie 一律丢弃；网关入口不接受缺少 sessionid 的头。
        assertEquals(
            "sessionid=b",
            GraderSessionPolicy.cookieHeader(GraderSessionPolicy.Entry.DIRECT, "SESSION=a; sessionid=b"),
        )
        assertNull(GraderSessionPolicy.cookieHeader(GraderSessionPolicy.Entry.GATEWAY, "SESSION=a"))
    }

    @Test
    fun `candidate pages are scoped to the entry host`() {
        val gateway = GraderSessionPolicy.Entry.GATEWAY
        assertTrue(GraderSessionPolicy.isCandidatePage("https://grader-hitsz-edu-cn-s.hitsz.edu.cn/home", gateway))
        assertTrue(GraderSessionPolicy.isCandidatePage("https://grader-hitsz-edu-cn-s.hitsz.edu.cn/courses/42", gateway))
        assertFalse(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn/home", gateway))
        assertFalse(GraderSessionPolicy.isCandidatePage("https://grader-hitsz-edu-cn-s.hitsz.edu.cn/home"))
        assertFalse(
            GraderSessionPolicy.isCandidatePage(
                "https://grader-hitsz-edu-cn-s.hitsz.edu.cn.evil.invalid/home", gateway,
            )
        )
    }

    @Test
    fun `direct session keeps the csrf cookie and drops everything else`() {
        assertEquals(
            "sessionid=s1; csrftoken=c1",
            GraderSessionPolicy.sessionCookie("a=1; csrftoken=c1; sessionid=s1; b=2"),
        )
        assertEquals("c1", GraderSessionPolicy.csrfToken("a=1; csrftoken=c1; sessionid=s1"))
        assertNull(GraderSessionPolicy.csrfToken("sessionid=s1"))
    }

    @Test
    fun `the root path is also a landing page`() {
        // CAS 回调完成后 Grader 常先停在 `/`，前端再用 history API 切换，不会再触发加载回调。
        assertTrue(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn/"))
        assertTrue(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn"))
        assertTrue(
            GraderSessionPolicy.isCandidatePage(
                "https://grader-hitsz-edu-cn-s.hitsz.edu.cn/", GraderSessionPolicy.Entry.GATEWAY,
            )
        )
        assertFalse(GraderSessionPolicy.isCandidatePage("https://grader.hitsz.edu.cn/api/auth/callback"))
    }

    @Test
    fun `entry start urls differ only where the gateway requires it`() {
        assertEquals("https://grader.hitsz.edu.cn/api/login/sso", GraderSessionPolicy.Entry.DIRECT.startUrl)
        assertEquals("https://grader-hitsz-edu-cn-s.hitsz.edu.cn/home", GraderSessionPolicy.Entry.GATEWAY.startUrl)
    }
}
