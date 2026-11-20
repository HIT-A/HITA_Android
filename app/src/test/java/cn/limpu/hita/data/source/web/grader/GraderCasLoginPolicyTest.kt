package cn.limpu.hita.data.source.web.grader

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraderCasLoginPolicyTest {
    @Test
    fun `main-frame navigation permits only HTTPS Grader and HIT CAS origins`() {
        assertTrue(GraderCasLoginPolicy.mayNavigate(GraderSessionPolicy.LOGIN_URL))
        assertTrue(GraderCasLoginPolicy.mayNavigate("https://ids.hit.edu.cn:443/authserver/mfa"))
        for (url in listOf(
            "http://ids.hit.edu.cn/authserver/login",
            "https://ids.hit.edu.cn:444/authserver/login",
            "https://ids.hit.edu.cn@external.invalid/authserver/login",
            "https://a@ids.hit.edu.cn/authserver/login",
            "https://ids.hit.edu.cn.external.invalid/authserver/login",
            "https://grader.hitsz.edu.cn.external.invalid/home",
            "https://ids.hit.edu.cn./authserver/login",
            "file:///authserver/login",
            "javascript:alert(1)",
        )) assertFalse(url, GraderCasLoginPolicy.mayNavigate(url))
    }

    @Test
    fun `a rendered CAS retry is a login endpoint even if it cannot receive app credentials`() {
        val retry = "$CAS_LOGIN?error=1"
        assertTrue(GraderCasLoginPolicy.isLoginEndpoint(retry))
        assertNull(GraderCasLoginPolicy.loginService(retry))
        assertFalse(GraderCasLoginPolicy.isLoginEndpoint("https://ids.hit.edu.cn/authserver/mfa"))
        assertFalse(GraderCasLoginPolicy.isLoginEndpoint("https://grader.hitsz.edu.cn/authserver/login"))
    }

    @Test
    fun `account tab preserves the same verified service and OAuth state`() {
        val service = service(state = "A+/=nested")
        val landing = login(service)
        val tab = requireNotNull(GraderCasLoginPolicy.accountTabUrl(landing))
        assertEquals(service, GraderCasLoginPolicy.loginService(landing))
        assertEquals(service, GraderCasLoginPolicy.loginService(tab))
        assertNull(GraderCasLoginPolicy.accountTabUrl(tab))
    }

    @Test
    fun `credential injection needs the exact account login page and a single service`() {
        val service = service()
        val good = login(service)
        for (url in listOf(
            good.replace("https:", "http:"),
            good.replace("/authserver/login", "/unexpected/authserver/login"),
            good.replace("/authserver/login", "/authserver/%6cogin"),
            "$good#anything",
            "$good&service=${encode(service)}",
            "$good&%73ervice=${encode(service)}",
            "$good&type=dynamicLogin",
            "$good&type=userNameLogin&type=userNameLogin",
            "$good&notObserved=1",
            "$CAS_LOGIN?type=userNameLogin",
        )) {
            assertNull(url, GraderCasLoginPolicy.loginService(url))
            assertNull(url, GraderCasLoginPolicy.accountTabUrl(url))
        }
    }

    @Test
    fun `nested service only authorizes the official Grader OAuth client and exact callback`() {
        val official = service()
        for (unapproved in listOf(
            official.replace("https://ids.hit.edu.cn", "https://ids.hit.edu.cn.external.invalid"),
            official.replace("oauth2.0/callbackAuthorize", "oauth2.0/other"),
            official.replace("client_id=1463851627464626176", "client_id=other"),
            official.replace("response_type=code", "response_type=token"),
            official.replace("client_name=CasOAuthClient", "client_name=Other"),
            official.replace("state=original-state", "state="),
            "$official&state=second",
            "$official&redirect_uri=${encode(CALLBACK)}",
            "$official#fragment",
            service(callback = "http://grader.hitsz.edu.cn/api/auth/callback"),
            service(callback = "https://grader.hitsz.edu.cn.external.invalid/api/auth/callback"),
            service(callback = "$CALLBACK/"),
            service(callback = "$CALLBACK?redirect=https://external.invalid"),
            service(callback = encode(CALLBACK)),
        )) assertNull(unapproved, GraderCasLoginPolicy.loginService(login(unapproved)))
    }

    @Test
    fun `form action must return to the identical CAS service rather than any valid Grader OAuth attempt`() {
        val service = service()
        val page = login(service) + "&type=userNameLogin"
        val action = login(service)
        assertTrue(GraderCasLoginPolicy.matchesFormAction(page, action))
        assertTrue(GraderCasLoginPolicy.matchesFormAction(page, action.replace("https://ids.hit.edu.cn", "https://ids.hit.edu.cn:443")))
        for (wrongAction in listOf(
            CAS_LOGIN,
            login(service(state = "different-state")),
            "$action&service=${encode(service)}",
            "$action&type=dynamicLogin",
            action.replace("/authserver/login", "/authserver/another"),
            action.replace("https://ids.hit.edu.cn", "https://grader.hitsz.edu.cn"),
            action.replace("https://ids.hit.edu.cn", "https://ids.hit.edu.cn@other.invalid"),
        )) assertFalse(wrongAction, GraderCasLoginPolicy.matchesFormAction(page, wrongAction))
        assertFalse(GraderCasLoginPolicy.matchesFormAction(GraderSessionPolicy.LOGIN_URL, action))
    }

    private fun login(service: String): String = "$CAS_LOGIN?service=${encode(service)}"

    private fun service(callback: String = CALLBACK, state: String = "original-state"): String =
        "https://ids.hit.edu.cn/authserver/oauth2.0/callbackAuthorize?client_id=1463851627464626176" +
            "&redirect_uri=${encode(callback)}&response_type=code&state=${encode(state)}&client_name=CasOAuthClient"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val CAS_LOGIN = "https://ids.hit.edu.cn/authserver/login"
        const val CALLBACK = "https://grader.hitsz.edu.cn/api/auth/callback"
    }
}
