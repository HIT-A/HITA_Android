package cn.limpu.hita.ui.eas.exam

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.ui.eas.exam.ExamViewModelContractHarness.Companion.exam
import cn.limpu.hita.ui.eas.exam.ExamViewModelContractHarness.Companion.term
import com.limpu.component.data.DataState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Existing page contracts: this entire class must pass before coroutine migration. */
@RunWith(AndroidJUnit4::class)
class ExamViewModelBaselineInstrumentedTest {
    private var harness: ExamViewModelContractHarness? = null
    private fun create(memos: List<ExamItem> = emptyList()) =
        ExamViewModelContractHarness(memos).also { harness = it }

    @After fun tearDown() { harness?.close() }

    @Test fun initialLocalMemosAreVisibleWithoutStartingRemoteRequests() {
        val h = create(listOf(exam("memo", memo = true)))
        h.start()
        h.main {
            assertEquals(DataState.STATE.SUCCESS, h.model.examInfoLiveData.value?.state)
            assertEquals(listOf("memo"), names(h))
            assertTrue(h.terms.isEmpty())
            assertTrue(h.exams.isEmpty())
            assertTrue(h.analytics.isEmpty())
        }
    }

    @Test fun initialTermGateWaitsForActivationAndConsumesOnlyFirstTerminalResult() {
        val h = create()
        h.main { h.model.startRefresh() }
        assertTrue(h.terms.isEmpty())
        assertTrue(h.exams.isEmpty())
        h.start()
        h.main {
            assertEquals(1, h.terms.size)
            h.terms.first().value = DataState(DataState.STATE.NOTHING)
            h.terms.first().value = DataState(DataState.STATE.LOADING)
            assertTrue(h.exams.isEmpty())
            val current = term("2", current = true)
            h.terms.first().value = DataState(listOf(term("1"), current)).setFromCache(true)
            assertEquals(current, h.model.selectedTermLiveData.value)
            assertEquals(listOf(current), h.requestedTerms)
            // The separately verified regression now keeps the original term request alive.
            assertEquals(1, h.terms.size)
            h.terms.last().value = DataState(listOf(term("3", current = true)))
            assertEquals(current, h.model.selectedTermLiveData.value)
            assertEquals(1, h.exams.size)
        }
    }

    @Test fun alreadyValuedTermSourceCanReenterRefreshWithoutDoubleExamQuery() {
        val h = create()
        val current = term("2", current = true)
        h.termsInitialState = DataState(listOf(term("1"), current)).setFromCache(true)
        h.start()
        h.main {
            h.model.startRefresh()
            assertEquals(current, h.model.selectedTermLiveData.value)
            assertEquals(listOf(current), h.requestedTerms)
            assertEquals(1, h.terms.size)
            assertEquals(DataState.STATE.LOADING, h.model.examInfoLiveData.value?.state)
        }
    }

    @Test fun emptyTermsReportUnavailableAndLaterRefreshCanRetry() {
        val h = create()
        h.start()
        h.main {
            h.model.startRefresh()
            h.terms.last().value = DataState(emptyList())
            assertEquals(DataState.STATE.NOT_AVAILABLE, h.model.examInfoLiveData.value?.state)
            assertFalse(h.model.examInfoLiveData.value?.message.isNullOrBlank())
            assertTrue(h.exams.isEmpty())
            h.now += 1_000
            h.model.startRefresh()
            val first = term("1")
            h.terms.last().value = DataState(listOf(first, term("2")))
            assertEquals(listOf(first), h.requestedTerms)
        }
    }

    @Test fun failedTermsKeepLocalMemosAndPreserveAuthenticationFailure() {
        val h = create(listOf(exam("memo", memo = true)))
        h.start()
        h.main {
            h.model.startRefresh()
            h.terms.last().value = DataState(DataState.STATE.NOT_LOGGED_IN, "term session expired")
            assertEquals(DataState.STATE.NOT_LOGGED_IN, h.model.examInfoLiveData.value?.state)
            assertEquals("term session expired", h.model.examInfoLiveData.value?.message)
            assertEquals(listOf("memo"), names(h))
            assertTrue(h.exams.isEmpty())
        }
    }

