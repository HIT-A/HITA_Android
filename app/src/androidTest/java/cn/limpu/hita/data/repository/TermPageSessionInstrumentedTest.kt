package cn.limpu.hita.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.ui.eas.score.ScoreInquiryViewModel
import com.limpu.component.data.DataState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermPageSessionInstrumentedTest : TermRepositoryContractHarness() {
    @Test fun activePageClearsTheOldSelectionAndRequeriesWithTheNewAccount() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        val store = ViewModelStore()
        lateinit var model: ScoreInquiryViewModel
        main { model = ScoreInquiryViewModel(fixture.repository, SavedStateHandle()); store.put("score", model) }
        try {
            val observed = fixture.observeSource(model.termsLiveData)
            main { model.startRefresh() }
            val obsolete = fixture.scripted.awaitCall()
            observed.await { it.fromCache }
            fixture.preferences.saveEasToken(accountToken().apply { stuId = "other-student"; sessionGeneration++ })
            val current = fixture.scripted.awaitCall()
            main { assertNull(model.selectedTermLiveData.value) }
            assertEquals("other-student", current.token.stuId)
            fixture.scripted.complete(obsolete, DataState(listOf(remoteTerm(label = "obsolete owner"))))
            fixture.scripted.complete(current, DataState(listOf(remoteTerm(label = "current owner"))))
            observed.await { it.data?.singleOrNull()?.termName == "current owner" }
            assertFalse(observed.states.any { it.data?.any { term -> term.termName == "obsolete owner" } == true })
            assertEquals("cached", fixture.rows().single().termName)
            assertEquals("current owner", fixture.rows("BENBU:other-student").single().termName)
        } finally { main { store.clear() } }
    }

    @Test fun refreshWhileInactiveCancelsTheOldRequestAndDefersItsReplacementUntilObserved() {
        val fixture = fixture()
        val store = ViewModelStore()
        lateinit var model: ScoreInquiryViewModel
        main { model = ScoreInquiryViewModel(fixture.repository, SavedStateHandle()); store.put("score", model) }
        try {
            val observed = fixture.observeSource(model.termsLiveData)
            main { model.startRefresh() }
            val obsolete = fixture.scripted.awaitCall()
            observed.close()
            main { model.startRefresh() }
            fixture.scripted.complete(obsolete, DataState(listOf(remoteTerm(label = "obsolete refresh"))))
            flushMain()
            assertEquals(1, fixture.scripted.calls.size)
            assertTrue(fixture.rows().isEmpty())
            observed.attach()
            val current = fixture.scripted.awaitCall()
            fixture.scripted.complete(current, DataState(listOf(remoteTerm(label = "visible refresh"))))
            observed.await { it.data?.singleOrNull()?.termName == "visible refresh" }
            assertFalse(observed.states.any { it.data?.any { term -> term.termName == "obsolete refresh" } == true })
            assertEquals("visible refresh", fixture.rows().single().termName)
        } finally { main { store.clear() } }
    }
}
