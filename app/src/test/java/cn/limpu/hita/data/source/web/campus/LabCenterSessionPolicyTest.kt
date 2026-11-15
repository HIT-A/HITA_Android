package cn.limpu.hita.data.source.web.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实验中心会话判定。
 *
 * 关键边界：服务端在会话过期时会返回 200 的登录壳页面，URL 仍停在课表页上，
 * 因此「登录完成」必须同时看 URL 与页面内容，不能只看 URL。
 */
class LabCenterSessionPolicyTest {

    private val scheduleHtml = """
        <html><body>
          <select id="termId"><option value="2013" selected>2025-2026学年第一学期</option></select>
          <script>$.post('myScheduleInfoAjax', {termId: 2013, week: ''})</script>
        </body></html>
    """.trimIndent()

    private val loginHtml = """
        <html><body><form action="/hitszlims/webapp/login"></form></body></html>
    """.trimIndent()

    @Test
    fun `only https on the lab host is accepted`() {
        assertTrue(LabCenterSessionPolicy.isLabUrl("https://lab.hitsz.edu.cn/hitszlims/test"))
        assertFalse(LabCenterSessionPolicy.isLabUrl("http://lab.hitsz.edu.cn/hitszlims/test"))
        assertFalse(LabCenterSessionPolicy.isLabUrl("https://lab.hitsz.edu.cn.evil.invalid/x"))
        assertFalse(LabCenterSessionPolicy.isLabUrl("https://lab.hitsz.edu.cn:8443/x"))
        assertFalse(LabCenterSessionPolicy.isLabUrl("https://example.invalid/"))
        assertFalse(LabCenterSessionPolicy.isLabUrl("not a url"))
    }

    @Test
    fun `observed lab UAA redirect can reach campus CAS without broadening business URL policy`() {
        val cas = "https://ids.hit.edu.cn/authserver/login?service=https%3A%2F%2Flab.hitsz.edu.cn%2Fuaa%2Flogin"
        assertTrue(LabCenterSessionPolicy.isLoginNavigationUrl(cas))
        assertFalse(LabCenterSessionPolicy.isLabUrl(cas))
        assertTrue(LabCenterSessionPolicy.isLoginNavigationUrl(LabCenterSessionPolicy.ENTRY_URL))
        assertTrue(LabCenterSessionPolicy.isLoginTransitPage("https://lab.hitsz.edu.cn/uaa/hitsz/login"))
        listOf(
            "http://ids.hit.edu.cn/authserver/login",
            "https://ids.hit.edu.cn.evil.invalid/authserver/login",
            "https://user@ids.hit.edu.cn/authserver/login",
            "https://ids.hit.edu.cn:8443/authserver/login",
            "https://ids.hit.edu.cn/unrelated",
        ).forEach { assertFalse(LabCenterSessionPolicy.isLoginNavigationUrl(it)) }
    }

    @Test
    fun `login transit pages are recognized`() {
        assertTrue(LabCenterSessionPolicy.isLoginTransitPage("https://lab.hitsz.edu.cn/hitszlims/login"))
        assertTrue(LabCenterSessionPolicy.isLoginTransitPage("https://lab.hitsz.edu.cn/hitszlims/webapp/login"))
        assertTrue(
            LabCenterSessionPolicy.isLoginTransitPage(
                "https://lab.hitsz.edu.cn/uaa/oauth/authorize?client_id=x"
            )
        )
        assertFalse(
            LabCenterSessionPolicy.isLoginTransitPage(
                "https://lab.hitsz.edu.cn/hitszlims/schedule/myScheduleInfo"
            )
        )
    }

    @Test
    fun `schedule page with term select means login is complete`() {
        assertTrue(
            LabCenterSessionPolicy.isLoginComplete(
                "https://lab.hitsz.edu.cn/hitszlims/schedule/myScheduleInfo",
                scheduleHtml,
            )
        )
    }

    @Test
    fun `login shell served with two hundred is not logged in`() {
        assertFalse(
            LabCenterSessionPolicy.isLoginComplete(
                "https://lab.hitsz.edu.cn/hitszlims/schedule/myScheduleInfo",
                loginHtml,
            )
        )
    }

    @Test
    fun `foreign pages never count as logged in`() {
        assertFalse(LabCenterSessionPolicy.isLoginComplete("https://example.invalid/x", scheduleHtml))
        assertFalse(
            LabCenterSessionPolicy.isLoginComplete("https://lab.hitsz.edu.cn/hitszlims/login", scheduleHtml)
        )
    }

    @Test
    fun `page markers are delegated to the html parser`() {
        assertTrue(LabCenterSessionPolicy.isSchedulePage(scheduleHtml))
        assertTrue(LabCenterSessionPolicy.isLoginPage(loginHtml))
        assertFalse(LabCenterSessionPolicy.isLoginPage(scheduleHtml))
    }

    @Test
    fun `cookie header is forwarded verbatim when healthy`() {
        val header = "JSESSIONID=abc123; OAUTH2COOKIE=def456"
        assertEquals(header, LabCenterSessionPolicy.cookieHeader(header))
    }

    @Test
    fun `empty or corrupted cookie headers are rejected`() {
        assertNull(LabCenterSessionPolicy.cookieHeader(""))
        assertNull(LabCenterSessionPolicy.cookieHeader("   "))
        assertNull(LabCenterSessionPolicy.cookieHeader("JSESSIONID="))
        assertNull(LabCenterSessionPolicy.cookieHeader("JSESSIONID=ab\u0000cd"))
        assertNull(LabCenterSessionPolicy.cookieHeader("novalue"))
    }

    @Test
    fun `entry and schedule urls point at the documented endpoints`() {
        assertEquals(
            "https://lab.hitsz.edu.cn/hitszlims/test?PROJECT_NAME=hitszlims",
            LabCenterSessionPolicy.ENTRY_URL,
        )
        assertEquals(
            "https://lab.hitsz.edu.cn/hitszlims/schedule/myScheduleInfo",
            LabCenterSessionPolicy.SCHEDULE_URL,
        )
        assertEquals(
            "https://lab.hitsz.edu.cn/hitszlims/schedule/myScheduleInfoAjax",
            LabCenterSessionPolicy.SCHEDULE_AJAX_URL,
        )
        assertNotNull(LabCenterSessionPolicy.ORIGIN)
    }
}
