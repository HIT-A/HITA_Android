package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.preference.EasCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasWebCredentialPolicyTest {
    @Test
    fun `remember me script covers both native checkbox and custom control`() {
        val script = EasWebCredentialPolicy.rememberMeScript()
        // 原生 checkbox 与自定义 label 两条路径都要覆盖，否则某些校区勾不上
        assertTrue(script.contains("input[type=\"checkbox\"]"))
        assertTrue(script.contains("免登录"))
        assertTrue(script.contains("记住我"))
        // 必须回填 checked 状态并派发 change，页面 JS 才会把 rememberMe 一并提交
        assertTrue(script.contains("dispatchEvent"))
        assertTrue(script.contains("'change'"))
        // 已勾选时不得再翻转，否则会把用户/上轮的勾选点掉
        assertTrue(script.contains("if (input.checked) return true;"))
    }

    @Test
    fun `remember me script is idempotent across repeated injection`() {
        val script = EasWebCredentialPolicy.rememberMeScript()
        // 多轮注入时只在未勾选时写状态，保证重复执行不会取消勾选
        assertFalse(script.contains("input.checked = false"))
        assertFalse(script.contains("setter.call(input, false)"))
    }

    @Test
    fun `allows only campus login origins and main frame`() {
        assertTrue(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.BENBU,
            "http://ids-hit-edu-cn-s.vpn.hit.edu.cn/authserver/login",
            true
        ))
        assertTrue(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.BENBU,
            "http://ids-hit-edu-cn-s.ivpn.hit.edu.cn/authserver/login",
            true
        ))
        assertTrue(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.BENBU,
            "https://ids.hit.edu.cn/authserver/login?service=jwts",
            true
        ))
        assertTrue(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.WEIHAI,
            "https://webvpn.hitwh.edu.cn/authserver/login",
            true
        ))
        assertTrue(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.WEIHAI,
            "https://ids.hit.edu.cn/authserver/login",
            true
        ))
        assertFalse(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.WEIHAI,
            "https://webvpn.hitwh.edu.cn/authserver/login",
            false
        ))
    }

    @Test
    fun `rejects wrong scheme host suffix and path`() {
        listOf(
            EASToken.Campus.BENBU to "https://ids-hit-edu-cn-s.ivpn.hit.edu.cn/authserver/login",
            EASToken.Campus.BENBU to "https://ids.hit.edu.cn.attacker.invalid/authserver/login",
            EASToken.Campus.WEIHAI to "http://webvpn.hitwh.edu.cn/authserver/login",
            EASToken.Campus.WEIHAI to "https://ids.hit.edu.cn.evil/authserver/login",
            EASToken.Campus.WEIHAI to "https://ids.hit.edu.cn/not-authserver/login",
            EASToken.Campus.WEIHAI to "https://ids.hit.edu.cn:8443/authserver/login",
            EASToken.Campus.WEIHAI to "https://user@ids.hit.edu.cn/authserver/login"
        ).forEach { (campus, url) ->
            assertFalse(url, EasWebCredentialPolicy.matchesLoginPage(campus, url, true))
        }
    }

    @Test
    fun `candidate commits only after success and discard prevents save`() {
        val state = EasWebCredentialCaptureState()
        val candidate = EasCredential(EASToken.Campus.BENBU, "student", "secret")
        var saved: EasCredential? = null
        state.stage(candidate)
        state.commitOnSuccess { saved = it }
        assertEquals(candidate, saved)

        state.stage(candidate)
        state.discard()
        saved = null
        state.commitOnSuccess { saved = it }
        assertNull(saved)
    }

    @Test
    fun `credential storage read errors preserve manual login`() {
        assertNull(EasWebCredentialPolicy.readOptionalCredential { error("keystore unavailable") })
    }

    @Test
    fun `autofill script never submits the login form`() {
        val script = EasWebCredentialPolicy.autofillScript("student", "secret")
        assertFalse(script.contains("requestSubmit"))
        assertFalse(script.contains(".submit()"))
        assertFalse(script.contains(".click()"))
        assertTrue(script.contains("dispatchEvent"))
        assertTrue(script.contains("getClientRects"))
        assertTrue(script.contains("HTMLInputElement.prototype"))
    }
}
