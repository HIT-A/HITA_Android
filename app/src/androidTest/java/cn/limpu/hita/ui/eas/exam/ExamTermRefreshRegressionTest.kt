package cn.limpu.hita.ui.eas.exam

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExamTermRefreshRegressionTest {
    @Test fun choosingTheInitialTermDoesNotRestartTheSameTermQuery() {
        val harness = ExamViewModelContractHarness()
        try {
            harness.start()
            harness.main {
                harness.model.startRefresh()
                harness.terms.single().value = DataState(listOf(ExamViewModelContractHarness.term("1")))
                assertEquals(1, harness.exams.size)
                assertEquals("The initial choice must reuse its in-flight term query", 1, harness.terms.size)
            }
        } finally { harness.close() }
    }
}
