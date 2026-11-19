package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasSessionGenerationGuardTest {
    @Test
    fun `current logged in session accepts routine refresh`() {
        assertTrue(
            EasSessionGenerationGuard.acceptsServiceRefresh(
                refreshEnabled = true,
                tokenGeneration = 7,
                currentGeneration = 7,
                storedSessionLoggedIn = true
            )
        )
    }

    @Test
    fun `response started before logout is rejected after relogin`() {
        assertFalse(
            EasSessionGenerationGuard.acceptsServiceRefresh(
                refreshEnabled = true,
                tokenGeneration = 7,
                currentGeneration = 8,
                storedSessionLoggedIn = true
            )
        )
    }

    @Test
    fun `refresh is rejected while logged out`() {
        assertFalse(
            EasSessionGenerationGuard.acceptsServiceRefresh(
                refreshEnabled = false,
                tokenGeneration = 8,
                currentGeneration = 8,
                storedSessionLoggedIn = false
            )
        )
    }

    @Test
    fun `credential scope rotates only when account or web session identity changes`() {
        val stored = loggedInToken("account-a", "session-a")
        val sameSession = loggedInToken("account-a", "session-a")
        val switchedAccount = loggedInToken("account-b", "session-b")
        val replacedSession = loggedInToken("account-a", "session-b")

        assertEquals(
            41L,
            EasSessionGenerationGuard.resolveCredentialScopeGeneration(
                storedToken = stored,
                incomingToken = sameSession,
                currentGeneration = 41L,
                nextGeneration = { 99L }
            )
        )
        assertEquals(
            99L,
            EasSessionGenerationGuard.resolveCredentialScopeGeneration(
                storedToken = stored,
                incomingToken = switchedAccount,
                currentGeneration = 41L,
                nextGeneration = { 99L }
            )
        )
        assertEquals(
            99L,
            EasSessionGenerationGuard.resolveCredentialScopeGeneration(
                storedToken = stored,
                incomingToken = replacedSession,
                currentGeneration = 41L,
                nextGeneration = { 99L }
            )
        )
        assertEquals(
            99L,
            EasSessionGenerationGuard.resolveCredentialScopeGeneration(
                storedToken = EASToken(),
                incomingToken = sameSession,
                currentGeneration = 0L,
                nextGeneration = { 99L }
            )
        )
    }

    @Test
    fun `verified Benbu login retires older requests even for the same account`() {
        val stored = EASToken().apply {
            campus = EASToken.Campus.BENBU
            username = "account-a"
            cookies["JSESSIONID"] = "old-session"
        }
        val replacement = EASToken().apply {
            campus = EASToken.Campus.BENBU
            username = "account-a"
            cookies["JSESSIONID"] = "new-session"
        }
        val refreshed = EasSessionGenerationGuard.resolveCredentialScopeGeneration(stored, replacement, 41L) { 99L }
        val relogged = EasSessionGenerationGuard.resolveCredentialScopeGeneration(stored, replacement, 41L,
            verifiedBenbuLogin = true) { 99L }
        assertEquals(41L, refreshed)
        assertEquals(99L, relogged)
        assertFalse(EasSessionGenerationGuard.acceptsServiceRefresh(true, 41L, relogged, true))
    }

    @Test
    fun `different account blocks inheritance of the stored session`() {
        val stored = loggedInToken("account-a", "session-a")
        assertTrue(
            EasSessionGenerationGuard.blocksStoredSessionInheritance(
                stored,
                loggedInToken("account-b", "session-b")
            )
        )
        assertFalse(
            EasSessionGenerationGuard.blocksStoredSessionInheritance(
                stored,
                loggedInToken("account-a", "session-b")
            )
        )
    }

    private fun loggedInToken(account: String, session: String) = EASToken().apply {
        campus = EASToken.Campus.SHENZHEN
        username = account
        webCookies["JSESSIONID"] = session
        webCookies["route"] = "route"
    }
}
