package cn.limpu.hita.data.repository

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.eas.TermQueryLiveData
import cn.limpu.hita.ui.eas.catalog.ShenzhenCourseCatalogViewModel
import cn.limpu.hita.ui.eas.grade.ShenzhenGradeAnalysisViewModel
import cn.limpu.hita.ui.eas.imp.ImportTimetableViewModel
import cn.limpu.hita.ui.eas.score.ScoreInquiryViewModel
import com.limpu.component.data.DataState
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executed against checkpoint 2 before fixing the boundary cases found during review. */
@RunWith(AndroidJUnit4::class)
class TermPageBoundaryRegressionTest : TermRepositoryContractHarness() {
    @Test fun offlineCachedImportTermDoesNotBypassStartDateLoginGate() {
        val fixture = fixture(accountToken().apply { cookies.clear() })
        fixture.seed(cachedRow())
        val store = ViewModelStore()
        lateinit var model: ImportTimetableViewModel
        val observer = Observer<DataState<Calendar>> {}
        main {
            model = fixture.importPage()
            store.put("import", model)
            model.startDateLiveData.observeForever(observer)
        }
        try {
            val terms = fixture.observeSource(model.termsLiveData)
            main { model.startRefreshTerms() }
            terms.await { it.state == DataState.STATE.SUCCESS }
            main {
                assertEquals(DataState.STATE.NOT_LOGGED_IN, model.startDateLiveData.value?.state)
                assertTrue("Offline identity cache must not call start-date source", fixture.scripted.metadataCalls.isEmpty())
            }
        } finally { main { model.startDateLiveData.removeObserver(observer); store.clear() } }
    }

    @Test fun offlineCachedImportTermDoesNotBypassScheduleLoginGate() {
        val fixture = fixture(accountToken().apply { cookies.clear() })
        fixture.seed(cachedRow())
        val store = ViewModelStore()
        lateinit var model: ImportTimetableViewModel
        val observer = Observer<DataState<MutableList<TimePeriodInDay>>> {}
        main {
            model = fixture.importPage()
            store.put("import", model)
            model.scheduleStructureLiveData.observeForever(observer)
        }
        try {
            val terms = fixture.observeSource(model.termsLiveData)
            main { model.startRefreshTerms() }
            terms.await { it.state == DataState.STATE.SUCCESS }
            main {
                assertEquals(DataState.STATE.NOT_LOGGED_IN, model.scheduleStructureLiveData.value?.state)
                assertTrue("Offline identity cache must not call schedule source", fixture.scripted.metadataCalls.isEmpty())
            }
        } finally { main { model.scheduleStructureLiveData.removeObserver(observer); store.clear() } }
    }

    @Test fun scoreManualTermKeySurvivesChangedRemoteIdWithoutRepeatingSelection() {
        val fixture = fixture()
        val store = ViewModelStore()
        lateinit var model: ScoreInquiryViewModel
        var selections = 0
        val observer = Observer<cn.limpu.hita.data.model.eas.TermItem?> { if (it != null) selections++ }
        main {
            model = ScoreInquiryViewModel(fixture.repository, SavedStateHandle())
            store.put("score", model)
            model.selectedTermLiveData.observeForever(observer)
        }
        try {
            main {
                model.selectTerm(remoteTerm(code = "1"))
                val other = remoteTerm(code = "2").apply { isCurrent = true }
                val refreshed = remoteTerm(code = "1", label = "renamed").apply { id = "server-custom-id" }
                model.reconcileTerms(listOf(other, refreshed))
                assertEquals("Manual key must survive cache-to-remote id changes", "1", model.selectedTermLiveData.value?.termCode)
                assertEquals("Metadata refresh must not repeat dependent requests", 1, selections)
            }
        } finally { main { model.selectedTermLiveData.removeObserver(observer); store.clear() } }
    }

