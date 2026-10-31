package cn.limpu.hita.data.repository

import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.ui.eas.grade.ShenzhenGradeAnalysisViewModel
import cn.limpu.hita.ui.eas.score.ScoreInquiryViewModel
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Each assertion is first executed against the original page implementation. */
@RunWith(AndroidJUnit4::class)
class TermPageSafetyRegressionTest : TermRepositoryContractHarness() {
    @Test fun scoreTermFilteringPreservesCachedMetadata() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        val store = ViewModelStore()
        lateinit var model: ScoreInquiryViewModel
        main { model = ScoreInquiryViewModel(fixture.repository, SavedStateHandle()); store.put("score", model) }
        try {
            val observed = fixture.observeSource(model.termsLiveData)
            main { model.startRefresh() }
            val call = fixture.scripted.awaitCall()
            fixture.scripted.complete(call, DataState(DataState.STATE.FETCH_FAILED))
            val cached = observed.await { it.state == DataState.STATE.SUCCESS }
            assertTrue("Filtering must preserve the cache marker", cached.fromCache)
        } finally { main { store.clear() } }
    }

    @Test fun gradeTermReconciliationDoesNotRepeatSelectionForTheSameIdentity() {
        val fixture = fixture()
        val store = ViewModelStore()
        lateinit var model: ShenzhenGradeAnalysisViewModel
        val selections = mutableListOf<TermItem>()
        val observer = Observer<TermItem?> { if (it != null) selections += it }
        main {
            model = ShenzhenGradeAnalysisViewModel(fixture.repository)
            store.put("grade", model)
            model.selectedTerm.observeForever(observer)
        }
        try {
            main {
                model.reconcileTerms(listOf(remoteTerm(label = "cached")))
                model.reconcileTerms(listOf(remoteTerm(label = "remote metadata")))
                assertEquals("Same-key cache/remote replay must not restart dependent requests", 1, selections.size)
            }
        } finally { main { model.selectedTerm.removeObserver(observer); store.clear() } }
    }
}
