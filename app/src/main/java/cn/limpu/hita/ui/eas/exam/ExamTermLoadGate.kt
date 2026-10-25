package cn.limpu.hita.ui.eas.exam

import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState

/** Only one pending initial term selection may consume a terminal result. */
internal class ExamTermLoadGate {
    private var awaitingResult = false

    fun begin() { awaitingResult = true }
    fun cancel() { awaitingResult = false }

    fun consume(result: DataState<List<TermItem>>): ExamTermLoadOutcome {
        if (!awaitingResult) return ExamTermLoadOutcome.Ignored
        if (result.state == DataState.STATE.LOADING || result.state == DataState.STATE.NOTHING) {
            return ExamTermLoadOutcome.Waiting
        }
        awaitingResult = false
        if (result.state == DataState.STATE.SUCCESS) {
            val terms = result.data.orEmpty()
            val term = terms.find { it.isCurrent } ?: terms.firstOrNull()
            return term?.let { ExamTermLoadOutcome.Selected(it) }
                ?: ExamTermLoadOutcome.Failed(DataState.STATE.NOT_AVAILABLE, result.message)
        }
        return ExamTermLoadOutcome.Failed(result.state, result.message)
    }
}

internal sealed interface ExamTermLoadOutcome {
    data object Waiting : ExamTermLoadOutcome
    data object Ignored : ExamTermLoadOutcome
    data class Selected(val term: TermItem) : ExamTermLoadOutcome
    data class Failed(val state: DataState.STATE, val message: String?) : ExamTermLoadOutcome
}
