package cn.limpu.hita.data.source.web.notice

import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoPortalLoginPolicyTest {

    @Test
    fun `portal pages are matched by exact host and scheme`() {
        assertTrue(InfoPortalLoginPolicy.isPortalPage("http://info.hitsz.edu.cn/"))
        assertTrue(
            InfoPortalLoginPolicy.isPortalPage(
                "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053"
            )
        )
        for (url in listOf(
            "http://info.hitsz.edu.cn.attacker.invalid/list.jsp",
            "http://evil.invalid/?next=http://info.hitsz.edu.cn/",
            "ftp://info.hitsz.edu.cn/",
            "http://info.hitsz.edu.cn:8080/list.jsp",
            "",
        )) {
            assertFalse(url, InfoPortalLoginPolicy.isPortalPage(url))
        }
    }

    @Test
    fun `notice list page requires list jsp on the portal`() {
        assertTrue(
            InfoPortalLoginPolicy.isNoticeListPage(
                "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053"
            )
        )
        assertFalse(InfoPortalLoginPolicy.isNoticeListPage("http://info.hitsz.edu.cn/"))
        assertFalse(
            InfoPortalLoginPolicy.isNoticeListPage(
                "https://ids.hit.edu.cn/authserver/login?service=list.jsp"
            )
        )
    }

    @Test
    fun `cas transit pages are not treated as logged in`() {
        // 门户未登录时先 302 到自己的 caslogin 中转，再跳统一身份认证。
        assertTrue(
            InfoPortalLoginPolicy.isLoginTransitPage(
                "http://info.hitsz.edu.cn/system/resource/code/auth/caslogin.jsp?owner=1834232703"
            )
        )
        assertTrue(
            InfoPortalLoginPolicy.isLoginTransitPage(
                "https://ids.hit.edu.cn/authserver/login?service=http%3A%2F%2Finfo.hitsz.edu.cn%2F"
            )
        )
        assertFalse(
            InfoPortalLoginPolicy.isLoginTransitPage(
                "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053"
            )
        )
    }

    @Test
    fun `only the cas form allows automatic submit`() {
        assertTrue(
            InfoPortalLoginPolicy.isCasLoginForm(
                "https://ids.hit.edu.cn/authserver/login?service=http%3A%2F%2Finfo.hitsz.edu.cn%2F"
            )
        )
        // 多因子页面必须交回用户，不能自动提交。
        assertFalse(
            InfoPortalLoginPolicy.isCasLoginForm(
                "https://ids.hit.edu.cn/authserver/reAuthCheck/verify"
            )
        )
        assertFalse(InfoPortalLoginPolicy.isCasLoginForm("http://info.hitsz.edu.cn/list.jsp"))
    }

    @Test
    fun `portal session needs the portal own jsessionid`() {
        assertFalse(InfoPortalLoginPolicy.hasPortalSession(emptyMap()))
        // CAS 票据属于 ids.hit.edu.cn，单独存在不代表门户已放行。
        assertFalse(InfoPortalLoginPolicy.hasPortalSession(mapOf("CASTGC" to "TGT-1")))
        assertTrue(InfoPortalLoginPolicy.hasPortalSession(mapOf("JSESSIONID" to "abc")))
        assertTrue(InfoPortalLoginPolicy.hasPortalSession(mapOf("jsessionid" to "abc")))
        // 旧部署用 iPlanetDirectoryPro 作为身份票据，历史判定也认它，保留兼容。
        assertTrue(
            InfoPortalLoginPolicy.hasPortalSession(mapOf("iPlanetDirectoryPro" to "token"))
        )
        // 空值不算建立会话。
        assertFalse(InfoPortalLoginPolicy.hasPortalSession(mapOf("JSESSIONID" to " ")))
    }

    @Test
    fun `login completes only on the notice list with a live session`() {
        val cookies = mapOf("JSESSIONID" to "abc")
        assertTrue(
            InfoPortalLoginPolicy.isLoginComplete(
                "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053",
                cookies,
            )
        )
        assertFalse(InfoPortalLoginPolicy.isLoginComplete("http://info.hitsz.edu.cn/", cookies))
        assertFalse(
            InfoPortalLoginPolicy.isLoginComplete(
                "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053",
                emptyMap(),
            )
        )
    }

    @Test
    fun `credential campus follows the actual cas host`() {
        assertEquals(
            EASToken.Campus.BENBU,
            InfoPortalLoginPolicy.credentialCampusFor(
                "https://ids.hit.edu.cn/authserver/login?service=http%3A%2F%2Finfo.hitsz.edu.cn%2F"
            )
        )
        // 深圳 aTrust 放行后的 CAS。白名单只认 SHENZHEN 的 host，用 BENBU 会永远匹配不上。
        assertEquals(
            EASToken.Campus.SHENZHEN,
            InfoPortalLoginPolicy.credentialCampusFor(
                "https://ids-hit-edu-cn-s.hitsz.edu.cn/authserver/login?service=..."
            )
        )
        assertEquals(
            EASToken.Campus.WEIHAI,
            InfoPortalLoginPolicy.credentialCampusFor("https://webvpn.hitwh.edu.cn/cas")
        )
        // 未知或被伪造的 host 一律退回本部，交由 origin 白名单拒绝。
        assertEquals(
            EASToken.Campus.BENBU,
            InfoPortalLoginPolicy.credentialCampusFor("https://evil.invalid/authserver/login")
        )
        assertEquals(EASToken.Campus.BENBU, InfoPortalLoginPolicy.credentialCampusFor("not a url"))
    }
}
