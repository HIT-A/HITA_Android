package cn.limpu.hita.data.source.web.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园卡登录态判定。
 *
 * 页面只在「停在门户前端 + 该 origin 有 Cookie」时才算已登录；停在统一身份认证
 * 或 `login_cas` 回跳点都必须判未登录，不能凭「URL 看起来像门户」就放行。
 */
class CampusCardSessionPolicyTest {

    private val portalCookies = setOf("SESSION", "route")

    @Test
    fun `portal page with cookies is signed in`() {
        assertEquals(
            CampusCardSessionPolicy.State.SignedIn,
            CampusCardSessionPolicy.state("https://dxcdp.hitsz.edu.cn/portal/", portalCookies),
        )
        assertEquals(
            CampusCardSessionPolicy.State.SignedIn,
            CampusCardSessionPolicy.state("https://dxcdp.hitsz.edu.cn/portal/#/index", portalCookies),
        )
    }

    @Test
    fun `portal page without cookies is still unknown`() {
        assertEquals(
            CampusCardSessionPolicy.State.Unknown,
            CampusCardSessionPolicy.state("https://dxcdp.hitsz.edu.cn/portal/", emptySet()),
        )
    }

    @Test
    fun `cas login page is signed out`() {
        assertEquals(
            CampusCardSessionPolicy.State.SignedOut,
            CampusCardSessionPolicy.state(CampusCardSessionPolicy.CAS_LOGIN_URL, emptySet()),
        )
        assertEquals(
            CampusCardSessionPolicy.State.SignedOut,
            CampusCardSessionPolicy.state("https://ids.hit.edu.cn/authserver/reAuthCheck/doLogin.do", portalCookies),
        )
        assertEquals(
            CampusCardSessionPolicy.State.SignedOut,
            CampusCardSessionPolicy.state("https://ids-hit-edu-cn-s.hitsz.edu.cn/authserver/login", portalCookies),
        )
    }

    @Test
    fun `cas callback endpoint is signed out even with cookies`() {
        assertEquals(
            CampusCardSessionPolicy.State.SignedOut,
            CampusCardSessionPolicy.state(
                "https://dxcdp.hitsz.edu.cn/portal-api/login_cas?ticket=ST-1",
                portalCookies,
            ),
        )
    }

    @Test
    fun `trust page is signed out`() {
        assertEquals(
            CampusCardSessionPolicy.State.SignedOut,
            CampusCardSessionPolicy.state("https://trust.hitsz.edu.cn/relay?id=1", portalCookies),
        )
    }

    @Test
    fun `non campus hosts and plain http are unknown`() {
        assertEquals(
            CampusCardSessionPolicy.State.Unknown,
            CampusCardSessionPolicy.state("http://dxcdp.hitsz.edu.cn/portal/", portalCookies),
        )
        assertEquals(
            CampusCardSessionPolicy.State.Unknown,
            CampusCardSessionPolicy.state("https://dxcdp.hitsz.edu.cn.evil.invalid/portal/", portalCookies),
        )
        assertEquals(
            CampusCardSessionPolicy.State.Unknown,
            CampusCardSessionPolicy.state("https://user@dxcdp.hitsz.edu.cn/portal/", portalCookies),
        )
        assertEquals(
            CampusCardSessionPolicy.State.Unknown,
            CampusCardSessionPolicy.state("about:blank", portalCookies),
        )
    }

    @Test
    fun `api endpoint is not a user page`() {
        assertEquals(
            CampusCardSessionPolicy.State.Unknown,
            CampusCardSessionPolicy.state(
                "https://dxcdp.hitsz.edu.cn/portal-api/index/getCountYkt",
                portalCookies,
            ),
        )
    }

    @Test
    fun `ticket is read from query and fragment`() {
        assertEquals(
            "T-001",
            CampusCardSessionPolicy.portalTicket("https://dxcdp.hitsz.edu.cn/portal/?t=T-001"),
        )
        assertEquals(
            "T-002",
            CampusCardSessionPolicy.portalTicket("https://dxcdp.hitsz.edu.cn/portal/#/index?t=T-002"),
        )
        assertEquals(
            "T-003",
            CampusCardSessionPolicy.portalTicket(
                "https://dxcdp.hitsz.edu.cn/portal/#/home?lang=zh&t=T-003&x=1",
            ),
        )
        assertNull(CampusCardSessionPolicy.portalTicket("https://dxcdp.hitsz.edu.cn/portal/"))
        assertNull(CampusCardSessionPolicy.portalTicket("https://ids.hit.edu.cn/portal/?t=T-004"))
    }

