package cn.limpu.hita.ui.eas.exam

import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.ui.eas.exam.ExamViewModelContractHarness.Companion.exam
import cn.limpu.hita.ui.eas.exam.ExamViewModelContractHarness.Companion.term
import com.limpu.component.data.DataState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExamViewModelCancellationInstrumentedTest {
    private var harness: ExamViewModelContractHarness? = null
    private fun create() = ExamViewModelContractHarness().also { harness = it }
    @After fun tearDown() { harness?.close() }

    @Test fun supersededNonCancellableQueryCannotDeliverOrCompleteAnalytics() {
        val h = create()
        h.nonCancellableQueries = true
        h.start()
        h.selectAndRefresh()
        h.main { h.now += 1_000; h.model.startRefresh() }
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        h.completeNonCancellable(1, DataState(listOf(exam("latest"))))
        h.completeNonCancellable(0, DataState(listOf(exam("obsolete"))))
        h.main {
            assertEquals(listOf("latest"), names(h))
            assertEquals(2, h.analytics.count { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_STARTED })
            assertEquals(1, h.analytics.count { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_SUCCEEDED })
            assertEquals(0, h.analytics.count { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_FAILED })
            assertEquals(h.analytics[1].operationId, h.analytics.last().operationId)
        }
    }

    @Test fun clearCancelsNonCancellableQueryAndLateCompletionLeavesStateUntouched() {
        val h = create()
        h.nonCancellableQueries = true
        h.start()
        h.selectAndRefresh()
        val before = h.model.examInfoLiveData.value
        h.main { h.store.clear() }
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        h.completeNonCancellable(0, DataState(listOf(exam("too late"))))
        h.main {
            assertSame(before, h.model.examInfoLiveData.value)
            assertEquals(listOf(UsageAnalyticsEvent.EXAMS_REFRESH_STARTED), h.analytics.map { it.event })
        }
    }

    @Test fun throttledRefreshDoesNotCancelCurrentQuery() {
        val h = create()
        h.nonCancellableQueries = true
        h.start()
        h.selectAndRefresh()
        h.main { h.now += 999; h.model.startRefresh() }
        assertFalse(requireNotNull(h.jobs[0]).isCancelled)
        assertEquals(1, h.jobs.size)
        h.completeNonCancellable(0, DataState(listOf(exam("still current"))))
        h.main { assertEquals(listOf("still current"), names(h)) }
    }

    @Test fun immediateAuthenticationResultMayReenterRefreshAndLatestJobRemainsOwned() {
        val h = create()
        h.examsInitialState = DataState(DataState.STATE.NOT_LOGGED_IN, "expired")
        var restarted = false
        val observer = Observer<DataState<List<ExamItem>>> { state ->
            if (!restarted && state.state == DataState.STATE.NOT_LOGGED_IN) {
                restarted = true
                h.examsInitialState = null
                h.now += 1_000
                h.model.startRefresh()
            }
        }
        h.start()
        h.main { h.model.examInfoLiveData.observe(h.owner, observer) }
        h.selectAndRefresh()
        h.flush()
        h.main {
            assertTrue(restarted)
            assertEquals(2, h.exams.size)
            assertTrue(requireNotNull(h.jobs[1]).isActive)
            h.store.clear()
            assertTrue(requireNotNull(h.jobs[1]).isCancelled)
            assertFalse(h.exams[1].hasObservers())
        }
    }

    @Test fun obsoleteQueuedResponseDoesNotUpdateRemoteListUsedByMemoEdits() {
        val h = create()
        h.start()
        h.selectAndRefresh()
        h.main {
            h.exams[0].value = DataState(listOf(exam("obsolete")))
            h.model.selectedTermLiveData.value = term("2")
            h.model.startRefresh()
        }
        h.flush()
        h.main { h.model.saveExamMemo(exam("memo", term = term("2"), memo = true)) }
        h.flush()
        h.main {
            // Switch back locally without a request: a stale remote list would become visible.
            h.model.selectedTermLiveData.value = term("1")
            assertEquals(emptyList<String>(), names(h))
            assertEquals(0, h.analytics.count { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_SUCCEEDED })
        }
    }

    private fun names(h: ExamViewModelContractHarness) =
        h.model.examInfoLiveData.value?.data.orEmpty().map { it.courseName.orEmpty() }
}
