package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One remote query and its campus initial state, captured before starting asynchronous work. */
internal class ExamQueryRequest(
    val initialState: DataState<List<ExamItem>>?,
    private val query: suspend () -> DataState<List<ExamItem>>,
) {
    suspend fun execute(): DataState<List<ExamItem>> {
        currentCoroutineContext().ensureActive()
        val result = query()
        currentCoroutineContext().ensureActive()
        return result
    }

    companion object {
        fun forToken(
            token: EASToken,
            term: TermItem?,
            query: suspend (EASToken, TermItem?) -> DataState<List<ExamItem>>,
        ): ExamQueryRequest {
            if (!token.isLogin()) {
                return ExamQueryRequest(null) { DataState(DataState.STATE.NOT_LOGGED_IN) }
            }
            val capturedTerm = term?.let {
                TermItem(it.yearCode, it.yearName, it.termCode, it.termName).apply {
                    name = it.name
                    id = it.id
                    isCurrent = it.isCurrent
                }
            }
            val initial = if (token.campus == EASToken.Campus.WEIHAI) {
                DataState<List<ExamItem>>(DataState.STATE.LOADING)
            } else null
            return ExamQueryRequest(initial) { query(token, capturedTerm) }
        }
    }
}
