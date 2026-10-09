package cn.limpu.hita.ui.timetable.share

import cn.limpu.hita.data.repository.ShareSummary
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import org.junit.Assert.*
import org.junit.Test

class ShareSummaryStateTest {
    @Test fun switchingModeRequestsMatchingCountsAndRejectsOldSummary() {
        val full = ShareSummaryState().reload("a", ShareMode.FULL)
        val fullSummary = ShareSummary("A", "学期", 2, 2, ShareMode.FULL)
        val busy = full.accept(full.revision, fullSummary).reload("a", ShareMode.BUSY)
        assertEquals(ShareMode.BUSY, busy.mode)
        assertTrue(busy.loading)
        assertNull(busy.summary)
        assertEquals(busy, busy.accept(full.revision, fullSummary))
        assertEquals(busy, busy.accept(busy.revision, fullSummary))
        val busySummary = ShareSummary("A", "学期", 0, 1, ShareMode.BUSY)
        val ready = busy.accept(busy.revision, busySummary)
        assertEquals(busySummary, ready.summary)
        assertFalse(ready.loading)
        val retry = ready.fail(ready.revision, 123).reload("a")
        assertEquals(ShareMode.BUSY, retry.mode)
        assertEquals(retry, retry.fail(full.revision, 456))
    }

    @Test fun failedSummaryCanRetryAndSucceedForTheSameTimetable() {
        val initial = ShareSummaryState().reload("only-table")
        val failed = initial.fail(initial.revision, 123)
        assertFalse(failed.loading)
        assertNull(failed.summary)
        assertEquals(123, failed.errorResource)
        val retry = failed.reload("only-table")
        assertTrue(retry.loading)
        assertNull(retry.errorResource)
        assertTrue(retry.revision > failed.revision)
        val summary = ShareSummary("课表", "学期", 2, 8)
        val recovered = retry.accept(retry.revision, summary)
        assertEquals(summary, recovered.summary)
        assertFalse(recovered.loading)
        assertNull(recovered.errorResource)
    }

    @Test fun lateResultOrErrorCannotReplaceRetriedRequest() {
        val initial = ShareSummaryState().reload("a")
        val retry = initial.fail(initial.revision, 123).reload("a")
        assertEquals(retry, retry.accept(initial.revision, ShareSummary("旧", "学期", 1, 1)))
        assertEquals(retry, retry.fail(initial.revision, 456))
        val newestError = retry.fail(retry.revision, 789)
        assertEquals(newestError, newestError.accept(initial.revision, ShareSummary("旧", "学期", 1, 1)))
    }

    @Test fun changingSelectedTableClearsPreviousSummaryAndFailure() {
        val old = ShareSummaryState().reload("a")
        val ready = old.accept(old.revision, ShareSummary("A", "学期", 1, 1))
        val changed = ready.reload("b")
        assertEquals("b", changed.timetableId)
        assertNull(changed.summary)
        assertNull(changed.errorResource)
        assertTrue(changed.loading)
        val empty = changed.reload("")
        assertFalse(empty.loading)
        assertNull(empty.summary)
    }
}
