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
}
