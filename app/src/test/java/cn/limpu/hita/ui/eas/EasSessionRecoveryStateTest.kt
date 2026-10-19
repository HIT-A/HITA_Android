package cn.limpu.hita.ui.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasSessionRecoveryStateTest {
    @Test
    fun `repeated login success followed by expired query stops after three retries`() {
        val recovery = EasSessionRecoveryState()
        var refreshes = 0
        repeat(3) {
            assertTrue(recovery.begin { refreshes++; true })
            recovery.loginSucceeded()
        }
        assertEquals(3, refreshes)
        assertFalse(recovery.begin { error("must not retry forever") })
    }

    @Test
    fun `concurrent expired responses share one recovery and one retry`() {
        val recovery = EasSessionRecoveryState()
        var refreshes = 0
        assertTrue(recovery.begin { refreshes++; true })
        assertFalse(recovery.begin { error("duplicate login") })
        recovery.loginSucceeded()
        recovery.loginSucceeded()
        assertEquals(1, refreshes)
        assertFalse(recovery.isRecovering)
    }

    @Test
    fun `postponing login releases the page without spending retry budget`() {
        val recovery = EasSessionRecoveryState(maxRetries = 1)
        repeat(5) {
            assertTrue(recovery.begin { error("cancelled action must not run") })
            recovery.cancel()
            recovery.loginSucceeded()
            assertFalse(recovery.isRecovering)
        }
        assertTrue(recovery.begin { true })
        recovery.loginSucceeded()
        assertFalse(recovery.begin { true })
    }

    @Test
    fun `unrelated successful query cannot abandon an open login`() {
        val recovery = EasSessionRecoveryState(maxRetries = 1)
        var retried = false
        assertTrue(recovery.begin { retried = true; true })
        recovery.querySucceeded()
        assertTrue(recovery.isRecovering)
        assertFalse(recovery.begin { true })
        recovery.loginSucceeded()
        assertTrue(retried)
        assertFalse(recovery.begin { true })
    }

    @Test
    fun `completed query permits a later independent session expiry`() {
        val recovery = EasSessionRecoveryState(maxRetries = 1)
        assertTrue(recovery.begin { true })
        recovery.loginSucceeded()
        assertFalse(recovery.begin { true })
        recovery.querySucceeded()
        assertTrue(recovery.begin { true })
    }

    @Test
    fun `a query rejected before starting does not exhaust recovery budget`() {
        val recovery = EasSessionRecoveryState(maxRetries = 1)
        assertTrue(recovery.begin { false })
        recovery.loginSucceeded()
        assertTrue(recovery.begin { true })
    }

    @Test
    fun `synchronous expired retry does not lose its new pending action`() {
        val recovery = EasSessionRecoveryState()
        var refreshes = 0
        assertTrue(recovery.begin {
            assertTrue(recovery.begin { refreshes++; true })
            true
        })
        recovery.loginSucceeded()
        assertTrue(recovery.isRecovering)
        recovery.loginSucceeded()
        assertEquals(1, refreshes)
    }
}
