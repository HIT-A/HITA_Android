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
    fun `weihai direct academic login form is autofillable`() {
        // 校园网直连时，教务系统自己渲染登录表单，不经过 CAS。
        assertTrue(
            EasWebCredentialPolicy.matchesLoginPage(
                EASToken.Campus.WEIHAI,
                "http://jwts.hitwh.edu.cn/xtgl/login_slogin.html",
                isMainFrame = true,
            )
        )
        assertTrue(
            EasWebCredentialPolicy.matchesLoginPage(
                EASToken.Campus.WEIHAI,
                "https://yjsgl.hitwh.edu.cn/xtgl/login_slogin.html",
                isMainFrame = true,
            )
        )
        // 教务系统的业务页不是登录页，不应被当作表单自动填充。
        assertFalse(
            EasWebCredentialPolicy.matchesLoginPage(
                EASToken.Campus.WEIHAI,
                "http://jwts.hitwh.edu.cn/xtgl/index_initMenu.html",
                isMainFrame = true,
            )
        )
        // 其它校区不受这条扩展影响。
        assertFalse(
            EasWebCredentialPolicy.matchesLoginPage(
                EASToken.Campus.BENBU,
                "http://jwts.hitwh.edu.cn/xtgl/login_slogin.html",
                isMainFrame = true,
            )
        )
    }

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
        // 深圳 CAS 也要能拿到账号密码并自动填充：三校区共用同一条无感登录路径
        assertTrue(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.SHENZHEN,
            "https://ids-hit-edu-cn-s.hitsz.edu.cn/authserver/login?service=x",
            true
        ))
        assertFalse(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.SHENZHEN,
            "https://trust.hitsz.edu.cn/portal/#/login",
            true
        ))
        assertFalse(EasWebCredentialPolicy.matchesLoginPage(
            EASToken.Campus.SHENZHEN,
            "https://ids-hit-edu-cn-s.hitsz.edu.cn/authserver/login",
            false
        ))
    }

    @Test
    fun `autofill and submit script is self contained and submits once`() {
        val script = EasWebCredentialPolicy.autofillAndSubmitScript("2024311228", "p@ss\"word")
        // 脚本必须是单个可执行 IIFE，且凭据被正确转义（不能拼出非法 JS）
        assertTrue(script.trimStart().startsWith("(function()"))
        assertTrue(script.trimEnd().endsWith("})();"))
        assertTrue(script.contains("p@ss\\\"word"))
        assertFalse(script.contains("${'$'}{fillFormJs}"))
        assertTrue(script.contains("fillForms(username, password)"))
        assertTrue(script.contains("requestSubmit"))
    }

    @Test
    fun `submit result parsing drives silent renewal decisions`() {
        // WebView 回调是 JSON 字面量；静默续期只有真的提交出去了才能继续等待登录结果。
        assertTrue(EasWebCredentialPolicy.submittedFromResult("""{"filled":true,"submitted":true}"""))
        assertTrue(
            EasWebCredentialPolicy.submittedFromResult(
                """"{\"filled\":true,\"submitted\":true,\"via\":\"button\"}""""
            )
        )
        assertFalse(EasWebCredentialPolicy.submittedFromResult("""{"filled":false,"submitted":false}"""))
        assertFalse(EasWebCredentialPolicy.submittedFromResult("""{"filled":true,"submitted":false}"""))
        assertFalse(EasWebCredentialPolicy.submittedFromResult("null"))
        assertFalse(EasWebCredentialPolicy.submittedFromResult(null))
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
