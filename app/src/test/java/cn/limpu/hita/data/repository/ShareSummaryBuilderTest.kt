package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.feature.timetableshare.protocol.ShareLimits
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import java.sql.Timestamp
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ShareSummaryBuilderTest {
    private fun time(value: String) = LocalDateTime.parse(value).atZone(ShareLimits.ZONE).toInstant().toEpochMilli()
    private fun timetable() = Timetable().apply {
        name = "我的真实课表"; code = "BENBU:2026-20271"
        startTime = Timestamp(time("2026-08-31T00:00:00")); endTime = Timestamp(time("2026-12-31T00:00:00"))
    }
    private fun event(table: Timetable, name: String, from: String, to: String) = EventItem().apply {
        timetableId = table.id; type = EventItem.TYPE.CLASS; this.name = name; subjectId = name
        this.from = Timestamp(time("2026-08-31T$from")); this.to = Timestamp(time("2026-08-31T$to"))
    }

    @Test fun adjacentDifferentCoursesUseFullCountsAndCanonicalBusyOccupancy() {
        val table = timetable()
        val events = listOf(event(table, "数学", "08:30:00", "10:15:00"), event(table, "物理", "10:15:00", "12:15:00"))
        val builder = ShareSummaryBuilder()
        val full = builder.build(table, events, ShareMode.FULL)
        val busy = builder.build(table, events, ShareMode.BUSY)
        assertEquals(2, full.courseCount)
        assertEquals(2, full.occurrenceCount)
        assertEquals(0, busy.courseCount)
        assertEquals(1, busy.occurrenceCount)
        assertEquals(ShareMode.FULL, full.mode)
        assertEquals(ShareMode.BUSY, busy.mode)
        assertEquals(table.name, full.name)
        assertEquals(table.name, busy.name)
        assertEquals(full.termName, busy.termName)
    }

    @Test fun emptySelectedClassesPreserveNameTermAndZeroCountsForEitherMode() {
        val table = timetable()
        val events = listOf(event(table, "另一课表", "08:30:00", "10:15:00").apply { timetableId = "other" },
            event(table, "考试", "10:15:00", "12:15:00").apply { type = EventItem.TYPE.EXAM })
        ShareMode.entries.forEach { mode ->
            val summary = ShareSummaryBuilder().build(table, events, mode)
            assertEquals(table.name, summary.name)
            assertTrue(summary.termName.isNotBlank())
            assertEquals(0, summary.courseCount)
            assertEquals(0, summary.occurrenceCount)
            assertEquals(mode, summary.mode)
        }
    }
}
