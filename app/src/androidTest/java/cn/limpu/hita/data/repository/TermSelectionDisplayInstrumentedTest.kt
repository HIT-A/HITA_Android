package cn.limpu.hita.data.repository

import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.ui.eas.TermSelectionDisplay
import cn.limpu.hita.ui.eas.grade.ShenzhenGradeAnalysisViewModel
import cn.limpu.hita.ui.eas.score.ScoreInquiryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermSelectionDisplayInstrumentedTest : TermRepositoryContractHarness() {
    @Test fun sameIdGradeMetadataUpdatesUiStateWithoutAnotherSelectionEvent() {
        val fixture = fixture()
        val store = ViewModelStore()
        lateinit var model: ShenzhenGradeAnalysisViewModel
        var selections = 0
        val snapshots = mutableListOf<TermSelectionDisplay?>()
        val selectedObserver = Observer<TermItem> { if (it != null) selections++ }
        val displayObserver = Observer<TermSelectionDisplay?> { snapshots += it }
        main {
            model = ShenzhenGradeAnalysisViewModel(fixture.repository)
            store.put("grade", model)
            model.selectedTerm.observeForever(selectedObserver)
            model.selectedTermDisplay.observeForever(displayObserver)
        }
        try {
            main {
                val cached = remoteTerm(label = "cached")
                model.reconcileTerms(listOf(cached))
                val before = snapshots.last()
                val remote = remoteTerm(label = "remote").apply { name = "fresh name"; isCurrent = true }
                model.reconcileTerms(listOf(remote))
                assertEquals(TermSelectionDisplay.from(remote), snapshots.last())
                assertNotEquals(before, snapshots.last())
                assertEquals(1, selections)
                assertEquals("cached", cached.termName)
            }
        } finally { main {
            model.selectedTerm.removeObserver(selectedObserver)
            model.selectedTermDisplay.removeObserver(displayObserver)
            store.clear()
        } }
    }

    @Test fun scoreRestoresManualTermByYearAndCodeAfterRemoteIdChanges() {
        val fixture = fixture()
        val state = SavedStateHandle()
        val store = ViewModelStore()
        try {
            main {
                val original = ScoreInquiryViewModel(fixture.repository, state)
                store.put("score", original)
                original.selectTerm(remoteTerm(code = "1").apply { id = "old-server-id" })
                store.clear()
                val restored = ScoreInquiryViewModel(fixture.repository, state)
                store.put("score", restored)
                restored.reconcileTerms(listOf(
                    remoteTerm(code = "2").apply { isCurrent = true },
                    remoteTerm(code = "1").apply { id = "new-server-id" },
                ))
                assertEquals("1", restored.selectedTermLiveData.value?.termCode)
                assertEquals("new-server-id", restored.selectedTermLiveData.value?.id)
            }
        } finally { main { store.clear() } }
    }
}
