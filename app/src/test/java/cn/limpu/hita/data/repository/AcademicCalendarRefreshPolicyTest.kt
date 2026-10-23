package cn.limpu.hita.data.repository

import org.junit.Assert.*
import org.junit.Test

class AcademicCalendarRefreshPolicyTest {
    private val now = 1_800_000_000_000L
    @Test fun successfulCampusDoesNotSuppressFailedCampusRetry() {
        val policy = AcademicCalendarRefreshPolicy()
        assertFalse(policy.begin("BENBU", now, true, now - 1000))
        assertTrue(policy.begin("SHENZHEN", now, false, 0))
        assertFalse(policy.begin("SHENZHEN", now + 1000, false, 0))
        assertTrue(policy.begin("SHENZHEN", now + 15 * 60_000L, false, 0))
    }
    @Test fun freshAndExpiredCacheUseSeparateWindows() {
        val policy = AcademicCalendarRefreshPolicy()
        assertFalse(policy.begin("WEIHAI", now, true, now - 1))
        assertTrue(policy.begin("WEIHAI", now + 24 * 60 * 60_000L, true, now))
    }
    @Test fun missingCacheDoesNotTrustSavedTimestamp() {
        assertTrue(AcademicCalendarRefreshPolicy().begin("BENBU", now, false, now))
    }
    @Test fun clockRollbackDoesNotBlockRefreshForever() {
        val policy = AcademicCalendarRefreshPolicy()
        assertTrue(policy.begin("BENBU", now, false, 0))
        assertTrue(policy.begin("BENBU", now - 1000, true, now))
    }
}
