package cn.limpu.hita.ui.eas.score

import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.limpu.component.data.DataState
import com.limpu.component.data.MTransformations
import com.limpu.component.data.Trigger
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.ScoreSummary
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.ui.eas.EASViewModel
import cn.limpu.hita.ui.eas.TermQueryLiveData
import cn.limpu.hita.ui.eas.mapTerms
import cn.limpu.hita.ui.eas.TermSelectionDisplaySource
import cn.limpu.hita.ui.eas.sameTermKey
import cn.limpu.hita.utils.WeightedScoreCalculator
import cn.limpu.hita.utils.TermUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ScoreInquiryViewModel @Inject constructor(
    easRepo: EASRepository,
    private val savedStateHandle: SavedStateHandle
) : EASViewModel(easRepo) {

    companion object {
        private const val STATE_SELECTED_TERM_ID = "score_selected_term_id"
        private const val STATE_SELECTED_TERM_YEAR = "score_selected_term_year"
        private const val STATE_SELECTED_TERM_CODE = "score_selected_term_code"
        private const val STATE_SELECTED_TEST_TYPE = "score_selected_test_type"
    }

    /**
     * LiveData区
     */
    private val termQueries = TermQueryLiveData(viewModelScope, easRepo.querySessions,
        createRequest = { easRepo.createTermQueryRequest() },
        transform = { session, state -> state.mapTerms {
            TermUtils.filterTermsForStudent(it, session.grade, session.calendarYear, session.calendarMonth)
        } },
        onSessionChanged = {
            savedStateHandle.remove<String>(STATE_SELECTED_TERM_ID)
            savedStateHandle.remove<String>(STATE_SELECTED_TERM_YEAR)
            savedStateHandle.remove<String>(STATE_SELECTED_TERM_CODE)
            selectedTermLiveData.value = null
            selectedTermDisplay.updateOptions(emptyList())
        },
        onResult = { _, state -> if (state.state == DataState.STATE.SUCCESS) reconcileTerms(state.data.orEmpty()) },
    )
    val termsLiveData: LiveData<DataState<List<TermItem>>> = termQueries

    val selectedTermLiveData: MutableLiveData<TermItem> = MutableLiveData()
    internal val selectedTermDisplay = TermSelectionDisplaySource(selectedTermLiveData)
    val selectedTestTypeLiveData: MutableLiveData<EASService.TestType> = MutableLiveData()

    init {
        savedStateHandle.get<String>(STATE_SELECTED_TEST_TYPE)
            ?.let { saved -> runCatching { EASService.TestType.valueOf(saved) }.getOrNull() }
            ?.let { selectedTestTypeLiveData.value = it }
    }

    private val scoresWithSummaryLiveData =
        selectedTermLiveData.switchMap { term -> selectedTestTypeLiveData.switchMap testType@{ testType ->
            if (term == null || testType == null) {
                return@testType MutableLiveData(DataState<cn.limpu.hita.data.model.eas.ScoreQueryResult>(DataState.STATE.NOTHING))
            }
            val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.SCORES_REFRESH_STARTED)
            var analyticsFinished = false
            val capturedSession = easRepo.captureQuerySession()
            easRepo.getPersonalScoresWithSummaryFlow(
                capturedSession,
                selectedTermDisplay.current() ?: term,
                testType,
            ).asLiveData(viewModelScope.coroutineContext).map { result ->
                when (result.state) {
                    DataState.STATE.SUCCESS -> if (!analyticsFinished) {
                        analyticsFinished = true
                        UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.SCORES_REFRESH_SUCCEEDED)
                    }
                    DataState.STATE.NOTHING, DataState.STATE.LOADING -> Unit
                    else -> if (!analyticsFinished) {
                        analyticsFinished = true
                        UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.SCORES_REFRESH_FAILED, mapOf("error_category" to "unknown"))
                    }
                }
                result
            }
        } }

    val scoresLiveData: LiveData<DataState<List<CourseScoreItem>>> =
        scoresWithSummaryLiveData.map { state ->
            DataState(state.data?.items ?: emptyList(), state.state).also {
                it.message = state.message
                it.fromCache = state.fromCache
                it.listAction = state.listAction
                it.stateRetried = state.stateRetried
            }
        }

    val scoreSummaryLiveData: LiveData<ScoreSummary?> =
        scoresWithSummaryLiveData.map { it.data?.summary }

    /** 当前筛选结果中，仅使用可解析数字成绩得到的本页估算。 */
    val localScoreLiveData: LiveData<WeightedScoreCalculator.ScoreResult> =
        scoresLiveData.map { state ->
            WeightedScoreCalculator.calculate(state.data ?: emptyList())
        }

    /**
     * 方法区
     */
    fun startRefresh() {
        termQueries.refresh()
    }

    fun reconcileTerms(terms: List<TermItem>) {
        selectedTermDisplay.updateOptions(terms)
        val current = selectedTermLiveData.value
        val year = current?.yearCode ?: savedStateHandle.get<String>(STATE_SELECTED_TERM_YEAR)
        val code = current?.termCode ?: savedStateHandle.get<String>(STATE_SELECTED_TERM_CODE)
        val selected = terms.firstOrNull { it.yearCode == year && it.termCode == code }
            ?: chooseScoreTerm(terms, if (year == null || code == null) savedStateHandle[STATE_SELECTED_TERM_ID] else null)
        if (selected == null) {
            selectedTermLiveData.value = null
            savedStateHandle.remove<String>(STATE_SELECTED_TERM_ID)
            savedStateHandle.remove<String>(STATE_SELECTED_TERM_YEAR)
            savedStateHandle.remove<String>(STATE_SELECTED_TERM_CODE)
            return
        }
        selectTerm(selected)
    }

    fun selectTerm(term: TermItem) {
        selectedTermDisplay.updateTerm(term)
        savedStateHandle[STATE_SELECTED_TERM_ID] = term.id
        savedStateHandle[STATE_SELECTED_TERM_YEAR] = term.yearCode
        savedStateHandle[STATE_SELECTED_TERM_CODE] = term.termCode
        if (!sameTermKey(selectedTermLiveData.value, term)) {
            selectedTermLiveData.value = term
        }
    }

    fun ensureDefaultTestType() {
        if (selectedTestTypeLiveData.value == null) {
            selectTestType(EASService.TestType.NORMAL)
        }
    }

    fun selectTestType(testType: EASService.TestType) {
        savedStateHandle[STATE_SELECTED_TEST_TYPE] = testType.name
        if (selectedTestTypeLiveData.value != testType) {
            selectedTestTypeLiveData.value = testType
        }
    }

    fun retryCurrentQuery(): Boolean {
        val term = selectedTermDisplay.current() ?: return false
        val testType = selectedTestTypeLiveData.value ?: return false
        selectedTermLiveData.value = term
        selectedTestTypeLiveData.value = testType
        return true
    }

}

internal fun chooseScoreTerm(terms: List<TermItem>, selectedId: String?): TermItem? {
    return terms.firstOrNull { it.id == selectedId }
        ?: terms.firstOrNull { it.isCurrent }
        ?: terms.firstOrNull()
}