    @Test fun selectedTermQueryStartsWithoutObserversAndCompletedResultReplaysOnActivation() {
        val h = create()
        h.selectAndRefresh()
        assertEquals(1, h.exams.size)
        assertTrue(h.terms.isEmpty())
        h.complete(0, DataState(listOf(exam("remote"))))
        assertTrue(h.states.isEmpty())
        h.start()
        h.main {
            assertEquals(listOf("remote"), names(h))
            assertEquals(1, h.exams.size)
            assertEquals(1, h.terms.size)
        }
        h.stop()
        h.start()
        assertEquals(1, h.exams.size)
    }

    @Test fun sameTermThrottleKeepsCurrentRequestWhileChangedTermBypassesIt() {
        val h = create()
        h.start()
        h.selectAndRefresh()
        h.main {
            h.now += 999
            h.model.startRefresh()
            assertEquals(1, h.exams.size)
            assertTrue(h.exams.first().hasObservers())
            h.model.selectedTermLiveData.value = term("2")
            h.model.startRefresh()
            assertEquals(listOf(term("1"), term("2")), h.requestedTerms)
            assertFalse(h.exams.first().hasObservers())
            assertTrue(h.exams.last().hasObservers())
        }
    }

    @Test fun changingSelectionOnlyFiltersAndTypeChangesDoNotRequestNetwork() {
        val h = create(listOf(exam("unscoped memo", term = null, type = "", memo = true)))
        h.start()
        h.selectAndRefresh()
        h.complete(0, DataState(listOf(
            exam("final", type = "期末"), exam("midterm", type = "期中"),
            exam("unknown", type = ""), exam("other term", term = term("2")),
        )))
        h.main {
            assertEquals(setOf("final", "midterm", "unknown", "unscoped memo"), names(h).toSet())
            h.model.selectedExamTypeLiveData.value = ExamViewModel.ExamType.MIDTERM
            assertEquals(setOf("midterm", "unknown", "unscoped memo"), names(h).toSet())
            h.model.selectedTermLiveData.value = term("2")
            h.model.selectedExamTypeLiveData.value = ExamViewModel.ExamType.ALL
            assertEquals(setOf("other term", "unscoped memo"), names(h).toSet())
            assertEquals(1, h.exams.size)
        }
    }

    @Test fun refreshRetainsOldDataUntilAnExplicitRemoteLoadingStateArrives() {
        val h = create(listOf(exam("memo", memo = true)))
        h.start()
        h.selectAndRefresh()
        h.complete(0, DataState(listOf(exam("remote"))))
        h.main {
            h.now += 1_000
            h.examsInitialState = DataState(DataState.STATE.LOADING)
            h.model.startRefresh()
            assertEquals(DataState.STATE.LOADING, h.model.examInfoLiveData.value?.state)
            assertEquals(setOf("memo", "remote"), names(h).toSet())
        }
        h.flush()
        h.main {
            assertEquals(DataState.STATE.LOADING, h.model.examInfoLiveData.value?.state)
            assertNull(h.model.examInfoLiveData.value?.data)
            assertEquals(2, h.analytics.count { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_STARTED })
            assertEquals(1, h.analytics.count { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_SUCCEEDED })
            assertEquals(0, h.analytics.count { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_FAILED })
        }
    }

    @Test fun ordinaryFailureWithMemosRemainsVisibleSuccessButAnalyticsRecordsFailure() {
        val h = create(listOf(exam("memo", memo = true)))
        h.start()
        h.selectAndRefresh()
        h.complete(0, DataState(DataState.STATE.FETCH_FAILED, "offline"))
        h.main {
            assertEquals(DataState.STATE.SUCCESS, h.model.examInfoLiveData.value?.state)
            assertEquals("offline", h.model.examInfoLiveData.value?.message)
            assertEquals(listOf("memo"), names(h))
            assertEquals(listOf(UsageAnalyticsEvent.EXAMS_REFRESH_STARTED, UsageAnalyticsEvent.EXAMS_REFRESH_FAILED), h.analytics.map { it.event })
            assertEquals(mapOf("error_category" to "unknown"), h.analytics.last().dimensions)
        }
    }

    @Test fun ordinaryFailureWithoutLocalDataStaysFailureAndEmptySuccessStaysSuccess() {
        val h = create()
        h.start()
        h.selectAndRefresh()
        h.complete(0, DataState(DataState.STATE.FETCH_FAILED, "offline"))
        h.main {
            assertEquals(DataState.STATE.FETCH_FAILED, h.model.examInfoLiveData.value?.state)
            assertEquals("offline", h.model.examInfoLiveData.value?.message)
            h.now += 1_000
            h.model.startRefresh()
        }
        h.complete(1, DataState(emptyList()))
        h.main {
            assertEquals(DataState.STATE.SUCCESS, h.model.examInfoLiveData.value?.state)
            assertEquals(emptyList<String>(), names(h))
        }
    }