    @Test
    fun `ticket from insecure or unrelated paths is rejected`() {
        listOf(
            "http://dxcdp.hitsz.edu.cn/portal/?t=secret",
            "https://user@dxcdp.hitsz.edu.cn/portal/?t=secret",
            "https://dxcdp.hitsz.edu.cn:8443/portal/?t=secret",
            "https://dxcdp.hitsz.edu.cn/other/?t=secret",
        ).forEach { assertNull(CampusCardSessionPolicy.portalTicket(it)) }
    }

    @Test
    fun `ticket is percent decoded`() {
        assertEquals(
            "A B",
            CampusCardSessionPolicy.portalTicket("https://dxcdp.hitsz.edu.cn/portal/?t=A%20B"),
        )
    }

    @Test
    fun `ticket is extracted from html redirect scripts`() {
        assertEquals(
            "T-100",
            CampusCardSessionPolicy.portalTicketInHtml(
                """<script>location.replace("https://dxcdp.hitsz.edu.cn/portal/#/index?t=T-100")</script>""",
            ),
        )
        assertEquals(
            "T-101",
            CampusCardSessionPolicy.portalTicketInHtml(
                """<a href="/portal/home?t=T-101">继续</a>""",
            ),
        )
        assertNull(CampusCardSessionPolicy.portalTicketInHtml("<html>登录</html>"))
    }

    @Test
    fun `cookie names ignore empty values and control bytes`() {
        assertEquals(
            // route 也是门户下发的真实 Cookie（负载均衡粘性），必须一并识别。
            setOf("SESSION", "route", "CASTGC"),
            CampusCardSessionPolicy.cookieNames("SESSION=abc; route=1; CASTGC=TGT-1"),
        )
        assertEquals(emptySet<String>(), CampusCardSessionPolicy.cookieNames("SESSION=; route="))
        assertEquals(emptySet<String>(), CampusCardSessionPolicy.cookieNames("SESSION=ab\u0000cd"))
        assertEquals(emptySet<String>(), CampusCardSessionPolicy.cookieNames(""))
    }

    @Test
    fun `cas ticket detection only accepts CASTGC`() {
        assertTrue(CampusCardSessionPolicy.hasTicket("CASTGC=TGT-1; JSESSIONID=x"))
        assertFalse(CampusCardSessionPolicy.hasTicket("JSESSIONID=x"))
        assertFalse(CampusCardSessionPolicy.hasTicket(""))
    }

    @Test
    fun `origin keeps scheme host and explicit port`() {
        assertEquals(
            "https://dxcdp.hitsz.edu.cn",
            CampusCardSessionPolicy.originOf("https://dxcdp.hitsz.edu.cn/portal-api/ssologin?t=x"),
        )
        assertEquals(
            "https://dxcdp.hitsz.edu.cn",
            CampusCardSessionPolicy.originOf("https://dxcdp.hitsz.edu.cn:443/portal/"),
        )
        assertEquals("", CampusCardSessionPolicy.originOf("not a url"))
    }

    @Test
    fun `login entry points at the campus cas with portal callback`() {
        assertTrue(CampusCardSessionPolicy.CAS_LOGIN_URL.startsWith("https://ids.hit.edu.cn/authserver/login"))
        assertTrue(
            CampusCardSessionPolicy.CAS_LOGIN_URL
                .contains("service=https%3A%2F%2Fdxcdp.hitsz.edu.cn%3A443%2Fportal-api%2Flogin_cas"),
        )
        assertEquals("https://dxcdp.hitsz.edu.cn", CampusCardSessionPolicy.PORTAL_ORIGIN)
        assertTrue(CampusCardClient.API_BASE.endsWith("/portal-api"))
    }
}
