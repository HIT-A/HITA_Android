package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录 WebView 的导航白名单：既要挡住任意地址，又不能挡住三校区现有的认证落点。
 * 每个放行用例都对应仓库里真实存在的 URL 常量或服务端跳转。
 */
class WebViewSecurityPolicyTest {

    @Test
    fun `benbu zero trust gateway and cas gateway stay reachable`() {
        listOf(
            "http://i-hit-edu-cn.vpn.hit.edu.cn:1080/portal/home/",
            "http://jwts-hit-edu-cn.vpn.hit.edu.cn:1080/loginCAS",
            "http://eelabinfo-hit-edu-cn.vpn.hit.edu.cn:1080/api/cas/loginSuccess",
            "http://ids-hit-edu-cn-s.vpn.hit.edu.cn/authserver/login",
            "http://ids-hit-edu-cn-s.ivpn.hit.edu.cn/authserver/login",
            "https://ids.hit.edu.cn/authserver/login?service=jwts"
        ).forEach { url ->
            assertTrue(url, WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.BENBU, url))
        }
    }

    @Test
    fun `weihai webvpn and cas stay reachable`() {
        listOf(
            "https://webvpn.hitwh.edu.cn/",
            "https://webvpn.hitwh.edu.cn/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667/loginCAS",
            "https://ids.hit.edu.cn/authserver/login"
        ).forEach { url ->
            assertTrue(url, WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.WEIHAI, url))
        }
    }

    @Test
    fun `shenzhen atrust cas and academic hosts stay reachable`() {
        listOf(
            "https://ids-hit-edu-cn-s.hitsz.edu.cn/authserver/login",
            "https://jw-hitsz-edu-cn.hitsz.edu.cn/authentication/main",
            "https://jw.hitsz.edu.cn/",
            "https://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053"
        ).forEach { url ->
            assertTrue(url, WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.SHENZHEN, url))
        }
    }

    @Test
    fun `lookalike host suffixes are rejected`() {
        listOf(
            "https://ids.hit.edu.cn.attacker.invalid/authserver/login",
            "https://evilhit.edu.cn/authserver/login",
            "https://hitsz.edu.cn.attacker.invalid/",
            "https://attacker.invalid/?next=https://ids.hit.edu.cn"
        ).forEach { url ->
            assertFalse(url, WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.SHENZHEN, url))
        }
    }

    @Test
    fun `non http schemes are rejected`() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///data/data/cn.limpu.hita/shared_prefs/local_eas_credentials.xml",
            "javascript:alert(document.cookie)",
            "data:text/html,<script>fetch('/')</script>",
            "content://com.android.providers.media.documents/document/image%3A42"
        ).forEach { url ->
            assertFalse(url, WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.BENBU, url))
        }
    }

    @Test
    fun `userinfo trick is rejected`() {
        assertFalse(
            WebViewSecurityPolicy.isAllowedNavigation(
                EASToken.Campus.SHENZHEN,
                "https://ids.hit.edu.cn@attacker.invalid/authserver/login"
            )
        )
    }

    @Test
    fun `blank and malformed values are rejected`() {
        assertFalse(WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.BENBU, null))
        assertFalse(WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.BENBU, ""))
        assertFalse(WebViewSecurityPolicy.isAllowedNavigation(EASToken.Campus.BENBU, "://"))
    }

    @Test
    fun `popup forwarding uses the same allow list as main frame navigation`() {
        assertTrue(
            WebViewSecurityPolicy.isAllowedPopupForward(
                EASToken.Campus.BENBU,
                "https://ids.hit.edu.cn/authserver/login"
            )
        )
        assertFalse(
            WebViewSecurityPolicy.isAllowedPopupForward(
                EASToken.Campus.BENBU,
                "https://attacker.invalid/collect"
            )
        )
    }

    @Test
    fun `cleartext detection matches only http scheme`() {
        assertTrue(WebViewSecurityPolicy.isCleartextNavigation("http://jwts.hit.edu.cn/loginCAS"))
        assertFalse(WebViewSecurityPolicy.isCleartextNavigation("https://jwts.hit.edu.cn/loginCAS"))
        assertFalse(WebViewSecurityPolicy.isCleartextNavigation(null))
    }
}
