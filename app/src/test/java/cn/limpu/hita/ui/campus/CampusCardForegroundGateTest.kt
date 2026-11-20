package cn.limpu.hita.ui.campus

import org.junit.Assert.*
import org.junit.Test

class CampusCardForegroundGateTest {
    @Test fun `app launch and return refresh while internal navigation does not`() {
        val gate = CampusCardForegroundGate()
        assertTrue(gate.started()) // Main
        assertFalse(gate.started()) // Card starts before Main stops
        gate.stopped(false)
        assertFalse(gate.started()) // Another internal page
        gate.stopped(false)
        gate.stopped(false) // Home button
        assertTrue(gate.started()) // Return to app
    }

    @Test fun `configuration recreation does not refresh but next real foreground does`() {
        val gate = CampusCardForegroundGate()
        assertTrue(gate.started())
        gate.stopped(true)
        assertFalse(gate.started())
        gate.stopped(false)
        assertTrue(gate.started())
    }

    @Test fun `stopping one of multiple windows does not turn navigation into a launch`() {
        val gate = CampusCardForegroundGate()
        assertTrue(gate.started())
        assertFalse(gate.started())
        gate.stopped(true)
        assertFalse(gate.started())
    }
}
