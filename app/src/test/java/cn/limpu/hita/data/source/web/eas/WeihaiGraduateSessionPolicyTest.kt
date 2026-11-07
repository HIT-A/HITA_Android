package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.*
import org.junit.Test

class WeihaiGraduateSessionPolicyTest {
    @Test fun graduateIdentityRequiresVerifiedStudentAndRoleButNotName() {
        val profile = WeihaiGraduateSessionPolicy.parseProfile(
            """{"yhdm":"  synthetic-grad  ","username":"other","pylx":"2","sfxs":"1","rolecode":["02"]}"""
        )
        assertEquals("synthetic-grad", profile?.account)
        assertNull(profile?.name)
        assertEquals("fallback", WeihaiGraduateSessionPolicy.parseProfile(
            """{"yhdm":" ","username":"fallback","xm":" 合成姓名 ","pylx":"2","sfxs":"1","rolecode":["02"]}"""
        )?.account)
        for (body in listOf(
            """{"yhdm":"a","pylx":"1","sfxs":"1","rolecode":["02"]}""",
            """{"yhdm":"a","pylx":"2","sfxs":"0","rolecode":["02"]}""",
            """{"yhdm":"a","pylx":"2","sfxs":"1","rolecode":["020"]}""",
            """{"yhdm":"a","pylx":2,"sfxs":"1","rolecode":["02"]}""",
            """{"yhdm":"a","pylx":"2","sfxs":"1","rolecode":"02"}""",
            """{"yhdm":" ","pylx":"2","sfxs":"1","rolecode":["02"]}""",
            "{}", "<html><form><input type='password'></form></html>",
        )) assertNull(WeihaiGraduateSessionPolicy.parseProfile(body))
    }

    @Test fun gatewayTicketWorksOnlyAtExactGraduateLandingWithoutJsessionId() {
        val base = WeihaiGraduateSessionPolicy.BASE_URL
        val cookie = mapOf(WeihaiGraduateSessionPolicy.TICKET_COOKIE to "synthetic-ticket")
        assertTrue(WeihaiGraduateSessionPolicy.isAuthenticatedLanding("$base/authentication/main", cookie))
        assertTrue(WeihaiGraduateSessionPolicy.isAuthenticatedLanding("$base/student_index", cookie))
        assertFalse(WeihaiGraduateSessionPolicy.isAuthenticatedLanding("$base/cas", cookie))
        assertFalse(WeihaiGraduateSessionPolicy.isAuthenticatedLanding("$base/authentication/main", mapOf("JSESSIONID" to "synthetic")))
        assertFalse(WeihaiGraduateSessionPolicy.isAuthenticatedLanding("$base/authentication/main", mapOf(WeihaiGraduateSessionPolicy.TICKET_COOKIE to "")))
        assertFalse(WeihaiGraduateSessionPolicy.isAuthenticatedLanding("https://example.invalid/student_index", cookie))
    }

    @Test fun transientServerFailureIsNotAuthenticationExpiry() {
        val url = WeihaiGraduateSessionPolicy.BASE_URL + "/user/me"
        val login = "<form><input type='password'></form>"
        assertFalse(WeihaiGraduateSessionPolicy.isAuthenticationFailure(503, url, login))
        for (status in listOf(302, 401, 403)) {
            assertTrue(WeihaiGraduateSessionPolicy.isAuthenticationFailure(status, url, ""))
        }
        assertTrue(WeihaiGraduateSessionPolicy.isAuthenticationFailure(200, url, login))
    }
}
