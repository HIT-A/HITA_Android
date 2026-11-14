package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.*
import org.junit.Test

class EasLoginPageStateTest {
    private val state = EasLoginPageState()
    private val campus = EASToken.Campus.BENBU
    private val url = "https://ids.hit.edu.cn/authserver/login?service=jwts"

    @Test fun navigationToMfaInvalidatesPendingFormWork() {
        val page = state.capture(campus, url)!!
        state.navigated()
        assertFalse(state.isCurrent(page, campus, "https://ids.hit.edu.cn/authserver/reAuthCheck/reAuthLogin.do"))
        assertNull(state.capture(campus, "https://ids.hit.edu.cn/authserver/reAuthCheck/reAuthLogin.do"))
        assertFalse(state.claimSubmission(page))
    }

    @Test fun reloadingTheSameUrlCannotRunCallbacksFromThePreviousDocument() {
        val page = state.capture(campus, url)!!
        state.navigated()
        assertFalse(state.isCurrent(page, campus, url))
        assertTrue(state.isCurrent(state.capture(campus, url)!!, campus, url))
    }

    @Test fun changedUrlAndUnapprovedHostAreRejectedEvenBeforeNavigationCallback() {
        val page = state.capture(campus, url)!!
        assertFalse(state.isCurrent(page, campus, url + "&service=other"))
        assertFalse(state.isCurrent(page, campus, url.replace("ids.hit.edu.cn", "attacker.invalid")))
        assertTrue(state.isCurrent(page, campus, url + "#login"))
    }

    @Test fun duplicatePagesAndThirdDistinctFormDoNotResubmitCredentials() {
        assertTrue(state.claimSubmission(state.capture(campus, url)!!))
        state.navigated()
        assertFalse(state.claimSubmission(state.capture(campus, url)!!))
        assertTrue(state.claimSubmission(state.capture(campus, url + "&step=2")!!))
        assertFalse(state.claimSubmission(state.capture(campus, url + "&step=3")!!))
    }
}