    @Test fun gradeManualTermKeySurvivesChangedRemoteIdWithoutRepeatingSelection() {
        val fixture = fixture()
        val store = ViewModelStore()
        lateinit var model: ShenzhenGradeAnalysisViewModel
        var selections = 0
        val observer = Observer<cn.limpu.hita.data.model.eas.TermItem?> { if (it != null) selections++ }
        main {
            model = ShenzhenGradeAnalysisViewModel(fixture.repository)
            store.put("grade", model)
            model.selectedTerm.observeForever(observer)
        }
        try {
            main {
                model.selectTerm(remoteTerm(code = "1"))
                val other = remoteTerm(code = "2").apply { isCurrent = true }
                val refreshed = remoteTerm(code = "1", label = "renamed").apply { id = "server-custom-id" }
                model.reconcileTerms(listOf(other, refreshed))
                assertEquals("Manual key must survive cache-to-remote id changes", "1", model.selectedTerm.value?.termCode)
                assertEquals("Metadata refresh must not repeat dependent requests", 1, selections)
            }
        } finally { main { model.selectedTerm.removeObserver(observer); store.clear() } }
    }

    @Test fun sessionWatcherDefersCallbackUntilOwnerConstructionFinishes() {
        val initial = EasQueryScope.from(accountToken())
        val actual = MutableStateFlow(initial)
        // Model a background update between the constructor's snapshot read and collection.
        val racing = object : StateFlow<EasQueryScope> by actual {
            override val value: EasQueryScope
                get() = actual.value.also { actual.value = it.copy(sessionGeneration = it.sessionGeneration + 1) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var constructing = true
        var calledDuringConstruction = false
        var callbacks = 0
        try {
            main {
                TermQueryLiveData(scope, racing, createRequest = { null }, onSessionChanged = {
                    callbacks++
                    calledDuringConstruction = constructing
                })
                constructing = false
            }
            flushMain()
            assertEquals(1, callbacks)
            assertFalse("Session callback must not touch an incompletely initialized ViewModel", calledDuringConstruction)
        } finally { scope.cancel() }
    }

    @Test fun catalogSessionRefreshDoesNotReplaceInjectedPreviewStateWithoutSelectedTerm() {
        val fixture = fixture()
        val store = ViewModelStore()
        lateinit var model: ShenzhenCourseCatalogViewModel
        val termsObserver = Observer<DataState<List<TermItem>>> {}
        val startDateObserver = Observer<DataState<Calendar>> {}
        main {
            model = fixture.catalogPage()
            store.put("catalog", model)
            model.termsLiveData.observeForever(termsObserver)
            model.startDateLiveData.observeForever(startDateObserver)
        }
        try {
            main {
                assertNull(model.selectedTermLiveData.value)
                @Suppress("UNCHECKED_CAST")
                (model.startDateLiveData as MutableLiveData<DataState<Calendar>>).value = DataState(
                    Calendar.getInstance(), DataState.STATE.SUCCESS
                )
                assertEquals(DataState.STATE.SUCCESS, model.startDateLiveData.value?.state)
            }
            fixture.preferences.saveEasToken(accountToken().apply { sessionGeneration++ })
            flushMain()
            main {
                assertEquals(
                    "A session refresh must not rebuild the null-term NOTHING source",
                    DataState.STATE.SUCCESS,
                    model.startDateLiveData.value?.state
                )
            }
            main {
                model.startDateLiveData.removeObserver(startDateObserver)
                model.selectedTermLiveData.value = remoteTerm()
            }
            fixture.preferences.saveEasToken(accountToken().apply { sessionGeneration = 213L })
            flushMain()
            main {
                assertEquals(
                    "A selected term must still be cleared when the session changes",
                    null,
                    model.selectedTermLiveData.value
                )
            }
        } finally {
            main {
                model.termsLiveData.removeObserver(termsObserver)
                model.startDateLiveData.removeObserver(startDateObserver)
                store.clear()
            }
        }
    }

    @Test fun cancelledApplicationScopeStillCompletesAndReleasesAutomaticImport() {
        val fixture = fixture()
        EasQueryCoordinator.forSession(fixture.preferences.querySessionState).applicationScope.cancel()
        val results = CopyOnWriteArrayList<AutoImportResult>()
        val first = fixture.repository.startAutoImportCurrentTimetable(true, results::add)
        assertNotNull(first)
        runBlocking { first!!.join() }
        assertEquals("Pre-start cancellation must invoke completion exactly once", listOf(AutoImportResult.FAILED), results.toList())
        val next = fixture.repository.startAutoImportCurrentTimetable(true, results::add)
        assertNotNull("Pre-start cancellation must release the in-progress flag", next)
        runBlocking { next!!.join() }
        assertEquals(listOf(AutoImportResult.FAILED, AutoImportResult.FAILED), results.toList())
        assertTrue("Cancelled imports must not begin term IO", fixture.scripted.calls.isEmpty())
    }
}
