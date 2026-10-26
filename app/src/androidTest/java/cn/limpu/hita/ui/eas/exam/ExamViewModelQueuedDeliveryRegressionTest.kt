package cn.limpu.hita.ui.eas.exam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Observer
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.ui.eas.exam.ExamViewModelContractHarness.Companion.exam
import cn.limpu.hita.ui.eas.exam.ExamViewModelContractHarness.Companion.term
import com.limpu.component.data.DataState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Correct behavior assertions: expected red on the old unguarded postValue implementation. */
@RunWith(AndroidJUnit4::class)
class ExamViewModelQueuedDeliveryRegressionTest {
    private lateinit var h: ExamViewModelContractHarness

    @Before fun setUp() {
        h = ExamViewModelContractHarness()
        h.start()
        h.selectAndRefresh()
    }

    @After fun tearDown() { h.close() }

    @Test fun terminalAlreadyQueuedBeforeRefreshCannotOverwriteNewLoadingState() {
        h.main {
            // The old observer accepts the terminal result, but its raw-state post cannot run
            // until this main-thread turn returns. Start the next refresh in that same turn.
            h.exams.first().value = DataState(listOf(exam("old result")))
            h.now += 1_000
            h.model.startRefresh()
            assertEquals(DataState.STATE.LOADING, h.model.examInfoLiveData.value?.state)
        }
        h.flush()
        h.main {
            assertEquals("Queued result belongs to the replaced request", DataState.STATE.LOADING, h.model.examInfoLiveData.value?.state)
        }
    }

    @Test fun terminalAlreadyQueuedBeforeClearCannotPublishAfterViewModelClear() {
        var stateAtClear: DataState<List<cn.limpu.hita.data.model.eas.ExamItem>>? = null
        h.main {
            h.exams.first().value = DataState(listOf(exam("result before clear")))
            stateAtClear = h.model.examInfoLiveData.value
            h.store.clear()
        }
        h.flush()
        h.main {
            assertSame("Queued result must stop at ViewModel clear", stateAtClear, h.model.examInfoLiveData.value)
        }
    }
    @Test fun loadingObserverSelectingNewTermPreventsOuterOldTermRequest() {
        var changedTerm = false
        var armed = false
        val observer = Observer<DataState<List<ExamItem>>> { state ->
            if (armed && !changedTerm && state.state == DataState.STATE.LOADING &&
                h.model.selectedTermLiveData.value == term("2")) {
                changedTerm = true
                h.model.selectedTermLiveData.value = term("3")
                h.model.startRefresh()
            }
        }
        h.main {
            h.model.examInfoLiveData.observe(h.owner, observer)
            h.now += 1_000
            h.model.selectedTermLiveData.value = term("2")
            armed = true
            h.model.startRefresh()
            assertEquals(
                "The reentrant latest refresh must own the requested term",
                listOf(term("1"), term("3")), h.requestedTerms,
            )
        }
    }

}
