package cn.limpu.hita.ui.eas.exam

import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExamTermLoadGateTest {
    private fun term(code: String, current: Boolean = false) =
        TermItem("2026-2027", "2026-2027", code, code).apply { isCurrent = current }

    @Test fun failureFinishesRequestAndRetryCanSelectOnce() {
        val gate = ExamTermLoadGate()
        gate.begin()
        assertEquals(
            ExamTermLoadOutcome.Failed(DataState.STATE.FETCH_FAILED, "offline"),
            gate.consume(DataState(DataState.STATE.FETCH_FAILED, "offline"))
        )
        val current = term("1", true)
        val success = DataState(listOf(current))
        assertSame(ExamTermLoadOutcome.Ignored, gate.consume(success))
        gate.begin()
        gate.begin()
        assertEquals(ExamTermLoadOutcome.Selected(current), gate.consume(success))
        assertSame(ExamTermLoadOutcome.Ignored, gate.consume(success))
    }

    @Test fun emptySuccessIsUnavailableRatherThanNoExams() {
        val gate = ExamTermLoadGate()
        gate.begin()
        assertEquals(
            ExamTermLoadOutcome.Failed(DataState.STATE.NOT_AVAILABLE, null),
            gate.consume(DataState(emptyList()))
        )
        assertSame(ExamTermLoadOutcome.Ignored, gate.consume(DataState(listOf(term("1")))))
    }

    @Test fun loadingDoesNotConsumePendingSelectionAndCurrentTermWins() {
        val gate = ExamTermLoadGate()
        gate.begin()
        assertSame(ExamTermLoadOutcome.Waiting, gate.consume(DataState(DataState.STATE.LOADING)))
        assertSame(ExamTermLoadOutcome.Waiting, gate.consume(DataState(DataState.STATE.NOTHING)))
        val current = term("2", true)
        assertEquals(
            ExamTermLoadOutcome.Selected(current),
            gate.consume(DataState(listOf(term("1"), current)))
        )
    }

    @Test fun loginFailureRemainsLoginFailure() {
        val gate = ExamTermLoadGate()
        gate.begin()
        assertEquals(
            ExamTermLoadOutcome.Failed(DataState.STATE.NOT_LOGGED_IN, "expired"),
            gate.consume(DataState(DataState.STATE.NOT_LOGGED_IN, "expired"))
        )
    }

    @Test fun cancellationIgnoresLateResultsAndMissingCurrentUsesFirstTerm() {
        val gate = ExamTermLoadGate()
        gate.begin()
        gate.cancel()
        val first = term("1")
        val success = DataState(listOf(first, term("2")))
        assertSame(ExamTermLoadOutcome.Ignored, gate.consume(success))
        gate.begin()
        assertEquals(ExamTermLoadOutcome.Selected(first), gate.consume(success))
    }
}
