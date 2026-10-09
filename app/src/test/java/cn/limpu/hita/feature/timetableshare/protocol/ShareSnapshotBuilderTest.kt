package cn.limpu.hita.feature.timetableshare.protocol

import cn.limpu.hita.data.model.timetable.*
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class ShareSnapshotBuilderTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun time(value: String) = LocalDateTime.parse(value).atZone(ShareLimits.ZONE).toInstant().toEpochMilli()
    private fun timetable() = Timetable().apply {
        name = "秋季课表"; code = "BENBU:2026-20271"
        startTime = Timestamp(time("2026-08-31T00:00:00")); endTime = Timestamp(time("2026-12-31T00:00:00"))
    }
    private fun event(tt: Timetable, day: String = "2026-08-31", subject: String = "private-subject") = EventItem().apply {
        timetableId = tt.id; name = "数学"; place = "秘密教室"; teacher = "秘密教师"; subjectId = subject; source = EventItem.SOURCE_MANUAL
        from = Timestamp(time("${day}T08:30:00")); to = Timestamp(time("${day}T10:15:00"))
    }
    @Test fun onlySelectedClassEventsAreSharedIncludingManualCourses() {
        val tt = timetable()
        val events = EventItem.TYPE.values().map { type -> event(tt).apply { this.type = type } } + event(tt).apply { timetableId = "other"; from = Timestamp(0) }
        val result = ShareSnapshotBuilder().build(tt, events, id, "小林", ShareMode.FULL)
        assertEquals(listOf("数学"), result.courses); assertEquals(1, result.occurrences.size)
        assertEquals(SharedPeriod(510, 615), result.periods.first())
    }
    @Test fun irregularWeeksAndSaturdayAdjustmentKeepExactActualTimes() {
        val tt = timetable()
        val days = listOf("2026-08-31", "2026-09-14", "2026-10-12", "2026-10-17")
        val result = ShareSnapshotBuilder().build(tt, days.reversed().map { event(tt, it) }, id, "小林", ShareMode.FULL)
        assertEquals(days.map { SharedOccurrence(time("${it}T08:30:00"), time("${it}T10:15:00"), 0, "秘密教室") }, result.occurrences)
    }
    @Test fun busyStripsDetailsBeforeEncodingAndMergesRegardlessOfGrouping() {
        val tt = timetable()
        val events = listOf(event(tt), event(tt, subject = "second").apply { from = Timestamp(time("2026-08-31T10:15:00")); to = Timestamp(time("2026-08-31T12:15:00")); name = "物理" })
        val result = ShareSnapshotBuilder().build(tt, events, id, "小林", ShareMode.BUSY)
        assertTrue(result.courses.isEmpty()); assertEquals("忙闲课表", result.term.timetableName)
        assertEquals(listOf(SharedOccurrence(time("2026-08-31T08:30:00"), time("2026-08-31T12:15:00"))), result.occurrences)
        val token = TimetableShareCodec().encode(result)
        val json = GZIPInputStream(Base64.getUrlDecoder().decode(token.removePrefix("HITA1:")).inputStream()).reader().use { it.readText() }
        listOf("数学", "物理", "秘密教室", "private-subject", "秘密教师", "BENBU", "subjectId", "teacher", "code").forEach { assertFalse(json.contains(it)) }
        events.forEach { it.name = "changed"; it.subjectId = "same"; it.place = "changed" }
        assertEquals(result, ShareSnapshotBuilder().build(tt, events, id, "小林", ShareMode.BUSY))
    }
    @Test fun invalidMatchedRecordsFailRatherThanBeingSkippedOrMergedAway() {
        val tt = timetable()
        for (mode in ShareMode.values()) {
            try { ShareSnapshotBuilder().build(tt, listOf(event(tt), event(tt).apply { to = from }), id, "小林", mode); fail() }
            catch (e: ShareFormatException) { assertEquals(ShareError.INVALID_FIELDS, e.error) }
        }
    }
    @Test fun emptyClassSelectionFails() {
        try { ShareSnapshotBuilder().build(timetable(), emptyList(), id, "小林", ShareMode.FULL); fail() }
        catch (e: ShareFormatException) { assertEquals(ShareError.EMPTY_SCHEDULE, e.error) }
    }
    @Test fun oneSubjectWithDifferentNamesPreservesEveryCourseName() {
        val tt = timetable()
        val result = ShareSnapshotBuilder().build(tt, listOf(event(tt), event(tt, "2026-09-01").apply { name = "数学实验" }), id, "小林", ShareMode.FULL)
        assertEquals(listOf("数学", "数学实验"), result.courses)
        assertEquals(listOf(0, 1), result.occurrences.map { it.courseIndex })
    }
    @Test fun sourceLimitsApplyBeforeBusyMerging() {
        val tt = timetable()
        try { ShareSnapshotBuilder().build(tt, List(4097) { event(tt) }, id, "小林", ShareMode.BUSY); fail() }
        catch (e: ShareFormatException) { assertEquals(ShareError.INVALID_FIELDS, e.error) }
    }
    @Test fun invalidClockComponentsCannotBecomeValidMinutes() {
        val tt = timetable().apply { scheduleStructure = listOf(TimePeriodInDay(TimeInDay(7, 0), TimeInDay(10, 15)).apply { from.minute = 90 }) }
        try { ShareSnapshotBuilder().build(tt, listOf(event(tt)), id, "小林", ShareMode.FULL); fail() }
        catch (e: ShareFormatException) { assertEquals(ShareError.INVALID_FIELDS, e.error) }
    }
    @Test fun anonymousGroupingKeepsDistinctSubjectsAndUsesNamesWithoutSubject() {
        val tt = timetable()
        val result = ShareSnapshotBuilder().build(tt, listOf(event(tt, subject = "first"), event(tt, "2026-09-01", "second"), event(tt, "2026-09-02", ""), event(tt, "2026-09-03", "")), id, "小林", ShareMode.FULL)
        assertEquals(listOf("数学", "数学", "数学"), result.courses)
        assertEquals(listOf(0, 1, 2, 2), result.occurrences.map { it.courseIndex })
        assertFalse(TimetableShareCodec().canonicalJson(result).contains("first"))
    }
}
