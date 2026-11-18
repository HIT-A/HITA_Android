package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayVisualPresentationTest {
    private val window = TodayDateWindow.from(Instant.parse("2026-11-18T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private fun event(from: String, to: String) = EventItem().apply {
        this.from = Timestamp(Instant.parse(from).toEpochMilli()); this.to = Timestamp(Instant.parse(to).toEpochMilli())
    }
    @Test fun sameDayUsesCompactTimeWhileMidnightKeepsDates() {
        assertEquals("10:00–11:35", todayEventTime(event("2026-11-18T02:00:00Z", "2026-11-18T03:35:00Z"), window))
        assertEquals("11-18 23:00–11-19 01:00", todayEventTime(event("2026-11-18T15:00:00Z", "2026-11-18T17:00:00Z"), window))
    }
    @Test fun yearBoundaryAndTomorrowAreExplicit() {
        assertEquals("2026-12-31 23:00–2027-01-01 01:00", todayEventTime(event("2026-12-31T15:00:00Z", "2026-12-31T17:00:00Z"), window))
        assertEquals("11-19 08:00–11-19 09:35", todayEventTime(event("2026-11-19T00:00:00Z", "2026-11-19T01:35:00Z"), window))
    }
    @Test fun invalidTimeIsNotReorderedOrInvented() {
        assertEquals("11:35–10:00", todayEventTime(event("2026-11-18T03:35:00Z", "2026-11-18T02:00:00Z"), window))
    }
    private fun source(source: TodayHomeworkSource, count: Int?, status: TodaySourceStatus = TodaySourceStatus.READY) =
        TodayHomeworkSourceSummary(source, status, dueToday = count, overdue = if (count != null) 1 else null)
    @Test fun disconnectedGraderKeepsLocalIdentityInsteadOfShowingATotal() {
        val model = todayHomeworkCard(TodayHomeworkSummary(source(TodayHomeworkSource.LOCAL, 2), source(TodayHomeworkSource.GRADER, null, TodaySourceStatus.DISCONNECTED)))
        assertEquals(2, model.count); assertEquals(TodayHomeworkSource.LOCAL, model.singleSource)
        assertEquals(2, model.sources.size); assertEquals(1, model.known.size)
    }
    @Test fun twoKnownSourcesKeepBreakdownAndCanSum() {
        val model = todayHomeworkCard(TodayHomeworkSummary(source(TodayHomeworkSource.LOCAL, 1), source(TodayHomeworkSource.GRADER, 2)))
        assertEquals(3, model.count); assertNull(model.singleSource)
        assertEquals(2, model.overdue); assertEquals(2, model.known.size)
    }
    @Test fun unknownCountsAreNotZeroButKnownEmptyIsZero() {
        val local = source(TodayHomeworkSource.LOCAL, null, TodaySourceStatus.LOADING)
        val grader = source(TodayHomeworkSource.GRADER, null, TodaySourceStatus.LOADING)
        assertNull(todayHomeworkCard(TodayHomeworkSummary(local, grader)).count)
        assertEquals(0, todayHomeworkCard(TodayHomeworkSummary(local.copy(dueToday = 0), grader.copy(status = TodaySourceStatus.UNAVAILABLE))).count)
    }
    @Test fun retainedFailureKeepsCountsAndVisibleFailureIdentity() {
        val model = todayHomeworkCard(TodayHomeworkSummary(source(TodayHomeworkSource.LOCAL, 2, TodaySourceStatus.FAILED), source(TodayHomeworkSource.GRADER, null, TodaySourceStatus.UNAVAILABLE)))
        assertEquals(2, model.count); assertTrue(model.retained)
        assertEquals(TodaySourceStatus.FAILED, model.sources.single().status)
    }
    @Test fun compactStatusPrioritizesUnknownSourcesThenOverdueAndKnownEmpty() {
        val local = source(TodayHomeworkSource.LOCAL, 0).copy(overdue = 0)
        val disconnected = source(TodayHomeworkSource.GRADER, null, TodaySourceStatus.DISCONNECTED)
        assertEquals(TodayCompactHomeworkStatus.Source(TodayHomeworkSource.GRADER, TodaySourceStatus.DISCONNECTED),
            todayCompactHomeworkStatus(TodayHomeworkSummary(local, disconnected)))
        val grader = disconnected.copy(status = TodaySourceStatus.READY, dueToday = 0, overdue = 0)
        assertEquals(TodayCompactHomeworkStatus.Empty, todayCompactHomeworkStatus(TodayHomeworkSummary(local, grader)))
        assertEquals(TodayCompactHomeworkStatus.Overdue(2), todayCompactHomeworkStatus(TodayHomeworkSummary(local.copy(overdue = 2), grader)))
        assertEquals(TodayCompactHomeworkStatus.Source(TodayHomeworkSource.LOCAL, TodaySourceStatus.FAILED),
            todayCompactHomeworkStatus(TodayHomeworkSummary(local.copy(status = TodaySourceStatus.FAILED), grader)))
    }
}
