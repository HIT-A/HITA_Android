package cn.limpu.hita.feature.timetable.display

import cn.limpu.hita.feature.timetable.display.EventTimePolicy.Phase
import org.junit.Assert.*
import org.junit.Test

class EventTimePolicyTest {
    @Test fun `start is active and end is ended`() {
        assertEquals(Phase.UPCOMING, EventTimePolicy.phase(100, 200, 99))
        assertEquals(Phase.ACTIVE, EventTimePolicy.phase(100, 200, 100))
        assertEquals(Phase.ACTIVE, EventTimePolicy.phase(100, 200, 199))
        assertEquals(Phase.ENDED, EventTimePolicy.phase(100, 200, 200))
    }

    @Test fun `invalid intervals never produce progress`() {
        for ((start, end) in listOf(100L to 100L, 200L to 100L)) {
            assertEquals(Phase.INVALID, EventTimePolicy.phase(start, end, 150))
            assertEquals(0f, EventTimePolicy.progress(start, end, 150), 0f)
        }
        assertEquals(0f, EventTimePolicy.progress(100, 200, 0), 0f)
        assertEquals(0.5f, EventTimePolicy.progress(100, 200, 150), 0f)
        assertEquals(1f, EventTimePolicy.progress(100, 200, 300), 0f)
    }

    @Test fun `positive remaining time rounds up without showing zero early`() {
        assertEquals(1L, EventTimePolicy.remainingMinutes(20_000, 0))
        assertEquals(1L, EventTimePolicy.remainingMinutes(60_000, 0))
        assertEquals(2L, EventTimePolicy.remainingMinutes(60_001, 0))
        assertEquals(121L, EventTimePolicy.remainingMinutes(7_200_001, 0))
        assertEquals(0L, EventTimePolicy.remainingMinutes(100, 100))
        assertEquals(0L, EventTimePolicy.remainingMinutes(100, 200))
    }
}
