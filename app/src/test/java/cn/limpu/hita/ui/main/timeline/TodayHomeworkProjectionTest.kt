package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.grader.GraderSummaryItem
import cn.limpu.hita.data.model.homework.HomeworkItem
import cn.limpu.hita.data.repository.grader.GraderError
import cn.limpu.hita.data.repository.grader.GraderState
import cn.limpu.hita.data.repository.homework.HomeworkState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayHomeworkProjectionTest {
    private val now = Instant.parse("2026-11-17T04:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun local(id: String, date: String? = "2026-11-17", time: String? = null, done: Boolean = false) =
        HomeworkItem(id, "same title", now, done, date, time)
    private fun grader(id: String, due: Long? = now, done: Boolean = false, account: String = "1") =
        GraderSummaryItem(account, id, "same title", "course", due, done)
    @Test fun `manual counts handle legacy time undated completed overdue and malformed dates`() {
        val local = HomeworkState(listOf(local("due"), local("old", time = "11:59"), local("done", done = true),
            local("no-date", null), local("invalid", "bad-date"), local("bad-time", time = "24:01")), ready = true)
        val result = TodayHomeworkProjection.project(local, GraderState(), true, now, zone)
        assertEquals(1, result.local.dueToday)
        assertEquals(1, result.local.overdue)
        assertEquals(1, result.local.undated)
        assertEquals(2, result.local.invalidDeadline)
        assertEquals("due", result.local.earliestDeadline?.id)
        assertEquals(Instant.parse("2026-11-17T15:59:00Z").toEpochMilli(), result.local.earliestDeadline?.dueAt)
    }
    @Test fun `grader preserves source account completion and missing deadline semantics`() {
        val grader = GraderState(accountId = "1", ready = true, summaryItems = listOf(
            grader("due"), grader("old", now - 1), grader("done", done = true), grader("undated", null),
            grader("other-account", account = "2")))
        val result = TodayHomeworkProjection.project(HomeworkState(listOf(local("due")), true), grader, true, now, zone)
        assertEquals(1, result.local.dueToday)
        assertEquals(1, result.grader.dueToday)
        assertEquals(1, result.grader.overdue)
        assertEquals(1, result.grader.undated)
        assertEquals("1", result.grader.earliestDeadline?.accountId)
    }
    @Test fun `unknown disconnected unavailable and failed retained sources never pretend zero`() {
        val loading = TodayHomeworkProjection.project(HomeworkState(), GraderState(), true, now, zone)
        assertEquals(TodaySourceStatus.LOADING, loading.local.status)
        assertNull(loading.local.dueToday); assertNull(loading.grader.dueToday)
        val disconnected = TodayHomeworkProjection.project(HomeworkState(), GraderState(ready = true), true, now, zone)
        assertEquals(TodaySourceStatus.DISCONNECTED, disconnected.grader.status)
        assertNull(disconnected.grader.dueToday)
        val retained = GraderState(accountId = "1", ready = true, error = GraderError.NETWORK, summaryItems = listOf(grader("due")))
        val failed = TodayHomeworkProjection.project(HomeworkState(listOf(local("due")), true, true), retained, true, now, zone)
        assertEquals(TodaySourceStatus.FAILED, failed.local.status)
        assertEquals(TodaySourceStatus.FAILED, failed.grader.status)
        assertEquals(1, failed.local.dueToday); assertEquals(1, failed.grader.dueToday)
        val needsLogin = TodayHomeworkProjection.project(HomeworkState(), retained.copy(error = null, needsLogin = true), true, now, zone)
        assertEquals(TodaySourceStatus.DISCONNECTED, needsLogin.grader.status)
        assertEquals(1, needsLogin.grader.dueToday)
        val hidden = TodayHomeworkProjection.project(HomeworkState(), retained, false, now, zone)
        assertEquals(TodaySourceStatus.UNAVAILABLE, hidden.grader.status)
        assertNull(hidden.grader.dueToday); assertNull(hidden.grader.accountId)
    }
    @Test fun `grader uses Shanghai day while manual homework follows device local date`() {
        val nearMidnight = Instant.parse("2026-11-17T16:30:00Z").toEpochMilli()
        val future = Instant.parse("2026-11-18T01:00:00Z").toEpochMilli()
        val result = TodayHomeworkProjection.project(HomeworkState(listOf(local("local", "2026-11-17")), true),
            GraderState(accountId = "1", ready = true, summaryItems = listOf(grader("grader", future))),
            true, nearMidnight, ZoneId.of("UTC"))
        assertEquals(1, result.local.dueToday)
        assertEquals(1, result.grader.dueToday)
        assertEquals("2026-11-18", Instant.ofEpochMilli(future).atZone(zone).toLocalDate().toString())
    }
}
