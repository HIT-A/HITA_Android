package cn.limpu.hita.data.source.web.campus

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusCardHistoryRangeTest {
    @Test
    fun `month is calculated in Shanghai time across UTC month boundary`() {
        assertEquals(mapOf("beginTime" to "2026/12/01 00:00:00", "endTime" to "2026/12/31 23:59:59"),
            CampusCardHistoryRange.MONTH.parameters(Instant.parse("2026-11-30T16:01:00Z")))
    }

    @Test
    fun `leap February keeps its last day and all has no date restriction`() {
        val now = Instant.parse("2024-02-15T00:00:00Z")
        assertEquals("2024/02/29 23:59:59", CampusCardHistoryRange.MONTH.parameters(now)["endTime"])
        assertTrue(CampusCardHistoryRange.ALL.parameters(now).isEmpty())
    }
}
