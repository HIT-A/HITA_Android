package cn.limpu.hita.ui.eas.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenzhenTrustPortalAutoLoginTest {
    @Test
    fun `only the aTrust shortcut page needs the force-web flag`() {
        assertTrue(
            ShenzhenTrustPortalAutoLogin.shouldForceWebMode(
                "https://trust.hitsz.edu.cn/portal/shortcut.html?dest=%23%21%2Flogin&appUrl=x&t=y"
            )
        )
        for (url in listOf(
            "http://trust.hitsz.edu.cn/portal/shortcut.html?dest=%23%21%2Flogin",
            "https://trust.hitsz.edu.cn/portal/",
            "https://trust.hitsz.edu.cn/portal/#/login",
            "https://trust.hitsz.edu.cn.evil.invalid/portal/shortcut.html",
            "https://grader-hitsz-edu-cn-s.hitsz.edu.cn/home",
        )) assertFalse(url, ShenzhenTrustPortalAutoLogin.shouldForceWebMode(url))
    }

    @Test
    fun `force-web script writes the legacy key and the prefixed one`() {
        val script = ShenzhenTrustPortalAutoLogin.buildForceWebScript()
        assertTrue(script.contains("sessionStorage.setItem('forceWeb', 'true')"))
        assertTrue(script.contains("__Prefix_map__"))
        assertTrue(script.contains("entry.pre + 'forceWeb'"))
    }

    @Test
    fun `portal login page alone triggers the auto click`() {
        assertTrue(
            ShenzhenTrustPortalAutoLogin.shouldAutoLogin(
                "https://trust.hitsz.edu.cn/portal/?redirectid=abc#!/login"
            )
        )
        assertFalse(
            ShenzhenTrustPortalAutoLogin.shouldAutoLogin(
                "https://trust.hitsz.edu.cn/portal/shortcut.html?dest=%23%21%2Flogin"
            )
        )
    }
}