    @Test fun failedRefreshKeepsPreviousRemoteListAndAuthenticationStateIsNeverMasked() {
        val h = create(listOf(exam("memo", memo = true)))
        h.start()
        h.selectAndRefresh()
        h.complete(0, DataState(listOf(exam("remote"))))
        h.main { h.now += 1_000; h.model.startRefresh() }
        h.complete(1, DataState(DataState.STATE.FETCH_FAILED, "retry later"))
        h.main {
            assertEquals(DataState.STATE.SUCCESS, h.model.examInfoLiveData.value?.state)
            assertEquals(setOf("remote", "memo"), names(h).toSet())
            h.now += 1_000
            h.model.startRefresh()
        }
        h.complete(2, DataState(DataState.STATE.NOT_LOGGED_IN, "expired"))
        h.main {
            assertEquals(DataState.STATE.NOT_LOGGED_IN, h.model.examInfoLiveData.value?.state)
            assertEquals("expired", h.model.examInfoLiveData.value?.message)
            assertEquals(setOf("remote", "memo"), names(h).toSet())
        }
    }

    @Test fun memoChangesWhileQueryPendingAreIndependentAndFinalMergeUsesLatestMemos() {
        val oldMemo = exam("old memo", memo = true)
        val h = create(listOf(oldMemo))
        h.start()
        h.selectAndRefresh()
        h.main {
            assertNull(h.model.saveExamMemo(exam("new memo", memo = true)))
            assertNull(h.model.deleteExamMemo(oldMemo))
        }
        h.flush()
        h.main {
            assertEquals(DataState.STATE.SUCCESS, h.model.examInfoLiveData.value?.state)
            assertEquals(listOf("new memo"), names(h))
            assertEquals(listOf(UsageAnalyticsEvent.EXAMS_REFRESH_STARTED), h.analytics.map { it.event })
        }
        h.complete(0, DataState(listOf(exam("remote"), exam("fake remote memo", memo = true))))
        h.main {
            assertEquals(setOf("remote", "new memo"), names(h).toSet())
            assertEquals(listOf("new memo"), h.memoStore.getAll().map { it.courseName })
            assertEquals(1, h.exams.size)
        }
    }

    @Test fun replacingOrClearingRequestDetachesOldSourceWithoutFailureAnalytics() {
        val h = create()
        h.start()
        h.selectAndRefresh()
        h.main {
            h.now += 1_000
            h.model.startRefresh()
            assertFalse(h.exams.first().hasObservers())
            h.exams.first().value = DataState(listOf(exam("obsolete")))
            h.store.clear()
            assertFalse(h.exams.last().hasObservers())
            h.exams.last().value = DataState(listOf(exam("after clear")))
        }
        h.flush()
        h.main {
            assertEquals(DataState.STATE.LOADING, h.model.examInfoLiveData.value?.state)
            assertEquals(2, h.analytics.size)
            assertTrue(h.analytics.all { it.event == UsageAnalyticsEvent.EXAMS_REFRESH_STARTED })
        }
    }

    @Test fun memoSavedAfterTerminalIsQueuedRetainsLatestRemoteList() {
        val h = create()
        h.start()
        h.selectAndRefresh()
        h.main {
            h.exams[0].value = DataState(listOf(exam("remote")))
            h.model.saveExamMemo(exam("memo", memo = true))
        }
        h.flush()
        h.main {
            assertEquals(setOf("remote", "memo"), names(h).toSet())
            assertEquals(DataState.STATE.SUCCESS, h.model.examInfoLiveData.value?.state)
        }
    }

    @Test fun memoDeletedAfterTerminalIsQueuedRetainsLatestRemoteList() {
        val memo = exam("memo", memo = true)
        val h = create(listOf(memo))
        h.start()
        h.selectAndRefresh()
        h.main {
            h.exams[0].value = DataState(listOf(exam("remote")))
            h.model.deleteExamMemo(memo)
        }
        h.flush()
        h.main {
            assertEquals(listOf("remote"), names(h))
            assertEquals(DataState.STATE.SUCCESS, h.model.examInfoLiveData.value?.state)
        }
    }

    private fun names(h: ExamViewModelContractHarness) =
        h.model.examInfoLiveData.value?.data.orEmpty().map { it.courseName.orEmpty() }
}
